package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/** Database-only operations. No book/file deletion services or filesystem writers. */
@Component
@RequiredArgsConstructor
public class CatalogueReconciliationStore {
    private final JdbcTemplate jdbc;

    static final Set<String> BOOK_DATA = Set.of("annotations", "book_award", "book_marks", "book_notes",
            "book_notes_v2", "book_shelf_mapping", "ebook_viewer_preference", "reading_sessions",
            "user_book_progress", "pdf_annotations", "cbx_viewer_preference", "epub_viewer_preference",
            "new_pdf_viewer_preference", "pdf_viewer_preference");
    static final Set<String> FILE_DATA = Set.of("user_book_file_progress", "kobo_span_map");
    static final List<String> METADATA = List.of("public_book_review", "comic_metadata_character_mapping",
            "comic_metadata_creator_mapping", "comic_metadata_location_mapping", "comic_metadata_team_mapping",
            "book_metadata_author_mapping", "book_metadata_category_mapping", "book_metadata_mood_mapping",
            "book_metadata_tag_mapping", "comic_metadata", "book_metadata");
    static final Set<String> REVIEW_ONLY = Set.of("metadata_fetch_proposals", "kobo_library_snapshot_book");

    record Table(String name, Set<String> columns, List<String> primaryKey, List<List<String>> uniqueKeys) {}
    record Schema(Map<String, Table> tables, Map<String, String> bookLinks, Map<String, String> fileLinks) {}
    record Change(String table, String link, String destination, Map<String, String> original, boolean remove) {}

    Schema schema() {
        return jdbc.execute((ConnectionCallback<Schema>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            Map<String, Set<String>> columns = new TreeMap<>();
            try (ResultSet rs = meta.getColumns(catalog, null, "%", "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    columns.computeIfAbsent(table, ignored -> new TreeSet<>()).add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            Map<String, String> bookLinks = new TreeMap<>(), fileLinks = new TreeMap<>();
            columns.forEach((table, cols) -> {
                if (cols.contains("book_id")) bookLinks.put(table, "book_id");
                if (cols.contains("book_file_id")) fileLinks.put(table, "book_file_id");
            });
            for (String parent : List.of("book", "book_file", "book_metadata", "comic_metadata")) {
                try (ResultSet rs = meta.getExportedKeys(catalog, null, parent)) {
                    while (rs.next()) {
                        (parent.equals("book_file") ? fileLinks : bookLinks).put(
                                rs.getString("FKTABLE_NAME").toLowerCase(Locale.ROOT), rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            Set<String> needed = new TreeSet<>(bookLinks.keySet());
            needed.addAll(fileLinks.keySet()); needed.add("book"); needed.add("book_file");
            if (columns.containsKey("kobo_reading_state")) needed.add("kobo_reading_state");
            Map<String, Table> tables = new TreeMap<>();
            for (String name : needed) {
                TreeMap<Short, String> primary = new TreeMap<>();
                try (ResultSet rs = meta.getPrimaryKeys(catalog, null, name)) {
                    while (rs.next()) primary.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                Map<String, TreeMap<Short, String>> indexes = new TreeMap<>();
                try (ResultSet rs = meta.getIndexInfo(catalog, null, name, true, false)) {
                    while (rs.next()) {
                        String column = rs.getString("COLUMN_NAME"), index = rs.getString("INDEX_NAME");
                        if (column != null && index != null) indexes.computeIfAbsent(index, ignored -> new TreeMap<>())
                                .put(rs.getShort("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                    }
                }
                tables.put(name, new Table(name, columns.getOrDefault(name, Set.of()), List.copyOf(primary.values()),
                        indexes.values().stream().map(index -> List.copyOf(index.values())).toList()));
            }
            return new Schema(tables, bookLinks, fileLinks);
        });
    }

    Map<String, List<Map<String, String>>> snapshot(Schema schema, List<Long> bookIds, List<Long> fileIds, boolean lock) {
        Map<String, List<Map<String, String>>> result = new TreeMap<>();
        result.put("book", rows("book", "id", bookIds, lock));
        result.put("book_file", rows("book_file", "id", fileIds, lock));
        schema.bookLinks.forEach((table, column) -> {
            if (!table.equals("book_file")) result.put(table, rows(table, column, bookIds, lock));
        });
        schema.fileLinks.forEach((table, column) -> result.put(table, rows(table, column, fileIds, lock)));
        Map<String, List<String>> lookups = Map.of(
                "book_metadata_author_mapping", List.of("author", "author_id"),
                "book_metadata_category_mapping", List.of("category", "category_id"),
                "book_metadata_mood_mapping", List.of("mood", "mood_id"),
                "book_metadata_tag_mapping", List.of("tag", "tag_id"),
                "comic_metadata_character_mapping", List.of("comic_character", "character_id"),
                "comic_metadata_creator_mapping", List.of("comic_creator", "creator_id"),
                "comic_metadata_location_mapping", List.of("comic_location", "location_id"),
                "comic_metadata_team_mapping", List.of("comic_team", "team_id"));
        lookups.forEach((mapping, lookup) -> {
            List<Long> keys = result.getOrDefault(mapping, List.of()).stream().map(row -> row.get(lookup.get(1)))
                    .filter(Objects::nonNull).map(Long::valueOf).distinct().toList();
            if (!keys.isEmpty()) result.put(lookup.get(0), rows(lookup.get(0), "id", keys, lock));
        });
        return result;
    }

    List<Map<String, String>> rows(String table, String column, Collection<Long> ids, boolean lock) {
        if (ids.isEmpty()) return List.of();
        String sql = "SELECT * FROM " + identifier(table) + " WHERE " + identifier(column) + " IN (" + placeholders(ids.size()) + ")"
                + (lock ? " FOR UPDATE" : "");
        List<Map<String, String>> result = jdbc.query(sql, (rs, index) -> {
            Map<String, String> row = new TreeMap<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                row.put(rs.getMetaData().getColumnLabel(c).toLowerCase(Locale.ROOT), rs.getString(c));
            }
            return row;
        }, ids.toArray());
        if (result.size() > 5000) throw ApiError.CONFLICT.createException("Too much related data for one catalogue merge. Review this group separately.");
        result.sort(Comparator.comparing(Map::toString));
        return result;
    }

    List<Change> plan(Schema schema, Map<String, List<Map<String, String>>> snapshot,
                      long targetBook, Map<Long, Long> fileTargets, List<String> reasons) {
        List<Change> changes = new ArrayList<>();
        for (String table : snapshot.keySet()) {
            boolean bookData = BOOK_DATA.contains(table), fileData = FILE_DATA.contains(table);
            if (!bookData && !fileData) continue;
            String column = bookData ? schema.bookLinks.get(table) : schema.fileLinks.get(table);
            Table definition = schema.tables.get(table);
            if (definition.primaryKey.isEmpty() && !snapshot.get(table).isEmpty()) {
                reasons.add("Some related data cannot be addressed safely and requires manual review."); continue;
            }
            List<Map<String, String>> projected = new ArrayList<>();
            // Put the surviving rows first so duplicate shelf memberships are removed from source records only.
            List<Map<String, String>> ordered = snapshot.get(table).stream()
                    .sorted(Comparator.comparing((Map<String, String> row) -> {
                        String destination = Long.toString(bookData ? targetBook : fileTargets.get(Long.valueOf(row.get(column))));
                        return !destination.equals(row.get(column));
                    })).toList();
            for (Map<String, String> row : ordered) {
                String destination = Long.toString(bookData ? targetBook : fileTargets.get(Long.valueOf(row.get(column))));
                Map<String, String> moved = new TreeMap<>(row); moved.put(column, destination);
                Map<String, String> collision = projected.stream().filter(other -> definition.uniqueKeys.stream()
                        .anyMatch(key -> key.contains(column) && key.stream().allMatch(k -> moved.get(k) != null
                                && Objects.equals(moved.get(k), other.get(k))))).findFirst().orElse(null);
                boolean source = !destination.equals(row.get(column));
                if (collision != null) {
                    if (source && table.equals("book_shelf_mapping") && moved.equals(collision)) {
                        changes.add(new Change(table, column, destination, row, true));
                    } else reasons.add("Conflicting reading data or preferences for the same user require manual review.");
                } else {
                    projected.add(moved);
                    if (source) changes.add(new Change(table, column, destination, row, false));
                }
            }
        }
        return changes;
    }

    void apply(Schema schema, List<Change> changes) {
        for (Change change : changes) {
            Table table = schema.tables.get(change.table);
            List<Object> args = new ArrayList<>();
            String sql;
            if (change.remove) sql = "DELETE FROM " + identifier(change.table);
            else {sql = "UPDATE " + identifier(change.table) + " SET " + identifier(change.link) + "=?"; args.add(change.destination);}
            sql += " WHERE " + String.join(" AND ", table.primaryKey.stream().map(key -> identifier(key) + "=?").toList());
            table.primaryKey.forEach(key -> args.add(change.original.get(key)));
            if (jdbc.update(sql, args.toArray()) != 1) throw ApiError.CONFLICT.createException("Related data changed. Inspect the group again.");
        }
    }

    void removeCatalogueRecords(Schema schema, List<Long> bookIds, List<Long> fileIds) {
        // Metadata and its mappings are retained in the immutable receipt before removal.
        for (String table : METADATA) if (schema.tables.containsKey(table)) {
            jdbc.update("DELETE FROM " + identifier(table) + " WHERE book_id IN (" + placeholders(bookIds.size()) + ")", bookIds.toArray());
        }
        if (!fileIds.isEmpty() && jdbc.update("DELETE FROM book_file WHERE id IN (" + placeholders(fileIds.size()) + ")", fileIds.toArray()) != fileIds.size())
            throw ApiError.CONFLICT.createException("Book file records changed. Inspect the group again.");
        if (jdbc.update("DELETE FROM book WHERE id IN (" + placeholders(bookIds.size()) + ")", bookIds.toArray()) != bookIds.size())
            throw ApiError.CONFLICT.createException("Book records changed. Inspect the group again.");
    }

    static String placeholders(int size) {return String.join(",", Collections.nCopies(size, "?"));}
    static String identifier(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid catalogue identifier");
        return "`" + name + "`";
    }
}
