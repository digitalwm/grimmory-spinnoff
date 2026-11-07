package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.model.dto.BookLoreUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.MetadataIdentityMatcher;
import org.booklore.service.file.FileFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class CatalogueReconciliationService {
    private static final int MAX_LIBRARY_FILES = 200_000;
    private static final long MAX_HASH_BYTES = 512L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final CatalogueReconciliationStore store;
    private final ObjectMapper mapper;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public record Member(long bookId, long fileId, String title, List<String> authors, String storedHash) {}
    public record Group(String key, String path, long suggestedTargetBookId, List<Member> members) {}
    public record Scan(long libraryId, int page, int size, int totalGroups, int extraRecords, List<Group> groups) {}
    public record Preview(long libraryId, long targetBookId, List<Member> members, String path,
                          String fileSha256, long fileBytes, boolean eligible, List<String> reasons,
                          Map<String, Integer> relatedRecords, Map<String, Map<Long, String>> metadataDifferences,
                          String token) {}
    public record Receipt(String id, long targetBookId, List<Long> removedBookIds, String fileSha256, Instant completedAt) {}
    public record ReceiptSummary(String id, long targetBookId, String completedAt) {}
    private record FileRow(long bookId, long fileId, long pathId, String root, String subPath, String name,
                           String title, String storedHash, double score, boolean physical, boolean deleted) {
        String key() {return pathId + "\n" + subPath + "\n" + name;}
        Path path() {return Path.of(root, subPath, name).toAbsolutePath().normalize();}
    }
    private record FileCheck(String path, String sha256, String storedFingerprint, long bytes) {}
    private record Inspection(Preview preview, CatalogueReconciliationStore.Schema schema,
                              Map<String, List<Map<String, String>>> before, List<CatalogueReconciliationStore.Change> changes,
                              List<FileRow> files) {}

    @Transactional(readOnly = true)
    public Scan scan(long libraryId, int page, int size) {
        requireAdmin();
        if (page < 0 || size < 1 || size > 50) throw ApiError.GENERIC_BAD_REQUEST.createException("Choose a page size between 1 and 50.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM library WHERE id=?", Long.class, libraryId) != 1)
            throw ApiError.LIBRARY_NOT_FOUND.createException(libraryId);
        Map<String, List<FileRow>> grouped = new TreeMap<>();
        for (FileRow file : libraryFiles(libraryId)) grouped.computeIfAbsent(file.key(), ignored -> new ArrayList<>()).add(file);
        List<List<FileRow>> duplicates = grouped.values().stream()
                .filter(rows -> rows.stream().map(FileRow::bookId).distinct().count() > 1).toList();
        int first = (int) Math.min((long) page * size, duplicates.size());
        List<Group> groups = duplicates.subList(first, Math.min(first + size, duplicates.size())).stream()
                .map(rows -> new Group(sha(rows.getFirst().key()), rows.getFirst().path().toString(), suggested(rows),
                        groupMembers(rows))).toList();
        return new Scan(libraryId, page, size, duplicates.size(), duplicates.stream()
                .mapToInt(rows -> (int) rows.stream().map(FileRow::bookId).distinct().count() - 1).sum(), groups);
    }

    @Transactional(readOnly = true)
    public Preview preview(long libraryId, List<Long> bookIds, long targetBookId) {
        requireAdmin();
        return inspect(libraryId, bookIds, targetBookId, false).preview;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt merge(long libraryId, List<Long> bookIds, long targetBookId, String previewToken, boolean confirmed) {
        BookLoreUser actor = requireAdmin();
        if (!confirmed || previewToken == null || previewToken.isBlank())
            throw ApiError.GENERIC_BAD_REQUEST.createException("Inspect and explicitly confirm this group before merging.");
        List<Long> ids = validateIds(bookIds, targetBookId);
        // Lock parents before any child snapshots. FK writers must finish before the merge can continue.
        if (store.rows("book", "id", ids, true).size() != ids.size())
            throw ApiError.CONFLICT.createException("Book records changed. Scan again.");
        Inspection inspection = inspect(libraryId, ids, targetBookId, true);
        Preview current = inspection.preview;
        if (!current.eligible || !MessageDigest.isEqual(current.token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                previewToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw ApiError.CONFLICT.createException("The group or file changed, or needs review. Inspect it again before merging.");
        List<Long> removedBooks = ids.stream().filter(id -> id != targetBookId).toList();
        List<Long> removedFiles = inspection.files.stream().filter(file -> file.bookId != targetBookId).map(FileRow::fileId).toList();
        store.apply(inspection.schema, inspection.changes);
        for (String table : CatalogueReconciliationStore.BOOK_DATA) {
            if (inspection.schema.bookLinks().containsKey(table)
                    && !store.rows(table, inspection.schema.bookLinks().get(table), removedBooks, true).isEmpty())
                throw ApiError.CONFLICT.createException("Related user data changed. No merge was applied.");
        }
        for (String table : CatalogueReconciliationStore.FILE_DATA) {
            if (inspection.schema.fileLinks().containsKey(table)
                    && !store.rows(table, inspection.schema.fileLinks().get(table), removedFiles, true).isEmpty())
                throw ApiError.CONFLICT.createException("File reading data changed. No merge was applied.");
        }
        store.removeCatalogueRecords(inspection.schema, removedBooks, removedFiles);
        FileRow survivingFile = inspection.files.stream().filter(file -> file.bookId == targetBookId).findFirst().orElseThrow();
        FileCheck afterFile = checkFile(survivingFile);
        if (!afterFile.sha256.equals(current.fileSha256))
            throw ApiError.CONFLICT.createException("The physical file changed during the operation. No catalogue merge was applied.");
        Map<String, List<Map<String, String>>> after = store.snapshot(inspection.schema, List.of(targetBookId), List.of(survivingFile.fileId), true);
        String receiptId = UUID.randomUUID().toString();
        Instant completed = Instant.now();
        jdbc.update("""
                INSERT INTO catalogue_reconciliation_audit
                (id,user_id,library_id,target_book_id,created_at,file_path,file_sha256,before_json,after_json)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, receiptId, actor.getId(), libraryId, targetBookId,
                java.sql.Timestamp.from(completed), current.path, current.fileSha256, json(inspection.before), json(after));
        auditService.log(AuditAction.DUPLICATE_BOOKS_MERGED, "Book", targetBookId,
                "Catalogue-only merge of " + removedBooks + "; physical file preserved; receipt " + receiptId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try {notificationService.sendMessageToUser(actor.getUsername(), Topic.BOOKS_REMOVE, removedBooks);}
                    catch (Exception e) {log.warn("Catalogue merge committed, but client notification failed; receipt {}", receiptId, e);}
                }
            });
        }
        return new Receipt(receiptId, targetBookId, removedBooks, current.fileSha256, completed);
    }

    @Transactional(readOnly = true)
    public List<ReceiptSummary> receipts(long libraryId) {
        requireAdmin();
        return jdbc.query("SELECT id,target_book_id,created_at FROM catalogue_reconciliation_audit WHERE library_id=? ORDER BY created_at DESC,id DESC LIMIT 20",
                (rs, index) -> new ReceiptSummary(rs.getString(1), rs.getLong(2), rs.getString(3)), libraryId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> receipt(String id) {
        requireAdmin();
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM catalogue_reconciliation_audit WHERE id=?", (rs, index) -> {
            Map<String, Object> row = new TreeMap<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) row.put(rs.getMetaData().getColumnLabel(c).toLowerCase(Locale.ROOT), rs.getString(c));
            return row;
        }, id);
        if (rows.isEmpty()) throw ApiError.RESOURCE_NOT_FOUND.createException("Catalogue receipt", id);
        return rows.getFirst();
    }

    private Inspection inspect(long libraryId, List<Long> requestedIds, long targetBookId, boolean lock) {
        List<Long> ids = validateIds(requestedIds, targetBookId);
        List<FileRow> all = libraryFiles(libraryId);
        List<FileRow> files = all.stream().filter(file -> ids.contains(file.bookId)).toList();
        if (files.size() != ids.size() || files.stream().map(FileRow::bookId).distinct().count() != ids.size()
                || files.stream().map(FileRow::key).distinct().count() != 1)
            throw ApiError.CONFLICT.createException("Only complete groups with one shared file per book can be reconciled.");
        if (!new HashSet<>(all.stream().filter(file -> file.key().equals(files.getFirst().key())).map(FileRow::bookId).toList())
                .equals(new HashSet<>(ids))) throw ApiError.CONFLICT.createException("Group membership changed. Scan again.");
        CatalogueReconciliationStore.Schema schema = store.schema();
        Map<String, List<Map<String, String>>> before = store.snapshot(schema, ids, files.stream().map(FileRow::fileId).toList(), lock);
        List<String> reasons = new ArrayList<>();
        if (schema.tables().containsKey("kobo_reading_state") && jdbc.queryForObject("SELECT COUNT(*) FROM kobo_reading_state", Long.class) > 0)
            reasons.add("Device reading state exists. Device identity reconciliation requires separate review.");
        if (files.stream().anyMatch(file -> file.physical || file.deleted)) reasons.add("Physical or deleted book records require separate review.");
        // Include alternate files even if they were registered under a different library path.
        if (store.rows("book_file", "book_id", ids, lock).size() != ids.size()) reasons.add("A book has additional files; review its formats separately.");
        if (files.stream().anyMatch(file -> file.storedHash == null || file.storedHash.isBlank())) reasons.add("One or more stored file hashes are missing.");
        if (files.stream().map(FileRow::storedHash).distinct().count() != 1) reasons.add("Stored file hashes disagree.");
        Set<String> allowed = new HashSet<>(CatalogueReconciliationStore.BOOK_DATA);
        allowed.addAll(CatalogueReconciliationStore.FILE_DATA); allowed.addAll(CatalogueReconciliationStore.METADATA);
        allowed.addAll(CatalogueReconciliationStore.REVIEW_ONLY); allowed.add("book_file");
        schema.bookLinks().keySet().stream().filter(table -> !allowed.contains(table)).forEach(table -> reasons.add("Unsupported related catalogue data: " + table));
        schema.fileLinks().keySet().stream().filter(table -> !allowed.contains(table)).forEach(table -> reasons.add("Unsupported related file data: " + table));
        for (String table : CatalogueReconciliationStore.REVIEW_ONLY) {
            if (before.getOrDefault(table, List.of()).stream().anyMatch(row -> !Long.toString(targetBookId).equals(row.get("book_id"))))
                reasons.add("Pending metadata proposals or device snapshots require separate review.");
        }
        Map<Long, List<String>> authors = new TreeMap<>();
        jdbc.query("SELECT mapping.book_id,a.name FROM book_metadata_author_mapping mapping JOIN author a ON a.id=mapping.author_id WHERE mapping.book_id IN ("
                + CatalogueReconciliationStore.placeholders(ids.size()) + ") ORDER BY a.name", rs -> {
            authors.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        }, ids.toArray());
        FileRow target = files.stream().filter(file -> file.bookId == targetBookId).findFirst().orElseThrow();
        BookMetadata targetIdentity = BookMetadata.builder().title(target.title).authors(authors.getOrDefault(targetBookId, List.of())).build();
        if (authors.values().stream().anyMatch(List::isEmpty) || authors.size() != ids.size()) reasons.add("Author identity is incomplete.");
        for (FileRow file : files) {
            BookMetadata identity = BookMetadata.builder().title(file.title).authors(authors.getOrDefault(file.bookId, List.of())).build();
            if (!MetadataIdentityMatcher.matches(targetIdentity, identity) || !MetadataIdentityMatcher.matches(identity, targetIdentity)) {
                reasons.add("Titles or authors disagree. Resolve the metadata before merging."); break;
            }
        }
        FileCheck checked;
        try {checked = checkFile(target);} catch (RuntimeException e) {
            reasons.add(e.getMessage()); checked = new FileCheck(target.path().toString(), null, null, 0);
        }
        String actualFingerprint = checked.storedFingerprint;
        if (actualFingerprint != null && files.stream().anyMatch(file -> file.storedHash == null
                || !actualFingerprint.equalsIgnoreCase(file.storedHash)))
            reasons.add("The stored fingerprint no longer matches the physical file. Rescan and review it before merging.");
        List<CatalogueReconciliationStore.Change> changes = store.plan(schema, before, targetBookId, target.fileId, reasons);
        String snapshot = json(before);
        if (snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000) reasons.add("This group has too much metadata or user data for one merge.");
        Map<String, Integer> related = new TreeMap<>();
        for (String table : before.keySet()) if (CatalogueReconciliationStore.BOOK_DATA.contains(table) || CatalogueReconciliationStore.FILE_DATA.contains(table)
                || CatalogueReconciliationStore.REVIEW_ONLY.contains(table)) related.put(table, before.get(table).size());
        Map<String, Map<Long, String>> differences = differences(before.getOrDefault("book_metadata", List.of()));
        String token = sha(libraryId + "\n" + targetBookId + "\n" + checked.path + "\n" + checked.sha256 + "\n" + snapshot);
        Preview preview = new Preview(libraryId, targetBookId, files.stream().map(file -> member(file, authors.getOrDefault(file.bookId, List.of()))).toList(),
                checked.path, checked.sha256, checked.bytes, reasons.isEmpty(), reasons.stream().distinct().toList(), related, differences, token);
        return new Inspection(preview, schema, before, changes, files);
    }

    private List<FileRow> libraryFiles(long libraryId) {
        List<FileRow> rows = jdbc.query("""
                SELECT b.id,f.id,b.library_path_id,p.path,f.file_sub_path,f.file_name,m.title,f.current_hash,
                       b.metadata_match_score,b.is_physical,b.deleted
                FROM book_file f JOIN book b ON b.id=f.book_id JOIN library_path p ON p.id=b.library_path_id
                LEFT JOIN book_metadata m ON m.book_id=b.id WHERE b.library_id=?
                ORDER BY b.id,f.id LIMIT ?
                """, (rs, index) -> new FileRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getDouble(9), rs.getBoolean(10), rs.getBoolean(11)), libraryId, MAX_LIBRARY_FILES + 1);
        if (rows.size() > MAX_LIBRARY_FILES) throw ApiError.CONFLICT.createException("This library is too large for an interactive scan. A partitioned audit is required.");
        return rows;
    }

    private FileCheck checkFile(FileRow file) {
        try {
            Path root = Path.of(file.root).toRealPath(), path = file.path().toRealPath();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new IllegalStateException("The shared path is outside the library or is not a regular file.");
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
            if (before.size() > MAX_HASH_BYTES) throw new IllegalStateException("Files larger than 512 MiB require a separate content audit.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int n;
                long read = 0;
                while ((n = input.read(buffer)) != -1) {
                    read += n;
                    if (read > MAX_HASH_BYTES) throw new IllegalStateException("The file grew beyond the interactive inspection limit.");
                    digest.update(buffer, 0, n);
                }
            }
            String fingerprint = FileFingerprint.generateHash(path);
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
                throw new IllegalStateException("The shared file changed while it was being inspected. Retry after the file is stable.");
            return new FileCheck(path.toString(), HexFormat.of().formatHex(digest.digest()), fingerprint, before.size());
        } catch (IllegalStateException e) {throw e;} catch (Exception e) {throw new IllegalStateException("The shared file is missing or unreadable; a catalogue merge is blocked.", e);}
    }

    private Map<String, Map<Long, String>> differences(List<Map<String, String>> metadata) {
        Map<String, Map<Long, String>> result = new TreeMap<>();
        for (Map<String, String> row : metadata) for (String column : row.keySet()) if (!column.equals("book_id")) {
            result.computeIfAbsent(column, ignored -> new TreeMap<>()).put(Long.parseLong(row.get("book_id")), row.get(column));
        }
        result.entrySet().removeIf(entry -> new HashSet<>(entry.getValue().values()).size() < 2);
        return result;
    }
    private Member member(FileRow row, List<String> authors) {return new Member(row.bookId, row.fileId, row.title, authors, row.storedHash);}
    private List<Member> groupMembers(List<FileRow> rows) {
        Map<Long, Member> members = new TreeMap<>();
        rows.forEach(row -> members.putIfAbsent(row.bookId, member(row, List.of())));
        return List.copyOf(members.values());
    }
    private long suggested(List<FileRow> rows) {return rows.stream().max(Comparator.comparingDouble(FileRow::score)
            .thenComparing(FileRow::bookId, Comparator.reverseOrder())).orElseThrow().bookId;}
    private List<Long> validateIds(List<Long> ids, long target) {
        if (ids == null || ids.size() < 2 || ids.size() > 20 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()
                || !ids.contains(target)) throw ApiError.GENERIC_BAD_REQUEST.createException("Choose a complete group of 2–20 records and one surviving record.");
        return ids.stream().sorted().toList();
    }
    private BookLoreUser requireAdmin() {
        // The normal authentication helper initializes settings, which would write during a read-only scan.
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        BookLoreUser user = principal instanceof BookLoreUser current ? current
                : principal instanceof OpdsUserDetails opds ? opds.getUser() : null;
        if (user == null || user.getPermissions() == null || !user.getPermissions().isAdmin()) throw ApiError.FORBIDDEN.createException("Catalogue reconciliation requires an administrator.");
        return user;
    }
    private String json(Object value) {try {return mapper.writeValueAsString(value);} catch (Exception e) {throw new IllegalStateException("Could not preserve catalogue evidence", e);}}
    private static String sha(String value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));} catch (Exception e) {throw new IllegalStateException(e);}}
}
