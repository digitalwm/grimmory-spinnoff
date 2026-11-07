package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Conservative identity check for automatic enrichment, not a completeness score. */
public final class MetadataIdentityMatcher {
    private MetadataIdentityMatcher() {}

    public static boolean matches(BookMetadata source, BookMetadata candidate) {
        if (source == null || candidate == null) return false;
        if (hasText(source.getTitle())) {
            if (!hasText(candidate.getTitle()) || !titleMatches(source.getTitle(), candidate.getTitle())) return false;
        } else if (!sharedIdentifier(source, candidate)) {
            return false;
        }
        List<String> authors = authors(source);
        return authors.isEmpty() || authors.stream().anyMatch(original ->
                authors(candidate).stream().anyMatch(fetched -> authorMatches(original, fetched)));
    }

    private static boolean titleMatches(String first, String second) {
        String a = withoutSeries(first), b = withoutSeries(second);
        String fullA = normalise(a), fullB = normalise(b);
        if (fullA.isEmpty() || fullB.isEmpty()) return false;
        if (fullA.equals(fullB)) return true;
        // Different explicit subtitles can identify different volumes or works.
        if (a.contains(":") == b.contains(":")) return false;
        return normalise(a.split(":", 2)[0]).equals(normalise(b.split(":", 2)[0]));
    }

    private static String withoutSeries(String title) {
        // Permit explicit subtitles and numbered series suffixes, never a series-name match.
        return title.replaceFirst("\\s*\\([^()]*#\\s*\\d+(?:\\.\\d+)?\\s*\\)\\s*$", "");
    }

    private static boolean authorMatches(String first, String second) {
        String a = normalise(first), b = normalise(second);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String[] left = a.split(" "), right = b.split(" ");
        if (left.length < 2 || left.length != right.length || !left[left.length - 1].equals(right[right.length - 1])) return false;
        for (int i = 0; i < left.length - 1; i++) {
            if (!left[i].equals(right[i]) && !(left[i].charAt(0) == right[i].charAt(0)
                    && (left[i].length() == 1 || right[i].length() == 1))) return false;
        }
        return true;
    }

    private static List<String> authors(BookMetadata metadata) {
        return metadata.getAuthors() == null ? List.of()
                : metadata.getAuthors().stream().filter(MetadataIdentityMatcher::hasText).toList();
    }

    private static boolean sharedIdentifier(BookMetadata a, BookMetadata b) {
        return same(a.getIsbn13(), b.getIsbn13()) || same(a.getIsbn10(), b.getIsbn10())
                || same(a.getAsin(), b.getAsin()) || same(a.getGoodreadsId(), b.getGoodreadsId())
                || same(a.getGoogleId(), b.getGoogleId()) || same(a.getOpenlibraryId(), b.getOpenlibraryId())
                || same(a.getHardcoverBookId(), b.getHardcoverBookId());
    }

    private static boolean same(String a, String b) {
        return hasText(a) && hasText(b) && a.strip().equalsIgnoreCase(b.strip());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalise(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
