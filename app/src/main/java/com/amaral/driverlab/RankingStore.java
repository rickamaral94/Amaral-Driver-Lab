package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Base64;

/** Append-only measurement persistence plus device-signed export/import envelopes. */
final class RankingStore {
    static final int EXPORT_SCHEMA_VERSION = 1;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private RankingStore() {}

    static File append(File filesDir, MeasurementRecord record) throws Exception {
        File directory = recordsDirectory(filesDir);
        File destination = new File(directory, safeId(record.id()) + ".json");
        if (destination.exists()) {
            MeasurementRecord existing = new MeasurementRecord(
                    new JSONObject(ResultFiles.readUtf8(destination)));
            if (!existing.toJson().toString().equals(record.toJson().toString())) {
                throw new IllegalStateException("Registro append-only já existe: " + record.id());
            }
            return destination;
        }
        ResultFiles.writeAtomic(destination, record.toJson().toString(2));
        return destination;
    }

    static List<MeasurementRecord> load(File filesDir) throws Exception {
        migrateLegacyWithoutDeleting(filesDir);
        File[] files = recordsDirectory(filesDir).listFiles(
                file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<MeasurementRecord> output = new ArrayList<>();
        for (File file : files) {
            try {
                output.add(new MeasurementRecord(new JSONObject(ResultFiles.readUtf8(file))));
            } catch (Exception ignored) {
                // A malformed file is retained for diagnostics; it never becomes evidence.
            }
        }
        return output;
    }

    static JSONObject exportEnvelope(File filesDir, List<MeasurementRecord> records,
                                     JSONObject fingerprint) throws Exception {
        JSONArray payload = new JSONArray();
        for (MeasurementRecord record : records) payload.put(record.toJson());
        String payloadText = payload.toString();
        SigningMaterial signing = signingMaterial(filesDir);
        String publicKey = Base64.getEncoder().encodeToString(signing.publicKey.getEncoded());
        String keyId = EnvironmentFingerprint.sha256(publicKey);
        return new JSONObject()
                .put("ranking_export_schema_version", EXPORT_SCHEMA_VERSION)
                .put("exported_at_ms", System.currentTimeMillis())
                .put("origin_device_fingerprint_sha256",
                        fingerprint.getString("device_fingerprint_sha256"))
                .put("signature_algorithm", SIGNATURE_ALGORITHM)
                .put("signing_key_id", keyId)
                .put("signing_public_key_x509_base64", publicKey)
                .put("records", payload)
                .put("signature_base64", sign(signing.privateKey, payloadText));
    }

    static int importEnvelope(File filesDir, JSONObject envelope,
                              String currentDeviceFingerprint) throws Exception {
        if (envelope.optInt("ranking_export_schema_version", -1) != EXPORT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Schema de exportação incompatível");
        }
        JSONArray records = envelope.getJSONArray("records");
        if (!SIGNATURE_ALGORITHM.equals(envelope.optString("signature_algorithm"))) {
            throw new SecurityException("Algoritmo de assinatura não aceito");
        }
        String publicText = envelope.getString("signing_public_key_x509_base64");
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicText)));
        if (!EnvironmentFingerprint.sha256(publicText).equals(
                envelope.optString("signing_key_id"))
                || !verify(publicKey, records.toString(),
                envelope.getString("signature_base64"))) {
            throw new SecurityException("Assinatura do histórico inválida");
        }
        SigningMaterial local = signingMaterial(filesDir);
        String localPublic = Base64.getEncoder().encodeToString(local.publicKey.getEncoded());
        boolean localSignature = EnvironmentFingerprint.sha256(localPublic).equals(
                envelope.optString("signing_key_id"));
        String origin = envelope.optString("origin_device_fingerprint_sha256", "unknown");
        boolean foreign = !currentDeviceFingerprint.equals(origin);
        int imported = 0;
        for (int index = 0; index < records.length(); index++) {
            JSONObject value = new JSONObject(records.getJSONObject(index).toString())
                    .put("imported", true)
                    .put("foreign_device", foreign)
                    .put("import_signature_status", localSignature
                            ? "verified_local" : "verified_foreign" );
            MeasurementRecord record = new MeasurementRecord(value);
            append(filesDir, record);
            imported++;
        }
        return imported;
    }

    static void migrateLegacyWithoutDeleting(File filesDir) throws Exception {
        File legacy = new File(new File(filesDir, "ranking"), "measurements.json");
        if (!legacy.isFile()) return;
        JSONArray values;
        try {
            values = new JSONArray(ResultFiles.readUtf8(legacy));
        } catch (Exception ignored) {
            return;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            if (!value.has("measurement_schema_version")) {
                value.put("measurement_schema_version", MeasurementRecord.SCHEMA_VERSION);
            }
            try { append(filesDir, new MeasurementRecord(value)); }
            catch (Exception ignored) { /* Preserve unreadable legacy bytes in place. */ }
        }
    }

    private static File recordsDirectory(File filesDir) {
        File directory = new File(new File(filesDir, "ranking"), "measurements");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o histórico de ranking");
        }
        return directory;
    }

    private static SigningMaterial signingMaterial(File filesDir) throws Exception {
        File directory = new File(filesDir, "ranking");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o armazenamento de ranking");
        }
        File privateFile = new File(directory, "export-signing-private.pk8");
        File publicFile = new File(directory, "export-signing-public.x509");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (!privateFile.isFile() || !publicFile.isFile()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair generated = generator.generateKeyPair();
            ResultFiles.writeAtomic(privateFile, Base64.getEncoder().encodeToString(
                    generated.getPrivate().getEncoded()));
            ResultFiles.writeAtomic(publicFile, Base64.getEncoder().encodeToString(
                    generated.getPublic().getEncoded()));
            return new SigningMaterial(generated.getPrivate(), generated.getPublic());
        }
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(ResultFiles.readUtf8(privateFile).trim())));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(ResultFiles.readUtf8(publicFile).trim())));
        return new SigningMaterial(privateKey, publicKey);
    }

    private static String sign(PrivateKey key, String payload) throws Exception {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(key);
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static boolean verify(PublicKey key, String payload, String encoded) throws Exception {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(key);
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(encoded));
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{1,160}")) {
            throw new IllegalArgumentException("measurement_id inválido");
        }
        return value;
    }

    private static final class SigningMaterial {
        final PrivateKey privateKey;
        final PublicKey publicKey;
        SigningMaterial(PrivateKey privateKey, PublicKey publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }
}
