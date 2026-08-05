package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RenderComparator {
    static final class DivergentBlock {
        final int x;
        final int y;
        final int width;
        final int height;
        final int mismatchedPixels;
        final int maxChannelDelta;
        final double pixelMatchPercent;

        DivergentBlock(int x, int y, int width, int height, int mismatchedPixels,
                       int maxChannelDelta, double pixelMatchPercent) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.mismatchedPixels = mismatchedPixels;
            this.maxChannelDelta = maxChannelDelta;
            this.pixelMatchPercent = pixelMatchPercent;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("x", x);
            json.put("y", y);
            json.put("width", width);
            json.put("height", height);
            json.put("mismatched_pixels", mismatchedPixels);
            json.put("max_channel_delta", maxChannelDelta);
            json.put("pixel_match_percent", pixelMatchPercent);
            return json;
        }
    }

    static final class Result {
        final int width;
        final int height;
        final int pixelTolerance;
        final int blockSize;
        final double minimumBlockMatchPercent;
        final long matchingPixels;
        final long totalPixels;
        final int maxChannelDelta;
        final List<DivergentBlock> divergentBlocks;

        Result(int width, int height, int pixelTolerance, int blockSize,
               double minimumBlockMatchPercent, long matchingPixels, long totalPixels,
               int maxChannelDelta, List<DivergentBlock> divergentBlocks) {
            this.width = width;
            this.height = height;
            this.pixelTolerance = pixelTolerance;
            this.blockSize = blockSize;
            this.minimumBlockMatchPercent = minimumBlockMatchPercent;
            this.matchingPixels = matchingPixels;
            this.totalPixels = totalPixels;
            this.maxChannelDelta = maxChannelDelta;
            this.divergentBlocks = Collections.unmodifiableList(new ArrayList<>(divergentBlocks));
        }

        double pixelMatchPercent() {
            return totalPixels == 0 ? 0.0 : matchingPixels * 100.0 / totalPixels;
        }

        boolean passes(int maximumDivergentBlocks) {
            return divergentBlocks.size() <= maximumDivergentBlocks;
        }

        JSONObject toJson(int maximumDivergentBlocks) throws Exception {
            JSONObject json = new JSONObject();
            json.put("image_width", width);
            json.put("image_height", height);
            json.put("pixel_tolerance", pixelTolerance);
            json.put("block_size_px", blockSize);
            json.put("minimum_block_match_percent", minimumBlockMatchPercent);
            json.put("maximum_divergent_blocks", maximumDivergentBlocks);
            json.put("matching_pixels", matchingPixels);
            json.put("total_pixels", totalPixels);
            json.put("pixel_match_percent", pixelMatchPercent());
            json.put("max_channel_delta", maxChannelDelta);
            json.put("divergent_block_count", divergentBlocks.size());
            JSONArray blocks = new JSONArray();
            for (DivergentBlock block : divergentBlocks) blocks.put(block.toJson());
            json.put("divergent_blocks", blocks);
            json.put("passed", passes(maximumDivergentBlocks));
            json.put("metric_note", WorkloadContract.RENDER_CORRECTNESS_LIMITATION);
            return json;
        }
    }

    private RenderComparator() {}

    static Result compare(int[] referenceArgb, int[] candidateArgb, int width, int height,
                          int pixelTolerance, int blockSize,
                          double minimumBlockMatchPercent) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Dimensões inválidas");
        int expectedPixels = Math.multiplyExact(width, height);
        if (referenceArgb.length != expectedPixels || candidateArgb.length != expectedPixels) {
            throw new IllegalArgumentException("A evidência não corresponde às dimensões declaradas");
        }
        if (pixelTolerance < 0 || pixelTolerance > 255) {
            throw new IllegalArgumentException("Tolerância de pixel deve ficar entre 0 e 255");
        }
        if (blockSize <= 0) throw new IllegalArgumentException("Tamanho de bloco inválido");
        if (minimumBlockMatchPercent < 0.0 || minimumBlockMatchPercent > 100.0) {
            throw new IllegalArgumentException("Percentual mínimo de bloco inválido");
        }

        long matchingPixels = 0;
        int globalMaximumDelta = 0;
        List<DivergentBlock> divergentBlocks = new ArrayList<>();
        for (int blockY = 0; blockY < height; blockY += blockSize) {
            for (int blockX = 0; blockX < width; blockX += blockSize) {
                int blockWidth = Math.min(blockSize, width - blockX);
                int blockHeight = Math.min(blockSize, height - blockY);
                int blockPixels = blockWidth * blockHeight;
                int blockMatches = 0;
                int blockMaximumDelta = 0;
                for (int y = blockY; y < blockY + blockHeight; ++y) {
                    int rowOffset = y * width;
                    for (int x = blockX; x < blockX + blockWidth; ++x) {
                        int reference = referenceArgb[rowOffset + x];
                        int candidate = candidateArgb[rowOffset + x];
                        int maximumDelta = maximumChannelDelta(reference, candidate);
                        blockMaximumDelta = Math.max(blockMaximumDelta, maximumDelta);
                        globalMaximumDelta = Math.max(globalMaximumDelta, maximumDelta);
                        if (maximumDelta <= pixelTolerance) {
                            ++blockMatches;
                            ++matchingPixels;
                        }
                    }
                }
                double blockMatchPercent = blockMatches * 100.0 / blockPixels;
                if (blockMatchPercent + 1.0e-9 < minimumBlockMatchPercent) {
                    divergentBlocks.add(new DivergentBlock(
                            blockX, blockY, blockWidth, blockHeight,
                            blockPixels - blockMatches, blockMaximumDelta, blockMatchPercent));
                }
            }
        }
        return new Result(width, height, pixelTolerance, blockSize,
                minimumBlockMatchPercent, matchingPixels, expectedPixels,
                globalMaximumDelta, divergentBlocks);
    }

    private static int maximumChannelDelta(int left, int right) {
        int alpha = Math.abs(((left >>> 24) & 0xff) - ((right >>> 24) & 0xff));
        int red = Math.abs(((left >>> 16) & 0xff) - ((right >>> 16) & 0xff));
        int green = Math.abs(((left >>> 8) & 0xff) - ((right >>> 8) & 0xff));
        int blue = Math.abs((left & 0xff) - (right & 0xff));
        return Math.max(Math.max(alpha, red), Math.max(green, blue));
    }
}
