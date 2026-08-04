package com.amaral.driverlab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VisualSceneAnalysis {
    private VisualSceneAnalysis() {}

    static JSONObject analyze(JSONArray phases, File suiteDirectory, int expectedRounds, int mode,
                              int pixelTolerance, int maximumDivergentBlocks) throws Exception {
        int failedPhases = 0;
        int completeComparisons = 0;
        int mismatchCount = 0;
        double minimumPixelMatch = Double.POSITIVE_INFINITY;
        int maximumObservedDivergentBlocks = 0;
        JSONArray comparisons = new JSONArray();
        Map<Integer, Set<String>> systemHashes = new HashMap<>();
        Map<Integer, Set<String>> candidateHashes = new HashMap<>();
        for (int frame : VisualSceneContract.CHECKPOINT_FRAMES) {
            systemHashes.put(frame, new HashSet<>());
            candidateHashes.put(frame, new HashSet<>());
        }

        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) {
                failedPhases++;
                continue;
            }
            JSONObject evidence = phase.optJSONObject("evidence");
            if (!validEvidence(evidence)) {
                failedPhases++;
                continue;
            }
            boolean custom = "custom".equals(phase.optString("driver_mode"));
            for (int frame : VisualSceneContract.CHECKPOINT_FRAMES) {
                JSONObject checkpoint = checkpoint(evidence, frame);
                if (checkpoint == null) {
                    failedPhases++;
                    break;
                }
                (custom ? candidateHashes : systemHashes).get(frame)
                        .add(checkpoint.optString("sha256_rgba", ""));
            }
        }

        if (mode == RunCoordinator.MODE_AB) {
            for (int round = 1; round <= expectedRounds; ++round) {
                JSONObject system = find(phases, round, false);
                JSONObject candidate = find(phases, round, true);
                if (system == null || candidate == null) continue;
                JSONObject systemEvidence = system.optJSONObject("evidence");
                JSONObject candidateEvidence = candidate.optJSONObject("evidence");
                if (!validEvidence(systemEvidence) || !validEvidence(candidateEvidence)) continue;
                for (int frame : VisualSceneContract.CHECKPOINT_FRAMES) {
                    JSONObject systemCheckpoint = checkpoint(systemEvidence, frame);
                    JSONObject candidateCheckpoint = checkpoint(candidateEvidence, frame);
                    if (systemCheckpoint == null || candidateCheckpoint == null) continue;
                    int[] reference = loadPixels(suiteDirectory,
                            systemCheckpoint.getString("relative_path"));
                    int[] output = loadPixels(suiteDirectory,
                            candidateCheckpoint.getString("relative_path"));
                    RenderComparator.Result comparison = RenderComparator.compare(
                            reference, output,
                            VisualSceneContract.WIDTH, VisualSceneContract.HEIGHT,
                            pixelTolerance, VisualSceneContract.BLOCK_SIZE,
                            VisualSceneContract.MINIMUM_BLOCK_MATCH_PERCENT);
                    JSONObject encoded = comparison.toJson(maximumDivergentBlocks)
                            .put("round", round)
                            .put("checkpoint_frame", frame)
                            .put("metric_note", Phase8Contract.LIMITATION)
                            .put("system_sha256_rgba",
                                    systemCheckpoint.optString("sha256_rgba"))
                            .put("candidate_sha256_rgba",
                                    candidateCheckpoint.optString("sha256_rgba"));
                    if (!comparison.passes(maximumDivergentBlocks)) {
                        mismatchCount++;
                        File heatmap = writeHeatmap(suiteDirectory, round, frame,
                                reference, output, pixelTolerance);
                        encoded.put("heatmap_relative_path", heatmap.getName());
                    } else {
                        encoded.put("heatmap_relative_path", JSONObject.NULL);
                    }
                    comparisons.put(encoded);
                    completeComparisons++;
                    minimumPixelMatch = Math.min(minimumPixelMatch,
                            comparison.pixelMatchPercent());
                    maximumObservedDivergentBlocks = Math.max(
                            maximumObservedDivergentBlocks,
                            comparison.divergentBlocks.size());
                }
            }
        }

        boolean systemNondeterministic = anyNondeterministic(systemHashes);
        boolean candidateNondeterministic = anyNondeterministic(candidateHashes);
        int expectedComparisons = mode == RunCoordinator.MODE_AB
                ? expectedRounds * VisualSceneContract.CHECKPOINT_FRAMES.length : 0;
        boolean available = mode == RunCoordinator.MODE_AB && completeComparisons > 0;
        boolean passed = available
                && completeComparisons == expectedComparisons
                && mismatchCount == 0
                && failedPhases == 0
                && !systemNondeterministic
                && !candidateNondeterministic;

        return new JSONObject()
                .put("checkpoint_analysis_version",
                        Phase8Contract.CHECKPOINT_ANALYSIS_VERSION)
                .put("comparison_available", available)
                .put("expected_comparison_count", expectedComparisons)
                .put("complete_comparison_count", completeComparisons)
                .put("checkpoint_mismatch_count", mismatchCount)
                .put("failed_phase_count", failedPhases)
                .put("minimum_pixel_match_percent", completeComparisons == 0
                        ? JSONObject.NULL : minimumPixelMatch)
                .put("maximum_divergent_block_count", completeComparisons == 0
                        ? JSONObject.NULL : maximumObservedDivergentBlocks)
                .put("system_checkpoint_hashes", hashesJson(systemHashes))
                .put("candidate_checkpoint_hashes", hashesJson(candidateHashes))
                .put("system_nondeterministic", systemNondeterministic)
                .put("candidate_nondeterministic", candidateNondeterministic)
                .put("comparisons", comparisons)
                .put("passed_correctness_gate", passed)
                .put("comparison_policy",
                        "paired_checkpoint_pixel_diff_with_per_frame_determinism")
                .put("limitations", Phase8Contract.LIMITATION);
    }

    static String verdictFor(JSONObject visual, JSONObject statistics, int mode) {
        if (visual.optInt("failed_phase_count", 0) > 0) {
            return "failed_visual_scene_execution";
        }
        if (visual.optBoolean("system_nondeterministic", false)
                || visual.optBoolean("candidate_nondeterministic", false)) {
            return "failed_visual_scene_nondeterminism";
        }
        if (mode != RunCoordinator.MODE_AB) return "completed_single_driver_visual_scene";
        if (!visual.optBoolean("comparison_available", false)) {
            return "insufficient_visual_scene_reference";
        }
        if (!visual.optBoolean("passed_correctness_gate", false)) {
            return "failed_visual_scene_checkpoint_mismatch";
        }
        return StatisticalComparison.verdictFor(statistics, 0);
    }

    private static boolean validEvidence(JSONObject evidence) {
        return evidence != null
                && "visible_vulkan_scene_checkpoints".equals(evidence.optString("kind"))
                && evidence.optInt("width", -1) == VisualSceneContract.WIDTH
                && evidence.optInt("height", -1) == VisualSceneContract.HEIGHT
                && evidence.optJSONArray("checkpoints") != null;
    }

    private static JSONObject checkpoint(JSONObject evidence, int frame) {
        if (evidence == null) return null;
        JSONArray checkpoints = evidence.optJSONArray("checkpoints");
        if (checkpoints == null) return null;
        for (int index = 0; index < checkpoints.length(); ++index) {
            JSONObject checkpoint = checkpoints.optJSONObject(index);
            if (checkpoint != null && checkpoint.optInt("frame", -1) == frame) {
                return checkpoint;
            }
        }
        return null;
    }

    private static JSONObject find(JSONArray phases, int round, boolean custom) {
        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            if (phase.optInt("round", -1) != round) continue;
            if (custom == "custom".equals(phase.optString("driver_mode"))) return phase;
        }
        return null;
    }

    private static int[] loadPixels(File suiteDirectory, String relativePath) throws Exception {
        File file = new File(suiteDirectory, relativePath);
        if (!ResultFiles.isInside(suiteDirectory, file) || !file.isFile()) {
            throw new IllegalArgumentException("Checkpoint visual fora da suíte");
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null || bitmap.getWidth() != VisualSceneContract.WIDTH
                || bitmap.getHeight() != VisualSceneContract.HEIGHT) {
            if (bitmap != null) bitmap.recycle();
            throw new IllegalArgumentException("Checkpoint visual inválido");
        }
        int[] pixels = new int[VisualSceneContract.WIDTH * VisualSceneContract.HEIGHT];
        bitmap.getPixels(pixels, 0, VisualSceneContract.WIDTH, 0, 0,
                VisualSceneContract.WIDTH, VisualSceneContract.HEIGHT);
        bitmap.recycle();
        return pixels;
    }

    private static File writeHeatmap(File suiteDirectory, int round, int frame,
                                     int[] reference, int[] candidate,
                                     int tolerance) throws Exception {
        int[] heatmap = new int[reference.length];
        for (int index = 0; index < reference.length; ++index) {
            int delta = maximumChannelDelta(reference[index], candidate[index]);
            if (delta <= tolerance) {
                int value = grayscale(reference[index]);
                heatmap[index] = Color.argb(255, value / 5, value / 5, value / 5);
            } else {
                int intensity = Math.min(255, 80 + delta * 3);
                heatmap[index] = Color.argb(255, intensity, Math.max(0, 120 - delta), 0);
            }
        }
        File output = new File(suiteDirectory,
                String.format(Locale.US, "visual-diff-r%02d-f%04d.png", round, frame));
        Bitmap bitmap = Bitmap.createBitmap(heatmap, VisualSceneContract.WIDTH,
                VisualSceneContract.HEIGHT, Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("Falha ao codificar heatmap visual");
            }
            stream.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        return output;
    }

    private static int maximumChannelDelta(int left, int right) {
        int alpha = Math.abs(((left >>> 24) & 0xff) - ((right >>> 24) & 0xff));
        int red = Math.abs(((left >>> 16) & 0xff) - ((right >>> 16) & 0xff));
        int green = Math.abs(((left >>> 8) & 0xff) - ((right >>> 8) & 0xff));
        int blue = Math.abs((left & 0xff) - (right & 0xff));
        return Math.max(Math.max(alpha, red), Math.max(green, blue));
    }

    private static int grayscale(int argb) {
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        return (red * 54 + green * 183 + blue * 19) >>> 8;
    }

    private static boolean anyNondeterministic(Map<Integer, Set<String>> values) {
        for (Set<String> hashes : values.values()) if (hashes.size() > 1) return true;
        return false;
    }

    private static JSONObject hashesJson(Map<Integer, Set<String>> values) throws Exception {
        JSONObject output = new JSONObject();
        for (Map.Entry<Integer, Set<String>> entry : values.entrySet()) {
            JSONArray hashes = new JSONArray();
            entry.getValue().stream().filter(value -> value.matches("[0-9a-fA-F]{64}"))
                    .sorted().forEach(hashes::put);
            output.put(Integer.toString(entry.getKey()), hashes);
        }
        return output;
    }
}
