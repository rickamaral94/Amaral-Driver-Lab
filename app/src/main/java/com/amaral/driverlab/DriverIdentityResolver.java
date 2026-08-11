package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves emulator-log driver identity from sanitized runtime evidence. */
final class DriverIdentityResolver {
    static final int POLICY_VERSION = DriverIdentityPolicy.POLICY_VERSION;

    static final String QUALCOMM_BLOB = DriverIdentityPolicy.QUALCOMM_BLOB;
    static final String TURNIP = DriverIdentityPolicy.TURNIP;
    static final String OTHER = DriverIdentityPolicy.OTHER;
    static final String UNKNOWN = DriverIdentityPolicy.UNKNOWN;

    static final String RUNTIME_CONFIRMED = DriverIdentityPolicy.RUNTIME_CONFIRMED;
    static final String INFERRED = DriverIdentityPolicy.INFERRED;
    static final String DISPUTED = DriverIdentityPolicy.DISPUTED;
    static final String UNAUDITED = DriverIdentityPolicy.UNAUDITED;

    private static final Pattern BLOCK_START = Pattern.compile(
            "(?i)(?:emuwindow[^\\n]{0,80}initializ|===\\s*[^=]{0,80}gpu logging started\\s*===|"
                    + "(?:initializ|creat)[^\\n]{0,60}vulkan(?: device| context| window)?)");
    private static final Pattern QUALCOMM_VENDOR = Pattern.compile(
            "(?i)\\b(Qualcomm\\s+(?:Proprietary|Technologies(?:,?\\s*Inc\\.?)?))\\b");
    private static final Pattern BLOB_VERSION = Pattern.compile(
            "(?<![0-9.])(\\d{3}\\.\\d{3}\\.\\d{1,3})(?![0-9.])");
    private static final Pattern MESA_VERSION = Pattern.compile(
            "(?i)\\b(Mesa\\s+\\d{1,3}(?:\\.[0-9A-Za-z_-]+){1,4}(?:\\s*\\([^)]*\\))?)");
    private static final Pattern GPU_LOGGING_DRIVER = Pattern.compile(
            "(?i)\\[GPU Logging\\][^\\n]{0,160}?\\bdriver\\s*:\\s*([^,;\\n]+)");
    private static final Pattern USING_GPU = Pattern.compile(
            "(?i)\\b(?:using GPU|\\[Driver\\]\\s*Device)\\s*:\\s*([^,;\\n]+)");
    private static final Pattern FIELD = Pattern.compile(
            "(?i)\\b(Vulkan Driver|Driver Name|Driver Info|Driver ID|Driver Version|Driver)\\s*[:=]\\s*(.*)$");
    private static final Pattern CONFIGURATION = Pattern.compile(
            "(?i)\\b(?:selected|configured|configuration|setting|package|custom)\\b[^\\n]{0,80}\\bdriver\\b"
                    + "|\\bdriver\\b[^\\n]{0,80}\\b(?:selected|configured|configuration|setting|package|custom)\\b");

    private DriverIdentityResolver() {}

    static JSONObject resolve(String sanitizedLog) throws Exception {
        String[] lines = sanitizedLog == null ? new String[0] : sanitizedLog.split("\\n", -1);
        List<Block> blocks = splitBlocks(lines);
        List<Evidence> evidence = new ArrayList<>();
        for (Block block : blocks) collectEvidence(lines, block, evidence);
        for (Block block : blocks) block.resolve(evidence);

        Resolution resolution = aggregate(blocks, evidence);
        JSONArray evidenceJson = new JSONArray();
        for (Evidence item : evidence) evidenceJson.put(item.toJson());
        JSONArray blocksJson = new JSONArray();
        for (Block block : blocks) {
            if (block.hasEvidence(evidence)) blocksJson.put(block.toJson());
        }

        return new JSONObject()
                .put("driver", resolution.identity)
                .put("driver_display_name", resolution.displayName)
                .put("driver_identity_confidence", resolution.confidence)
                .put("driver_identity_policy_version", POLICY_VERSION)
                .put("driver_identity_evidence", evidenceJson)
                .put("driver_identity_blocks", blocksJson);
    }

    static JSONObject normalizeForRead(JSONObject report) throws Exception {
        JSONObject normalized = report == null ? new JSONObject() : new JSONObject(report.toString());
        if (!normalized.has("driver_identity_confidence")) {
            normalized.put("driver_identity_confidence",
                    normalized.optInt("schema_version", 1) < 2 ? UNAUDITED : INFERRED);
        }
        if (!normalized.has("driver") || normalized.optString("driver").trim().isEmpty()) {
            normalized.put("driver", UNKNOWN);
        }
        return normalized;
    }

    static boolean isEligibleForAggregation(JSONObject report) {
        if (report == null) return false;
        String confidence = report.optString("driver_identity_confidence", UNAUDITED);
        String identity = report.optString("driver", UNKNOWN);
        return RUNTIME_CONFIRMED.equals(confidence)
                && !UNKNOWN.equals(identity)
                && !identity.trim().isEmpty();
    }

    private static List<Block> splitBlocks(String[] lines) {
        List<Block> blocks = new ArrayList<>();
        int start = 0;
        int number = 1;
        boolean seenInitialization = false;
        for (int index = 0; index < lines.length; index++) {
            if (!BLOCK_START.matcher(lines[index]).find()) continue;
            if (!seenInitialization) {
                seenInitialization = true;
            } else if (index > start) {
                blocks.add(new Block(number++, start, index - 1));
                start = index;
            }
        }
        if (lines.length > 0) blocks.add(new Block(number, start, lines.length - 1));
        if (blocks.isEmpty()) blocks.add(new Block(1, 0, 0));
        return blocks;
    }

    private static void collectEvidence(String[] lines, Block block, List<Evidence> output) {
        for (int index = block.start; index <= block.end && index < lines.length; index++) {
            String content = compact(lines[index]);
            if (content.isEmpty()) continue;
            boolean configuration = CONFIGURATION.matcher(content).find();

            Matcher vendor = QUALCOMM_VENDOR.matcher(content);
            if (vendor.find()) {
                output.add(new Evidence(block.number, index + 1,
                        configuration ? "app_configuration" : "runtime_vendor",
                        configuration ? 3 : 1, content, QUALCOMM_BLOB, vendor.group(1),
                        !configuration, false));
            }

            Matcher runtime = GPU_LOGGING_DRIVER.matcher(content);
            if (runtime.find()) {
                addIdentityEvidence(output, block, index, content, "runtime_emulator", 1,
                        runtime.group(1), false);
            }
            Matcher usingGpu = USING_GPU.matcher(content);
            if (usingGpu.find()) {
                addIdentityEvidence(output, block, index, content, "runtime_device", 1,
                        usingGpu.group(1), false);
            }

            Matcher field = FIELD.matcher(content);
            if (field.find()) {
                String label = field.group(1).toLowerCase(Locale.ROOT).replace(' ', '_');
                String value = compact(field.group(2));
                String source = configuration ? "app_configuration"
                        : "driver".equals(label) ? "runtime_emulator" : "vulkan_" + label;
                int priority = configuration ? 3 : "driver".equals(label) ? 1 : 2;
                addIdentityEvidence(output, block, index, content, source, priority,
                        value, configuration);
            }

            Matcher blobVersion = BLOB_VERSION.matcher(content);
            if (blobVersion.find() && !hasVersionEvidence(output, index + 1, blobVersion.group(1))) {
                output.add(new Evidence(block.number, index + 1, "version_pattern", 2,
                        content, UNKNOWN, blobVersion.group(1), false, true));
            }
            Matcher mesaVersion = MESA_VERSION.matcher(content);
            if (mesaVersion.find() && !hasVersionEvidence(output, index + 1, mesaVersion.group(1))) {
                output.add(new Evidence(block.number, index + 1, "mesa_version", 2,
                        content, UNKNOWN, mesaVersion.group(1), false, true));
            }
        }
    }

    private static void addIdentityEvidence(List<Evidence> output, Block block, int lineIndex,
                                            String content, String source, int priority,
                                            String rawValue, boolean configuration) {
        String value = normalizeFieldValue(rawValue);
        if (value.isEmpty()) return;
        if (source.endsWith("driver_version")) return;
        if (source.endsWith("driver_id") && value.matches("\\d+")) return;
        String identity = classifyStrongIdentity(value);
        if (OTHER.equals(identity)
                && (source.endsWith("driver_info") || source.endsWith("driver_id"))) return;
        if (UNKNOWN.equals(identity)) return;
        output.add(new Evidence(block.number, lineIndex + 1, source, priority, content,
                identity, displayName(identity, value), !configuration, false));
    }

    private static String classifyStrongIdentity(String value) {
        String sharedIdentity = DriverIdentityPolicy.classifyRuntimeText(value);
        if (QUALCOMM_BLOB.equals(sharedIdentity) || TURNIP.equals(sharedIdentity)) {
            return sharedIdentity;
        }
        String withoutPunctuation = value.replaceAll("[-: ]", "");
        if (withoutPunctuation.isEmpty()
                || BLOB_VERSION.matcher(value).matches()
                || MESA_VERSION.matcher(value).matches()
                || value.matches("(?i)(?:vulkan|driver|unknown|null|none|-)+")) {
            return UNKNOWN;
        }
        return OTHER;
    }

    private static String normalizeFieldValue(String rawValue) {
        String value = compact(rawValue);
        if (value.startsWith("-")) value = compact(value.substring(1));
        return value;
    }

    private static Resolution aggregate(List<Block> blocks, List<Evidence> evidence) {
        boolean hardQualcomm = false;
        boolean disputed = false;
        boolean runtime = false;
        boolean inferred = false;
        Set<String> identities = new LinkedHashSet<>();
        for (Evidence item : evidence) {
            if (item.runtime && item.strong && QUALCOMM_BLOB.equals(item.identity)) hardQualcomm = true;
        }
        for (Block block : blocks) {
            if (!block.hasEvidence(evidence)) continue;
            if (DISPUTED.equals(block.confidence)
                    && (!hardQualcomm || !UNKNOWN.equals(block.identity))) disputed = true;
            if (!UNKNOWN.equals(block.identity)) identities.add(block.identity);
            if (RUNTIME_CONFIRMED.equals(block.confidence)) runtime = true;
            if (INFERRED.equals(block.confidence)) inferred = true;
        }
        if (identities.size() > 1) disputed = true;

        String identity;
        if (hardQualcomm) identity = QUALCOMM_BLOB;
        else if (identities.size() == 1) identity = identities.iterator().next();
        else identity = UNKNOWN;

        String confidence = disputed ? DISPUTED
                : runtime ? RUNTIME_CONFIRMED
                : inferred ? INFERRED : INFERRED;
        return new Resolution(identity, confidence, bestDisplayName(identity, evidence));
    }

    private static String bestDisplayName(String identity, List<Evidence> evidence) {
        Evidence best = null;
        for (Evidence item : evidence) {
            if (!item.strong || !identity.equals(item.identity)) continue;
            if (best == null || item.priority < best.priority
                    || (item.priority == best.priority && item.line < best.line)) best = item;
        }
        if (best != null) return best.displayName;
        if (QUALCOMM_BLOB.equals(identity)) return "Qualcomm proprietary driver";
        if (TURNIP.equals(identity)) return "Turnip (Mesa Freedreno)";
        if (OTHER.equals(identity)) return "Other Vulkan driver";
        return "Unknown";
    }

    private static String displayName(String identity, String value) {
        Matcher vendor = QUALCOMM_VENDOR.matcher(value);
        if (QUALCOMM_BLOB.equals(identity) && vendor.find()) return vendor.group(1);
        return limit(compact(value), 180);
    }

    private static boolean hasVersionEvidence(List<Evidence> evidence, int line, String value) {
        for (Evidence item : evidence) {
            if (item.line == line && item.corroborating && item.displayName.equals(value)) return true;
        }
        return false;
    }

    private static String compact(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static final class Block {
        final int number;
        final int start;
        final int end;
        String identity = UNKNOWN;
        String confidence = INFERRED;

        Block(int number, int start, int end) {
            this.number = number;
            this.start = start;
            this.end = end;
        }

        void resolve(List<Evidence> allEvidence) {
            Set<String> runtimeIdentities = new LinkedHashSet<>();
            Set<String> configuredIdentities = new LinkedHashSet<>();
            boolean hardQualcomm = false;
            boolean blobVersionOnly = false;
            for (Evidence item : allEvidence) {
                if (item.block != number) continue;
                if (item.corroborating && "version_pattern".equals(item.source)) blobVersionOnly = true;
                if (!item.strong) continue;
                if (item.runtime) {
                    runtimeIdentities.add(item.identity);
                    if (QUALCOMM_BLOB.equals(item.identity)) hardQualcomm = true;
                } else {
                    configuredIdentities.add(item.identity);
                }
            }
            if (hardQualcomm) {
                identity = QUALCOMM_BLOB;
                confidence = hasContradiction(identity, runtimeIdentities, configuredIdentities)
                        ? DISPUTED : RUNTIME_CONFIRMED;
            } else if (runtimeIdentities.size() == 1) {
                identity = runtimeIdentities.iterator().next();
                confidence = blobVersionOnly
                        || hasContradiction(identity, runtimeIdentities, configuredIdentities)
                        ? DISPUTED : RUNTIME_CONFIRMED;
            } else if (runtimeIdentities.size() > 1) {
                identity = UNKNOWN;
                confidence = DISPUTED;
            } else if (configuredIdentities.size() == 1) {
                identity = configuredIdentities.iterator().next();
                confidence = blobVersionOnly ? DISPUTED : INFERRED;
            } else if (configuredIdentities.size() > 1 || blobVersionOnly) {
                identity = UNKNOWN;
                confidence = DISPUTED;
            }
        }

        boolean hasEvidence(List<Evidence> evidence) {
            for (Evidence item : evidence) if (item.block == number) return true;
            return false;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("block", number)
                    .put("start_line", start + 1)
                    .put("end_line", end + 1)
                    .put("driver", identity)
                    .put("driver_identity_confidence", confidence);
        }

        private static boolean hasContradiction(String selected, Set<String> runtime,
                                                Set<String> configured) {
            for (String identity : runtime) if (!selected.equals(identity)) return true;
            for (String identity : configured) if (!selected.equals(identity)) return true;
            return false;
        }
    }

    private static final class Evidence {
        final int block;
        final int line;
        final String source;
        final int priority;
        final String content;
        final String identity;
        final String displayName;
        final boolean runtime;
        final boolean corroborating;
        final boolean strong;

        Evidence(int block, int line, String source, int priority, String content,
                 String identity, String displayName, boolean runtime, boolean corroborating) {
            this.block = block;
            this.line = line;
            this.source = source;
            this.priority = priority;
            this.content = limit(content, 500);
            this.identity = identity;
            this.displayName = limit(displayName, 180);
            this.runtime = runtime;
            this.corroborating = corroborating;
            this.strong = !corroborating && !UNKNOWN.equals(identity);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("block", block)
                    .put("source", source)
                    .put("priority", priority)
                    .put("line", line)
                    .put("content", content)
                    .put("identity", identity)
                    .put("strength", corroborating ? "corroborating" : "identity");
        }
    }

    private static final class Resolution {
        final String identity;
        final String confidence;
        final String displayName;

        Resolution(String identity, String confidence, String displayName) {
            this.identity = identity;
            this.confidence = confidence;
            this.displayName = displayName;
        }
    }
}
