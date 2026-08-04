package com.amaral.driverlab;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility bridge for the programmatic Java UI inherited from phases 1-11.
 * New Phase 12 UI uses string resources directly. The bridge lets every existing Activity
 * honor the selected locale while the literal-by-literal resource migration remains auditable.
 */
final class LegacyUiTranslations {
    private static final Map<String, TranslationTable> CACHE = new ConcurrentHashMap<>();

    private LegacyUiTranslations() {}

    static String translate(Context context, String input) {
        if (input == null || input.isEmpty()) return input;
        TranslationTable table = CACHE.computeIfAbsent(
                LanguageManager.effectiveLanguageTag(context), ignored -> build(context));
        String direct = table.exact.get(input);
        if (direct != null) return direct;

        // Dynamic status messages are assembled from stable prefixes/suffixes. Translate the
        // longest matching fragment while preserving hashes, IDs, paths, metrics and values.
        String output = input;
        for (Fragment fragment : table.fragments) {
            if (output.contains(fragment.source)) {
                output = output.replace(fragment.source, fragment.translation);
            }
        }
        return output;
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static TranslationTable build(Context context) {
        String[] source = context.getResources().getStringArray(R.array.legacy_ui_source_pt_br);
        String[] translated = context.getResources().getStringArray(R.array.legacy_ui_translation);
        int count = Math.min(source.length, translated.length);
        Map<String, String> exact = new LinkedHashMap<>();
        List<Fragment> fragments = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String from = source[index];
            String to = translated[index];
            exact.put(from, to);
            if (!from.isEmpty() && !from.equals(to)
                    && (from.endsWith(" ") || from.startsWith("\n") || from.endsWith(":"))) {
                fragments.add(new Fragment(from, to));
            }
        }
        fragments.sort((left, right) -> Integer.compare(
                right.source.length(), left.source.length()));
        return new TranslationTable(exact, fragments);
    }

    private static final class TranslationTable {
        final Map<String, String> exact;
        final List<Fragment> fragments;

        TranslationTable(Map<String, String> exact, List<Fragment> fragments) {
            this.exact = exact;
            this.fragments = fragments;
        }
    }

    private static final class Fragment {
        final String source;
        final String translation;

        Fragment(String source, String translation) {
            this.source = source;
            this.translation = translation;
        }
    }
}
