package com.amaral.driverlab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ResultFiles {
    private ResultFiles() {}

    static void writeAtomic(File target, String value) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Não foi possível criar " + parent);
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Falha ao publicar arquivo " + target.getName());
        }
    }

    static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 indisponível", error);
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder output = new StringBuilder(64);
        for (byte value : digest.digest()) {
            output.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return output.toString();
    }

    static boolean isInside(File parent, File child) throws IOException {
        String root = parent.getCanonicalPath() + File.separator;
        return child.getCanonicalPath().startsWith(root);
    }
}
