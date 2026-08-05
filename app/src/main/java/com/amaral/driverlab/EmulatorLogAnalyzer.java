package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmulatorLogAnalyzer {
    static final int SCHEMA_VERSION = 1;
    static final int DEFAULT_MAX_BYTES = 2_000_000;
    private static final int MAX_FINDINGS = 24;
    private static final int MAX_EXCERPT_LINES = 90;
    private static final int MAX_EXCERPT_CHARS = 24_000;

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern ANDROID_PATH = Pattern.compile(
            "(?i)(?:/storage/emulated/\\d+|/sdcard|/data/(?:user|data)/\\d+|/home/[^/\\s]+)(?:/[^\\s\\]\\[(){}<>\"']*)?");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)\\b[A-Z]:\\\\Users\\\\[^\\\\\\s]+(?:\\\\[^\\s\\]\\[(){}<>\"']*)?");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)\\b(token|authorization|bearer|api[_-]?key|access[_-]?token)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private static final Pattern GPU = Pattern.compile(
            "(?i)\\b(Adreno(?:\\s*\\(TM\\))?\\s*\\d{3}|Mali[-\\s][A-Za-z0-9_-]+|Xclipse\\s*\\d+|Apple\\s+[A-Za-z0-9_-]+\\s+GPU)\\b");
    private static final Pattern DRIVER = Pattern.compile(
            "(?i)\\b((?:Mesa\\s+)?Turnip[^\\n,;]{0,90}|Mesa\\s+\\d{2,4}\\.\\d+(?:\\.\\d+)?[^\\n,;]{0,60}|Qualcomm[^\\n,;]{0,80}Vulkan[^\\n,;]{0,80})");
    private static final Pattern API = Pattern.compile(
            "(?i)\\b(Vulkan(?:\\s+API)?\\s*(?:version)?\\s*[0-9]+(?:\\.[0-9]+){1,2}|OpenGL\\s*ES\\s*[0-9.]+|DXVK\\s*[0-9.]+|VKD3D(?:-Proton)?\\s*[0-9.]+|Direct3D\\s*1[0-2])\\b");
    private static final Pattern GAME = Pattern.compile(
            "(?im)^(?:game|title|application|program)(?:\\s+name)?\\s*[:=]\\s*([^\\n]{2,160})$");
    private static final Pattern TITLE_ID = Pattern.compile(
            "(?im)^(?:title|program|game)[ _-]?id\\s*[:=]\\s*([A-F0-9-]{8,32})$");
    private static final Pattern VERSION = Pattern.compile(
            "(?i)\\b(?:version|build|release)\\s*[:=]?\\s*(v?[0-9]+(?:\\.[0-9A-Za-z_-]+){1,5})\\b");

    private static final String[][] EMULATORS = {
            {"Eden", "(?i)\\bEden\\b"},
            {"Citron", "(?i)\\bCitron\\b"},
            {"Sudachi", "(?i)\\bSudachi\\b"},
            {"Yuzu", "(?i)\\bYuzu\\b"},
            {"GameHub", "(?i)\\bGame\\s*Hub\\b"},
            {"Winlator", "(?i)\\bWinlator\\b"},
            {"XenDroid", "(?i)\\bXenDroid\\b"},
            {"Xenia", "(?i)\\bXenia\\b"},
            {"RPCS3", "(?i)\\bRPCS3\\b"},
            {"aPS3e", "(?i)\\baPS3e\\b"},
            {"Cemu", "(?i)\\bCemu\\b"},
            {"Dolphin", "(?i)\\bDolphin\\b"},
            {"NetherSX2", "(?i)\\bNetherSX2\\b"},
            {"AetherSX2", "(?i)\\bAetherSX2\\b"},
            {"RetroArch", "(?i)\\bRetroArch\\b"},
            {"PPSSPP", "(?i)\\bPPSSPP\\b"}
    };

    private EmulatorLogAnalyzer() {}

    static ReadResult read(InputStream input, int maximumBytes) throws Exception {
        if (input == null) throw new IllegalArgumentException("Log indisponível");
        if (maximumBytes < 1) throw new IllegalArgumentException("Limite de log inválido");
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int remaining = maximumBytes;
            boolean truncated = false;
            while (remaining > 0) {
                int count = source.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (remaining == 0 && source.read() >= 0) truncated = true;
            byte[] bytes = output.toByteArray();
            String text;
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                text = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                text = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            } else {
                text = new String(bytes, StandardCharsets.UTF_8);
            }
            return new ReadResult(text, bytes.length, truncated);
        }
    }

    static JSONObject analyze(String rawLog, String fileName, int sourceBytes,
                              boolean truncated, JSONObject device, String appVersion) throws Exception {
        if (rawLog == null || rawLog.trim().isEmpty()) {
            throw new IllegalArgumentException("O arquivo selecionado está vazio");
        }
        String normalized = rawLog.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u0000", "");
        RedactionResult redaction = redact(normalized);
        String sanitized = redaction.text;
        String[] lines = sanitized.split("\n", -1);

        String emulator = detectEmulator(sanitized);
        String emulatorVersion = detectVersion(sanitized, emulator);
        String game = capture(GAME, sanitized);
        String titleId = capture(TITLE_ID, sanitized);
        String gpu = capture(GPU, sanitized);
        String driver = capture(DRIVER, sanitized);
        String api = capture(API, sanitized);

        int fatalCount = 0;
        int errorCount = 0;
        int warningCount = 0;
        JSONArray findings = new JSONArray();
        Set<String> excerpt = new LinkedHashSet<>();

        for (int index = 0; index < lines.length; index++) {
            String compact = compact(lines[index]);
            if (compact.isEmpty()) continue;
            String lower = compact.toLowerCase(Locale.ROOT);
            boolean fatal = containsAny(lower, "fatal", "crash", "segfault", "sigsegv",
                    "device lost", "vk_error_device_lost", "anr", "abort");
            boolean error = fatal || containsAny(lower, " error", "error:", "exception",
                    "failed", "failure", "vk_error", "assertion");
            boolean warning = containsAny(lower, "warning", "warn:", "w/");
            if (fatal) fatalCount++;
            if (error) errorCount++;
            if (warning) warningCount++;

            boolean identity = containsAny(lower, "emulator", "version", "build", "gpu",
                    "adreno", "mali", "turnip", "mesa", "vulkan", "driver",
                    "game:", "title:", "title id", "program id");
            if ((fatal || error || warning) && findings.length() < MAX_FINDINGS) {
                findings.put("L" + (index + 1) + ": " + limit(compact, 300));
            }
            if ((fatal || error || warning || identity) && excerpt.size() < MAX_EXCERPT_LINES) {
                excerpt.add("L" + (index + 1) + ": " + limit(compact, 500));
            }
        }

        if (findings.length() == 0) {
            findings.put("No explicit fatal/error markers were detected; manual review is recommended.");
        }

        String severity = fatalCount > 0 ? "fatal"
                : errorCount > 0 ? "error"
                : warningCount > 0 ? "warning" : "informational";
        String excerptText = joinExcerpt(excerpt);
        JSONObject safeDevice = device == null ? new JSONObject() : new JSONObject(device.toString());

        JSONObject report = new JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("source_file", safeFileName(fileName))
                .put("source_size_bytes", sourceBytes)
                .put("source_truncated", truncated)
                .put("source_line_count", lines.length)
                .put("sanitized_sha256", sha256(sanitized))
                .put("privacy_redactions", redaction.count)
                .put("device", safeDevice)
                .put("emulator", valueOrUnknown(emulator))
                .put("emulator_version", valueOrUnknown(emulatorVersion))
                .put("game", valueOrUnknown(game))
                .put("title_id", valueOrUnknown(titleId))
                .put("gpu", valueOrUnknown(gpu))
                .put("graphics_api", valueOrUnknown(api))
                .put("driver", valueOrUnknown(driver))
                .put("severity", severity)
                .put("fatal_count", fatalCount)
                .put("error_count", errorCount)
                .put("warning_count", warningCount)
                .put("key_findings", findings)
                .put("relevant_excerpt", excerptText);

        String title = issueTitle(report);
        String body = issueBody(report, appVersion);
        report.put("issue_title", title);
        report.put("issue_body", body);
        report.put("preview", preview(report));
        return report;
    }

    static String issueTitle(JSONObject report) {
        JSONObject device = report.optJSONObject("device");
        String model = device == null ? "Android device"
                : device.optString("model", "Android device");
        String emulator = report.optString("emulator", "Unknown emulator");
        String driver = report.optString("driver", "Unknown driver");
        if (driver.length() > 55) driver = driver.substring(0, 55);
        String title = "[Emulator Log] " + emulator + " · " + model + " · "
                + driver + " · " + report.optString("severity", "informational");
        return title.length() > 240 ? title.substring(0, 240) : title;
    }

    static String issueBody(JSONObject report, String appVersion) {
        JSONObject device = report.optJSONObject("device");
        StringBuilder body = new StringBuilder();
        body.append("## Emulator log report — Amaral Driver Lab\n\n");
        body.append("> This summary was generated locally from a selected emulator log. ")
                .append("Sensitive paths, e-mail addresses, tokens and IP addresses were redacted automatically.\n\n");
        body.append("### Environment\n\n");
        body.append("| Field | Value |\n|---|---|\n");
        row(body, "Device", device == null ? "Unknown"
                : device.optString("manufacturer", "") + " " + device.optString("model", ""));
        row(body, "Android", device == null ? "Unknown"
                : device.optString("android_release", "Unknown") + " / API "
                + device.optInt("android_sdk", 0));
        row(body, "Emulator", report.optString("emulator", "Unknown"));
        row(body, "Emulator version", report.optString("emulator_version", "Unknown"));
        row(body, "Game / application", report.optString("game", "Unknown"));
        row(body, "Title / program ID", report.optString("title_id", "Unknown"));
        row(body, "GPU", report.optString("gpu", "Unknown"));
        row(body, "Graphics API", report.optString("graphics_api", "Unknown"));
        row(body, "Driver", report.optString("driver", "Unknown"));
        row(body, "Severity", report.optString("severity", "informational"));
        row(body, "Fatal / error / warning lines",
                report.optInt("fatal_count") + " / " + report.optInt("error_count")
                        + " / " + report.optInt("warning_count"));
        row(body, "Source file", report.optString("source_file", "log"));
        row(body, "Imported bytes", String.valueOf(report.optInt("source_size_bytes")));
        row(body, "Log truncated by importer", String.valueOf(report.optBoolean("source_truncated")));
        row(body, "Privacy redactions", String.valueOf(report.optInt("privacy_redactions")));
        row(body, "Sanitized SHA-256", "`" + report.optString("sanitized_sha256") + "`");

        body.append("\n### User context\n\n")
                .append("- **What were you trying to run?** \n")
                .append("- **What happened?** \n")
                .append("- **What did you expect?** \n")
                .append("- **Can the problem be reproduced?** \n")
                .append("- **Turnip ZIP/release used:** \n\n");

        body.append("### Automatically detected findings\n\n");
        JSONArray findings = report.optJSONArray("key_findings");
        if (findings == null || findings.length() == 0) {
            body.append("- No explicit findings detected.\n");
        } else {
            for (int index = 0; index < findings.length(); index++) {
                body.append("- `").append(escapeCode(findings.optString(index))).append("`\n");
            }
        }

        body.append("\n<details><summary>Relevant sanitized log excerpt</summary>\n\n```text\n")
                .append(report.optString("relevant_excerpt", "No relevant excerpt detected."))
                .append("\n```\n</details>\n\n");
        body.append("### Import limitations\n\n")
                .append("- This parser uses pattern matching and can misidentify fields.\n")
                .append("- The report contains selected relevant lines, not necessarily the complete log.\n")
                .append("- A truncated source is explicitly marked above.\n")
                .append("- The summary does not prove that a Turnip driver caused the issue.\n\n");
        body.append("_Generated by Amaral Driver Lab ")
                .append(appVersion == null ? "" : appVersion)
                .append(" · emulator-log schema v").append(SCHEMA_VERSION).append("._");
        return body.toString();
    }

    private static String preview(JSONObject report) {
        return report.optString("emulator") + " · "
                + report.optString("gpu") + "\n"
                + report.optString("driver") + "\n"
                + "Fatal " + report.optInt("fatal_count")
                + " · Errors " + report.optInt("error_count")
                + " · Warnings " + report.optInt("warning_count")
                + " · Redactions " + report.optInt("privacy_redactions");
    }

    private static RedactionResult redact(String value) {
        int count = 0;
        Replacement step = replace(EMAIL, value, "<redacted-email>");
        value = step.text; count += step.count;
        step = replace(ANDROID_PATH, value, "<redacted-path>");
        value = step.text; count += step.count;
        step = replace(WINDOWS_PATH, value, "<redacted-path>");
        value = step.text; count += step.count;
        step = replace(IPV4, value, "<redacted-ip>");
        value = step.text; count += step.count;

        Matcher matcher = TOKEN.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + "=<redacted-secret>"));
        }
        matcher.appendTail(output);
        return new RedactionResult(output.toString(), count);
    }

    private static Replacement replace(Pattern pattern, String value, String replacement) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return new Replacement(output.toString(), count);
    }

    private static String detectEmulator(String text) {
        for (String[] entry : EMULATORS) {
            if (Pattern.compile(entry[1]).matcher(text).find()) return entry[0];
        }
        return null;
    }

    private static String detectVersion(String text, String emulator) {
        if (emulator != null) {
            Pattern near = Pattern.compile("(?i)\\b" + Pattern.quote(emulator)
                    + "\\b[^\\n]{0,50}?\\b(v?[0-9]+(?:\\.[0-9A-Za-z_-]+){1,5})\\b");
            String version = capture(near, text);
            if (version != null) return version;
        }
        return capture(VERSION, text);
    }

    private static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
        return compact(value);
    }

    private static String joinExcerpt(Set<String> lines) {
        if (lines.isEmpty()) return "No relevant lines were detected automatically.";
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() + line.length() + 1 > MAX_EXCERPT_CHARS) break;
            result.append(line).append('\n');
        }
        return result.toString().trim();
    }

    private static String safeFileName(String value) {
        if (value == null || value.trim().isEmpty()) return "emulator.log";
        String safe = value.replace('\\', '/');
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) safe = safe.substring(slash + 1);
        safe = safe.replaceAll("[\\r\\n\\t]", "_");
        return limit(safe, 160);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "Unknown" : limit(value.trim(), 180);
    }

    private static String compact(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void row(StringBuilder body, String field, String value) {
        String safe = value == null ? "Unknown" : value.replace("|", "\\|")
                .replace("\n", " ").trim();
        if (safe.isEmpty()) safe = "Unknown";
        body.append("| ").append(field).append(" | ").append(safe).append(" |\n");
    }

    private static String escapeCode(String value) {
        return value == null ? "" : value.replace("`", "'");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
        return result.toString();
    }

    static final class ReadResult {
        final String text;
        final int bytesRead;
        final boolean truncated;

        ReadResult(String text, int bytesRead, boolean truncated) {
            this.text = text;
            this.bytesRead = bytesRead;
            this.truncated = truncated;
        }
    }

    private static final class RedactionResult {
        final String text;
        final int count;

        RedactionResult(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private static final class Replacement {
        final String text;
        final int count;

        Replacement(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }
}
