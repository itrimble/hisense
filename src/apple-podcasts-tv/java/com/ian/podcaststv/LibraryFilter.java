package com.ian.podcaststv;

import java.text.Normalizer;
import java.util.Locale;

/** All query words must occur in the title or visible metadata. */
final class LibraryFilter {
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
    static boolean matches(String title, String metadata, String query) {
        String haystack = normalize(title + " " + metadata);
        for (String word : normalize(query).trim().split("\\s+"))
            if (!haystack.contains(word)) return false;
        return true;
    }
}
