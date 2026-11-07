package org.booklore.util;

import java.util.Locale;

/** Normalize formatting before storage; an ISBN is accepted only with a valid check digit. */
public final class IsbnUtils {
    private IsbnUtils() {}
    private static String compact(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT)
                .replaceFirst("^(?:URN:ISBN:|ISBN(?:-1[03])?:?\\s*)", "")
                .replaceAll("[\\s\\p{Pd}]", "");
    }
    public static String normalize13(String value) {
        String isbn = compact(value);
        if (!isbn.matches("97[89][0-9]{10}")) return null;
        int sum = 0;
        for (int i = 0; i < 13; i++) sum += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return sum % 10 == 0 ? isbn : null;
    }
    public static String normalize10(String value) {
        String isbn = compact(value);
        if (!isbn.matches("[0-9]{9}[0-9X]")) return null;
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (isbn.charAt(i) == 'X' ? 10 : isbn.charAt(i) - '0') * (10 - i);
        return sum % 11 == 0 ? isbn : null;
    }
    public static String canonical13(String isbn13, String isbn10) {
        String full = normalize13(isbn13);
        if (full != null) return full;
        String shortIsbn = normalize10(isbn10);
        return shortIsbn == null ? null : BookUtils.isbn10To13(shortIsbn);
    }
}
