package com.amaral.driverlab;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DriverImporter {
    private static final long MAX_PACKAGE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 192L * 1024L * 1024L;
    private static final long MAX_TOTAL_EXTRACTED_BYTES = 384L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 128;

    private DriverImporter() {}

    static DriverPackage importZip(Context context, Uri uri) throws Exception {
        File packagesDir = new File(context.getFilesDir(), "packages");
        File driversDir = new File(context.getFilesDir(), "drivers");
        if ((!packagesDir.isDirectory() && !packagesDir.mkdirs())
                || (!driversDir.isDirectory() && !driversDir.mkdirs())) {
            throw new IOException("Não foi possível preparar o armazenamento interno");
        }

        File incoming = new File(packagesDir, "incoming-" + UUID.randomUUID() + ".zip");
        String sha256;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Android não forneceu acesso ao ZIP");
            }
            sha256 = copyAndHash(input, incoming);
        }

        File sourceZip = new File(packagesDir, sha256 + ".zip");
        if (!sourceZip.isFile() && !incoming.renameTo(sourceZip)) {
            throw new IOException("Não foi possível guardar o pacote importado");
        }
        if (incoming.exists() && !incoming.delete()) {
            incoming.deleteOnExit();
        }

        File finalDirectory = new File(driversDir, sha256);
        File descriptor = new File(finalDirectory, "descriptor.json");
        if (descriptor.isFile()) {
            DriverPackage cached = DriverPackage.fromJson(ResultFiles.readUtf8(descriptor));
            if (cached.isUsable()) {
                return cached;
            }
        }
        if (finalDirectory.exists()) {
            deleteTree(finalDirectory);
        }

        File temporaryDirectory = new File(driversDir, ".partial-" + UUID.randomUUID());
        if (!temporaryDirectory.mkdir()) {
            throw new IOException("Não foi possível criar a pasta temporária do driver");
        }

        try {
            JSONObject metadata;
            try (ZipFile zip = new ZipFile(sourceZip)) {
                validateArchive(zip);
                ZipEntry metadataEntry = zip.getEntry("meta.json");
                if (metadataEntry == null || metadataEntry.isDirectory()) {
                    throw new IOException("Pacote inválido: meta.json não está na raiz");
                }
                metadata = new JSONObject(readEntry(zip, metadataEntry, 1024L * 1024L));
                validateMetadata(metadata, zip);
                extractLibraries(zip, temporaryDirectory);
            }

            makeCodeReadOnly(temporaryDirectory);

            if (!temporaryDirectory.renameTo(finalDirectory)) {
                throw new IOException("Não foi possível finalizar a importação do driver");
            }

            DriverPackage result = fromMetadata(sha256, metadata, finalDirectory, sourceZip);
            ResultFiles.writeAtomic(new File(finalDirectory, "descriptor.json"),
                    result.toJson().toString(2));
            return result;
        } catch (Exception error) {
            deleteTree(temporaryDirectory);
            throw error;
        }
    }

    private static String copyAndHash(InputStream input, File destination) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException("ZIP excede o limite de 256 MiB");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static void validateArchive(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        Set<String> names = new HashSet<>();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            count++;
            if (count > MAX_ENTRIES) {
                throw new IOException("ZIP possui entradas demais");
            }
            String name = entry.getName();
            if (!isSafeArchiveName(name) || !names.add(name)) {
                throw new IOException("Entrada ZIP insegura ou duplicada: " + name);
            }
            if (entry.getSize() > MAX_ENTRY_BYTES) {
                throw new IOException("Entrada ZIP grande demais: " + name);
            }
        }
    }

    private static boolean isSafeArchiveName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/")
                || name.startsWith("\\") || name.contains("\\") || name.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    private static void validateMetadata(JSONObject metadata, ZipFile zip) throws IOException {
        int schema = metadata.optInt("schemaVersion", -1);
        String name = metadata.optString("name", "").trim();
        String library = metadata.optString("libraryName", "").trim();
        int minApi = metadata.optInt("minApi", 28);
        if (schema < 1 || name.isEmpty()) {
            throw new IOException("meta.json sem schemaVersion/name válido");
        }
        if (library.isEmpty() || library.contains("/") || library.contains("\\")
                || !library.endsWith(".so")) {
            throw new IOException("libraryName inválido em meta.json");
        }
        ZipEntry libraryEntry = zip.getEntry(library);
        if (libraryEntry == null || libraryEntry.isDirectory()) {
            throw new IOException("Biblioteca declarada não encontrada na raiz: " + library);
        }
        if (minApi > Build.VERSION.SDK_INT) {
            throw new IOException("Driver exige Android API " + minApi
                    + ", aparelho está na API " + Build.VERSION.SDK_INT);
        }
    }

    private static void extractLibraries(ZipFile zip, File destination) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        long total = 0;
        int libraryCount = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".so")) {
                continue;
            }
            if (entry.getName().contains("/")) {
                throw new IOException("Bibliotecas .so devem estar na raiz do ZIP: " + entry.getName());
            }
            libraryCount++;
            File outputFile = new File(destination, entry.getName());
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                long entryTotal = 0;
                while ((count = input.read(buffer)) >= 0) {
                    entryTotal += count;
                    total += count;
                    if (entryTotal > MAX_ENTRY_BYTES || total > MAX_TOTAL_EXTRACTED_BYTES) {
                        throw new IOException("Conteúdo descompactado excede o limite seguro");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
        }
        if (libraryCount == 0) {
            throw new IOException("Nenhuma biblioteca .so encontrada");
        }
    }

    private static String readEntry(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    throw new IOException("meta.json grande demais");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static DriverPackage fromMetadata(String sha256, JSONObject metadata,
                                              File directory, File sourceZip) {
        return new DriverPackage(
                sha256,
                metadata.optString("name", "Driver sem nome"),
                metadata.optString("packageVersion", ""),
                metadata.optString("vendor", ""),
                metadata.optString("driverVersion", ""),
                metadata.optInt("minApi", 28),
                metadata.optString("libraryName", "libvulkan_freedreno.so"),
                directory,
                sourceZip,
                metadata);
    }

    private static void makeCodeReadOnly(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Falha ao enumerar bibliotecas importadas");
        }
        for (File file : files) {
            if (file.getName().endsWith(".so")) {
                validateElf64Arm64(file);
                if (!file.setReadable(true, true) || !file.setWritable(false, false)) {
                    throw new IOException("Não foi possível proteger " + file.getName());
                }
            }
        }
    }

    private static void validateElf64Arm64(File library) throws IOException {
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(library)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset < header.length) {
                throw new IOException("Biblioteca ELF truncada: " + library.getName());
            }
        }
        boolean elf = (header[0] & 0xff) == 0x7f
                && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        int elfClass = header[4] & 0xff;
        int byteOrder = header[5] & 0xff;
        int type = (header[16] & 0xff) | ((header[17] & 0xff) << 8);
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if (!elf || elfClass != 2 || byteOrder != 1 || type != 3 || machine != 183) {
            throw new IOException("Biblioteca não é ELF64 AArch64 compartilhada: "
                    + library.getName());
        }
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!root.delete()) {
            root.deleteOnExit();
        }
    }
}
