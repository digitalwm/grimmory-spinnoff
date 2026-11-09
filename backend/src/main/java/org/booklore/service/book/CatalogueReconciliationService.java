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
    private final CatalogueQuarantineService quarantine;
    private final org.booklore.service.monitoring.MonitoringRegistrationService monitoring;

    public record Member(long bookId, long fileId, String title, List<String> authors, String storedHash) {}
    public record Group(String key, String path, long suggestedTargetBookId, List<Member> members) {}
    public record Scan(long libraryId, int page, int size, int totalGroups, int extraRecords, List<Group> groups, int offset) {}
    public record Preview(long libraryId, long targetBookId, List<Member> members, String path,
                          String fileSha256, long fileBytes, boolean eligible, List<String> reasons,
                          Map<String, Integer> relatedRecords, Map<String, Map<Long, String>> metadataDifferences,
                          String token, List<FilePlan> files, int quarantineRetentionDays, boolean identityConfirmed) {}
    public record FilePlan(long fileId, long bookId, String path, String bookType, String sha256, long bytes,
                           String action, long retainedFileId, String retainedPath) {}
    public record Receipt(String id, long targetBookId, List<Long> removedBookIds, String fileSha256, Instant completedAt,
                          List<FilePlan> files, int quarantineRetentionDays) {}
    public record QuickMergeResult(String status, Receipt receipt, Preview preview, List<String> reviewReasons) {}
    public record ReceiptSummary(String id, long targetBookId, String completedAt) {}
    private record FileRow(long bookId, long fileId, long pathId, String root, String subPath, String name,
                           String title, String storedHash, double score, boolean physical, boolean deleted,
                           String bookType, boolean bookFormat, boolean folderBased) {
        String key() {return pathId + "\n" + subPath + "\n" + name;}
        Path path() {return Path.of(root, subPath, name).toAbsolutePath().normalize();}
    }
    private record FileCheck(String path, String sha256, String storedFingerprint, long bytes) {}
    private record Inspection(Preview preview, CatalogueReconciliationStore.Schema schema,
                              Map<String, List<Map<String, String>>> before, List<CatalogueReconciliationStore.Change> changes,
                              List<FileRow> files, Map<Long, Long> fileTargets, Map<Long, String> fingerprintRepairs) {}

    @Transactional(readOnly = true)
    public Scan scan(long libraryId, int page, int size) {return scan(libraryId, page, size, null);}

    @Transactional(readOnly = true)
    public Scan scan(long libraryId, int page, int size, String search) {return scan(libraryId,page,size,search,null);}

    @Transactional(readOnly = true)
    public Scan scan(long libraryId, int page, int size, String search, Integer offset) {
        requireAdmin();
        if (search != null && search.length() > 200) throw ApiError.GENERIC_BAD_REQUEST.createException("Search must be at most 200 characters.");
        if (offset != null && (offset < 0 || offset > MAX_LIBRARY_FILES)) throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid scan offset.");
        if (page < 0 || size < 1 || size > 50) throw ApiError.GENERIC_BAD_REQUEST.createException("Choose a page size between 1 and 50.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM library WHERE id=?", Long.class, libraryId) != 1)
            throw ApiError.LIBRARY_NOT_FOUND.createException(libraryId);
        List<FileRow> all = libraryFiles(libraryId);
        // Join overlapping shared-path and title candidates. Identity checks happen only in the explicit preview.
        Map<Long, List<String>> libraryAuthors = authorsInLibrary(libraryId);
        Map<Long, Long> parents = new TreeMap<>();
        Map<String, Long> paths = new HashMap<>(), titles = new HashMap<>();
        for (FileRow file : all) {
            parents.putIfAbsent(file.bookId, file.bookId);
            Long samePath = paths.putIfAbsent(file.key(), file.bookId);
            if (samePath != null) union(parents, samePath, file.bookId);
            String title = candidateKey(file, libraryAuthors);
            if (!title.startsWith("unique-")) {
                Long sameTitle = titles.putIfAbsent(title, file.bookId);
                if (sameTitle != null) union(parents, sameTitle, file.bookId);
            }
        }
        Map<Long, List<FileRow>> grouped = new TreeMap<>();
        for (FileRow file : all) grouped.computeIfAbsent(root(parents, file.bookId), ignored -> new ArrayList<>()).add(file);
        var excluded=DuplicateExclusions.load(jdbc,libraryId);
        List<List<FileRow>> duplicates = grouped.values().stream()
                .flatMap(rows->DuplicateExclusions.split(rows,FileRow::bookId,excluded).stream())
                .filter(rows -> rows.stream().map(FileRow::bookId).distinct().count() > 1)
                .filter(rows -> search == null || search.isBlank() || rows.stream().anyMatch(file ->
                        normalise(file.title + " " + file.name).contains(normalise(search))))
                .sorted(Comparator.comparing(rows -> normalise(rows.getFirst().title))).toList();
        int first = (int) Math.min(offset == null ? (long) page * size : offset, duplicates.size());
        List<Group> groups = duplicates.subList(first, Math.min(first + size, duplicates.size())).stream()
                .map(rows -> new Group(sha(rows.stream().map(FileRow::bookId).distinct().sorted().toList().toString()), rows.getFirst().path().toString(), suggested(rows),
                        groupMembers(rows))).toList();
        return new Scan(libraryId, page, size, duplicates.size(), duplicates.stream()
                .mapToInt(rows -> (int) rows.stream().map(FileRow::bookId).distinct().count() - 1).sum(), groups, first);
    }

    @Transactional(readOnly = true)
    public Preview preview(long libraryId, List<Long> bookIds, long targetBookId) {
        requireAdmin();
        return inspect(libraryId, bookIds, targetBookId, false).preview;
    }

    @Transactional(readOnly = true)
    public Preview preview(long libraryId, List<Long> bookIds, long targetBookId, boolean identityConfirmed) {
        requireAdmin();
        return inspect(libraryId,bookIds,targetBookId,false,false,identityConfirmed).preview;
    }

    @Transactional
    public void exclude(long libraryId,List<Long> bookIds) {exclude(libraryId,bookIds,null);}

    @Transactional
    public void exclude(long libraryId,List<Long> bookIds,Long isolatedBookId) {
        var actor=requireAdmin();quarantine.lock();
        var ids=validateIds(bookIds,bookIds.getFirst());
        if(isolatedBookId!=null&&!ids.contains(isolatedBookId))throw ApiError.GENERIC_BAD_REQUEST.createException("The separated book must belong to this group.");
        var rows=store.rows("book","id",ids,true);
        if(rows.size()!=ids.size()||rows.stream().anyMatch(row->!Long.toString(libraryId).equals(row.get("library_id"))))
            throw ApiError.CONFLICT.createException("Select existing records from this library.");
        var excluded=DuplicateExclusions.load(jdbc,libraryId);
        for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++) {
            if(isolatedBookId!=null&&!ids.get(i).equals(isolatedBookId)&&!ids.get(j).equals(isolatedBookId))continue;
            var pair=DuplicateExclusions.Pair.of(ids.get(i),ids.get(j));
            if(excluded.add(pair))jdbc.update("INSERT INTO catalogue_duplicate_exclusion(library_id,book_low_id,book_high_id,user_id) VALUES(?,?,?,?)",
                    libraryId,pair.low(),pair.high(),actor.getId());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt merge(long libraryId, List<Long> bookIds, long targetBookId, String previewToken, boolean confirmed) {
        return merge(libraryId,bookIds,targetBookId,previewToken,confirmed,false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt merge(long libraryId,List<Long> bookIds,long targetBookId,String previewToken,boolean confirmed,boolean identityConfirmed) {
        BookLoreUser actor = requireAdmin();
        if (!confirmed || previewToken == null || previewToken.isBlank())
            throw ApiError.GENERIC_BAD_REQUEST.createException("Inspect and explicitly confirm this group before merging.");
        quarantine.lock();
        List<Long> ids = validateIds(bookIds, targetBookId);
        if (store.rows("book", "id", ids, true).size() != ids.size())
            throw ApiError.CONFLICT.createException("Book records changed. Scan again.");
        Inspection inspection = inspect(libraryId, ids, targetBookId, true, false, identityConfirmed);
        return applyInspection(actor, libraryId, ids, targetBookId, inspection, previewToken);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuickMergeResult quickMerge(long libraryId, List<Long> bookIds, long targetBookId) {
        return quickMergeWithDecision(libraryId, bookIds, targetBookId, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuickMergeResult confirmIdentityAndMerge(long libraryId, List<Long> bookIds, long targetBookId, boolean identityConfirmed) {
        requireAdmin();
        if (!identityConfirmed) throw ApiError.GENERIC_BAD_REQUEST.createException("Explicitly confirm the book identity before merging.");
        return quickMergeWithDecision(libraryId, bookIds, targetBookId, true);
    }

    private QuickMergeResult quickMergeWithDecision(long libraryId, List<Long> bookIds, long targetBookId, boolean identityConfirmed) {
        BookLoreUser actor = requireAdmin();
        quarantine.lock();
        List<Long> ids = validateIds(bookIds, targetBookId);
        if (store.rows("book", "id", ids, true).size() != ids.size()) throw ApiError.CONFLICT.createException("Book records changed. Refresh this group before merging.");
        Inspection inspection = inspect(libraryId, ids, targetBookId, true, false, identityConfirmed);
        List<String> reasons = inspection.preview.reasons;
        if (!reasons.isEmpty()) return new QuickMergeResult("REVIEW_REQUIRED", null, inspection.preview, reasons);
        Receipt receipt = applyInspection(actor, libraryId, ids, targetBookId, inspection, inspection.preview.token);
        return new QuickMergeResult("MERGED", receipt, null, List.of());
    }

    private Receipt applyInspection(BookLoreUser actor, long libraryId, List<Long> ids, long targetBookId,
                                    Inspection inspection, String previewToken) {
        Preview current = inspection.preview;
        if (!current.eligible || !MessageDigest.isEqual(current.token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                previewToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw ApiError.CONFLICT.createException("The group or file changed, or needs review. Inspect it again before merging.");
        // Expanded shared references must be included in the confirmed preview, never merged invisibly.
        List<Long> actualIds = current.members.stream().map(Member::bookId).toList();
        if (!new HashSet<>(ids).equals(new HashSet<>(actualIds)))
            throw ApiError.CONFLICT.createException("Additional shared references were found. Confirm the expanded preview first.");
        List<Long> removedBooks = ids.stream().filter(id -> id != targetBookId).toList();
        List<Long> removedFiles = current.files.stream().filter(file -> file.fileId != file.retainedFileId).map(FilePlan::fileId).toList();
        List<Long> retainedFiles = current.files.stream().map(FilePlan::retainedFileId).distinct().toList();
        String receiptId = UUID.randomUUID().toString();
        List<CatalogueQuarantineService.Move> moves = current.files.stream().filter(file -> file.action.equals("QUARANTINE_COPY"))
                .map(file -> new CatalogueQuarantineService.Move(file.path,
                        Path.of(inspection.files.stream().filter(row -> row.fileId == file.fileId).findFirst().orElseThrow().root)
                                .toAbsolutePath().normalize().resolve(".grimmory-quarantine").resolve(receiptId)
                                .resolve(file.fileId + ".quarantined").toString(), file.retainedPath, file.retainedFileId, file.sha256)).toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("A transaction is required for catalogue reconciliation.");
        if (!moves.isEmpty()) {
            monitoring.suppressLibraryEvents(libraryId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    try {quarantine.finish(receiptId, status == STATUS_COMMITTED);}
                    catch (Exception e) {log.error("Quarantine journal requires recovery for receipt {}", receiptId, e);}
                    finally {monitoring.resumeLibraryEvents(libraryId);}
                }
            });
            quarantine.prepare(receiptId, libraryId, moves);
            quarantine.move(moves);
        }
        store.apply(inspection.schema, inspection.changes);
        for (String table : CatalogueReconciliationStore.BOOK_DATA) {
            if (inspection.schema.bookLinks().containsKey(table) && !store.rows(table, inspection.schema.bookLinks().get(table), removedBooks, true).isEmpty())
                throw ApiError.CONFLICT.createException("Related user data changed. No merge was applied.");
        }
        for (String table : CatalogueReconciliationStore.FILE_DATA) {
            if (inspection.schema.fileLinks().containsKey(table) && !store.rows(table, inspection.schema.fileLinks().get(table), removedFiles, true).isEmpty())
                throw ApiError.CONFLICT.createException("File reading data changed. No merge was applied.");
        }
        FileRow target = inspection.files.stream().filter(file -> file.bookId == targetBookId).findFirst().orElseThrow();
        Path targetRoot = Path.of(target.root).toAbsolutePath().normalize();
        long preferredFileId = preferredTargetFile(inspection.before, targetBookId);
        for (FilePlan file : current.files) if (file.fileId == file.retainedFileId) {
            Path path = Path.of(file.path);
            String subPath = targetRoot.relativize(path.getParent()).toString();
            if (jdbc.update("UPDATE book_file SET book_id=?,file_sub_path=?,preferred_primary=? WHERE id=?", targetBookId, subPath, file.fileId == preferredFileId, file.fileId) != 1)
                throw ApiError.CONFLICT.createException("Retained file records changed. Inspect the group again.");
        }
        store.removeCatalogueRecords(inspection.schema, removedBooks, removedFiles);
        // Verify every format and quarantined copy again before making the commit marker durable.
        try {
            for (FilePlan file : current.files) if (file.fileId == file.retainedFileId) CatalogueQuarantineService.verify(Path.of(file.path), file.sha256);
            for (var move : moves) CatalogueQuarantineService.verify(Path.of(move.quarantinePath()), move.sha256());
        } catch (Exception e) {throw ApiError.CONFLICT.createException("File content changed during reconciliation. The transaction was rolled back.");}
        // Repair only retained catalogue cache entries, after full content verification.
        // Original fingerprints remain in before_json, including removed shared references.
        for (var repair : inspection.fingerprintRepairs.entrySet()) if (retainedFiles.contains(repair.getKey())) {
            if (jdbc.update("UPDATE book_file SET current_hash=? WHERE id=?", repair.getValue(), repair.getKey()) != 1)
                throw ApiError.CONFLICT.createException("The retained file record changed. No merge was applied.");
        }
        Map<String, Object> after = new TreeMap<>(store.snapshot(inspection.schema, List.of(targetBookId), retainedFiles, true));
        after.put("_fingerprint_repairs", inspection.fingerprintRepairs);
        after.put("_human_identity_confirmed",current.identityConfirmed);
        after.put("_file_plan", current.files); after.put("_quarantine", moves); after.put("_retention_days", quarantine.retentionDays());
        Instant completed = Instant.now();
        jdbc.update("""
                INSERT INTO catalogue_reconciliation_audit
                (id,user_id,library_id,target_book_id,created_at,file_path,file_sha256,before_json,after_json)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, receiptId, actor.getId(), libraryId, targetBookId,
                java.sql.Timestamp.from(completed), current.path, current.fileSha256, json(inspection.before), json(after));
        auditService.log(AuditAction.DUPLICATE_BOOKS_MERGED, "Book", targetBookId,
                "Reviewed duplicate reconciliation of " + removedBooks + "; quarantined copies " + moves.size() + "; receipt " + receiptId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {notificationService.sendMessageToUser(actor.getUsername(), Topic.BOOKS_REMOVE, removedBooks);}
                catch (Exception e) {log.warn("Catalogue merge committed, but client notification failed; receipt {}", receiptId, e);}
            }
        });
        return new Receipt(receiptId, targetBookId, removedBooks, current.fileSha256, completed, current.files, quarantine.retentionDays());
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
        rows.getFirst().put("quarantine", quarantine.entries(id));
        return rows.getFirst();
    }

    private Inspection inspect(long libraryId, List<Long> requestedIds, long targetBookId, boolean lock) {
        return inspect(libraryId, requestedIds, targetBookId, lock, true);
    }

    private Inspection inspect(long libraryId, List<Long> requestedIds, long targetBookId, boolean lock, boolean expand) {
        return inspect(libraryId,requestedIds,targetBookId,lock,expand,false);
    }

    private Inspection inspect(long libraryId,List<Long> requestedIds,long targetBookId,boolean lock,boolean expand,boolean identityConfirmed) {
        List<Long> ids = validateIds(requestedIds, targetBookId);
        List<FileRow> all = libraryFiles(libraryId, expand ? null : ids);
        // Only explicit inspection expands candidates; direct merges honor the selected records.
        if (expand) {
            Map<Long, List<String>> libraryAuthors = authorsInLibrary(libraryId);
            Set<Long> expanded = new TreeSet<>(ids);
            boolean changed;
            do {
                Set<String> keys = new HashSet<>(all.stream().filter(file -> expanded.contains(file.bookId)).map(FileRow::key).toList());
                Set<String> identities = new HashSet<>(all.stream().filter(file -> expanded.contains(file.bookId))
                        .map(file -> candidateKey(file, libraryAuthors)).filter(key -> !key.startsWith("unique-")).toList());
                changed = false;
                for (FileRow file : all) if (keys.contains(file.key()) || identities.contains(candidateKey(file, libraryAuthors))) changed |= expanded.add(file.bookId);
                if (expanded.size() > 20) throw ApiError.CONFLICT.createException("This connected group has more than 20 records and requires a separate audit.");
            } while (changed);
            ids = List.copyOf(expanded);
        }
        List<Long> selectedIds = ids;
        List<FileRow> files = all.stream().filter(file -> selectedIds.contains(file.bookId)).toList();
        if (files.stream().map(FileRow::bookId).distinct().count() != ids.size() || files.size() > 40)
            throw ApiError.CONFLICT.createException("Every selected record must have files, with at most 40 files in one preview.");
        if (lock && store.rows("book", "id", ids, true).size() != ids.size()) throw ApiError.CONFLICT.createException("The connected group changed.");
        CatalogueReconciliationStore.Schema schema = store.schema();
        Map<String, List<Map<String, String>>> before = store.snapshot(schema, ids, files.stream().map(FileRow::fileId).toList(), lock);
        List<Map<String, String>> roots = store.rows("library_path", "id", files.stream().map(FileRow::pathId).distinct().toList(), lock);
        before.put("catalogue_duplicate_exclusion", java.util.stream.Stream.concat(
                store.rows("catalogue_duplicate_exclusion","book_low_id",ids,lock).stream(),
                store.rows("catalogue_duplicate_exclusion","book_high_id",ids,lock).stream()).distinct().toList());
        before.put("library_path", roots);
        before.put("library", store.rows("library", "id", List.of(libraryId), lock));
        // Discovery precedes the locks. Never base a direct merge on values changed during that gap.
        for (FileRow file : files) {
            var book = before.get("book").stream().filter(row -> Long.toString(file.bookId).equals(row.get("id"))).findFirst().orElseThrow();
            var stored = before.get("book_file").stream().filter(row -> Long.toString(file.fileId).equals(row.get("id"))).findFirst().orElseThrow();
            var metadata = before.getOrDefault("book_metadata", List.of()).stream().filter(row -> Long.toString(file.bookId).equals(row.get("book_id"))).findFirst().orElse(Map.of());
            var root = roots.stream().filter(row -> Long.toString(file.pathId).equals(row.get("id"))).findFirst().orElse(Map.of());
            if (!Long.toString(libraryId).equals(book.get("library_id")) || !Long.toString(file.pathId).equals(book.get("library_path_id"))
                    || !Objects.equals(file.root, root.get("path")) || !Objects.equals(file.title, metadata.get("title"))
                    || !Long.toString(file.bookId).equals(stored.get("book_id")) || !Objects.equals(file.storedHash, stored.get("current_hash"))
                    || !Objects.equals(file.name, stored.get("file_name")) || !Objects.equals(file.subPath, stored.get("file_sub_path"))
                    || !Objects.equals(file.bookType, stored.get("book_type"))
                    || file.physical != storedBoolean(book.get("is_physical")) || file.deleted != storedBoolean(book.get("deleted"))
                    || file.bookFormat != storedBoolean(stored.get("is_book")) || file.folderBased != storedBoolean(stored.get("is_folder_based")))
                throw ApiError.CONFLICT.createException("The group changed during its checks. Retry after the records are stable.");
        }
        List<String> reasons = new ArrayList<>();
        if (schema.tables().containsKey("kobo_reading_state") && jdbc.queryForObject("SELECT COUNT(*) FROM kobo_reading_state", Long.class) > 0)
            reasons.add("Device reading state exists. Device identity reconciliation requires separate review.");
        if (files.stream().anyMatch(file -> file.physical || file.deleted)) reasons.add("Physical or deleted book records require separate review.");
        if (store.rows("book_file", "book_id", ids, lock).size() != files.size()) reasons.add("Some files are outside this library's registered paths.");
        if (files.stream().anyMatch(file -> !file.bookFormat || file.folderBased)) reasons.add("Attachments and folder-based books require a separate review.");
        Set<String> allowed = new HashSet<>(CatalogueReconciliationStore.BOOK_DATA);
        allowed.addAll(CatalogueReconciliationStore.FILE_DATA); allowed.addAll(CatalogueReconciliationStore.METADATA);
        allowed.addAll(CatalogueReconciliationStore.REVIEW_ONLY); allowed.add("book_file");
        schema.bookLinks().keySet().stream().filter(table -> !allowed.contains(table)).forEach(table -> reasons.add("Unsupported related catalogue data: " + table));
        schema.fileLinks().keySet().stream().filter(table -> !allowed.contains(table)).forEach(table -> reasons.add("Unsupported related file data: " + table));
        for (String table : CatalogueReconciliationStore.REVIEW_ONLY) {
            if (before.getOrDefault(table, List.of()).stream().anyMatch(row -> !Long.toString(targetBookId).equals(row.get("book_id"))))
                reasons.add("Pending metadata proposals or device snapshots require separate review.");
        }
        Map<Long, String> authorLookup = new HashMap<>();
        for (var row : before.getOrDefault("author", List.of())) authorLookup.put(Long.valueOf(row.get("id")), row.get("name"));
        Map<Long, List<String>> authors = new TreeMap<>();
        for (var row : before.getOrDefault("book_metadata_author_mapping", List.of())) {
            String name = authorLookup.get(Long.valueOf(row.get("author_id")));
            if (name != null) authors.computeIfAbsent(Long.valueOf(row.get("book_id")), ignored -> new ArrayList<>()).add(name);
        }
        FileRow target = files.stream().filter(file -> file.bookId == targetBookId).findFirst().orElseThrow();
        BookMetadata targetIdentity = BookMetadata.builder().title(target.title).authors(authors.getOrDefault(targetBookId, List.of())).build();
        if (!identityConfirmed && (authors.values().stream().anyMatch(List::isEmpty) || authors.size() != ids.size())) reasons.add("Author identity is incomplete.");
        for (FileRow file : files) {
            BookMetadata identity = BookMetadata.builder().title(file.title).authors(authors.getOrDefault(file.bookId, List.of())).build();
            if (!identityConfirmed && (!MetadataIdentityMatcher.matches(targetIdentity, identity) || !MetadataIdentityMatcher.matches(identity, targetIdentity))) {
                reasons.add("Titles or authors disagree. Resolve the metadata before merging."); break;
            }
        }
        Map<Long, FileCheck> checks = new TreeMap<>();
        Map<String, FileCheck> pathChecks = new HashMap<>();
        long totalBytes = 0;
        for (FileRow file : files) {
            try {
                FileCheck checked = pathChecks.get(file.key());
                if (checked == null) {
                    if (totalBytes + Files.size(file.path()) > 1024L * 1024 * 1024) {
                        reasons.add("This preview exceeds the 1 GiB content verification limit."); break;
                    }
                    checked = checkFile(file); pathChecks.put(file.key(), checked); totalBytes += checked.bytes;
                    if (totalBytes > 1024L * 1024 * 1024) throw new IllegalStateException("This preview exceeds the 1 GiB content verification limit.");
                }
                checks.put(file.fileId, checked);
                if (Files.isSymbolicLink(file.path()) || !Path.of(file.root).toAbsolutePath().normalize().equals(Path.of(file.root).toRealPath()))
                    reasons.add("Symbolic file paths require separate review before quarantine.");
            } catch (Exception e) {reasons.add(e.getMessage());}
        }
        Map<Long, String> fingerprintRepairs = new TreeMap<>();
        for (FileRow file : files) {
            FileCheck checked = checks.get(file.fileId);
            if (checked == null || checked.storedFingerprint.equalsIgnoreCase(file.storedHash)) continue;
            // Legacy catalogue records can share one path but carry different cached hashes.
            // A selected reference to that exact verified physical path must already match.
            // Matching bytes at a different path are insufficient evidence for this repair.
            boolean verifiedReference = files.stream().anyMatch(peer -> peer.fileId != file.fileId
                    && checks.containsKey(peer.fileId) && checked.path.equals(checks.get(peer.fileId).path)
                    && checked.storedFingerprint.equalsIgnoreCase(peer.storedHash));
            if (verifiedReference) fingerprintRepairs.put(file.fileId, checked.storedFingerprint);
            else reasons.add("A stored fingerprint no longer matches the physical file. No selected reference to this same path has a matching fingerprint; inspect the changed file before merging.");
        }
        Map<Long, Long> fileTargets = new TreeMap<>();
        Map<String, FileRow> survivors = new HashMap<>();
        long preferredFileId = preferredTargetFile(before, targetBookId);
        List<FileRow> preferred = files.stream().sorted(Comparator.comparing((FileRow row) -> row.bookId != targetBookId)
                .thenComparing(row -> row.fileId != preferredFileId).thenComparing(FileRow::fileId)).toList();
        for (FileRow file : preferred) {
            FileCheck checked = checks.get(file.fileId);
            String contentKey = checked == null ? "unverified-" + file.fileId : file.bookType + "\n" + checked.sha256;
            FileRow survivor = survivors.computeIfAbsent(contentKey, ignored -> file);
            fileTargets.put(file.fileId, survivor.fileId);
        }
        List<FilePlan> plans = new ArrayList<>();
        Set<String> physicalCopies = new HashSet<>();
        Path targetRoot = Path.of(target.root).toAbsolutePath().normalize();
        for (FileRow file : files) {
            FileRow survivor = files.stream().filter(row -> row.fileId == fileTargets.get(file.fileId)).findFirst().orElseThrow();
            FileCheck checked = checks.get(file.fileId), retained = checks.get(survivor.fileId);
            String path = checked == null ? file.path().toString() : checked.path;
            String retainedPath = retained == null ? survivor.path().toString() : retained.path;
            String action = file.fileId == survivor.fileId ? "KEEP" : path.equals(retainedPath) || !physicalCopies.add(path) ? "SHARED_REFERENCE" : "QUARANTINE_COPY";
            plans.add(new FilePlan(file.fileId, file.bookId, path, file.bookType, checked == null ? null : checked.sha256,
                    checked == null ? 0 : checked.bytes, action, survivor.fileId, retainedPath));
            if (!Path.of(retainedPath).startsWith(targetRoot)) reasons.add("Files spanning different library roots require a separate move plan.");
        }
        for (String path : plans.stream().map(FilePlan::path).distinct().toList()) {
            if (plans.stream().filter(file -> file.path.equals(path)).map(FilePlan::bookType).distinct().count() > 1)
                reasons.add("One physical file is registered under conflicting formats. Review its format metadata.");
        }
        if (!quarantine.supportsQuarantine() && plans.stream().anyMatch(file -> file.action.equals("QUARANTINE_COPY")))
            reasons.add("Atomic quarantine requires local storage. Network libraries need a separate file plan.");
        checkExternalReferences(plans, files.stream().map(FileRow::fileId).toList(), reasons);
        List<CatalogueReconciliationStore.Change> changes = store.plan(schema, before, targetBookId, fileTargets, reasons);
        String snapshot = json(before);
        if (snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000) reasons.add("This group has too much metadata or user data for one merge.");
        Map<String, Integer> related = new TreeMap<>();
        for (String table : before.keySet()) if (CatalogueReconciliationStore.BOOK_DATA.contains(table) || CatalogueReconciliationStore.FILE_DATA.contains(table)
                || CatalogueReconciliationStore.REVIEW_ONLY.contains(table)) related.put(table, before.get(table).size());
        Map<String, Map<Long, String>> differences = differences(before.getOrDefault("book_metadata", List.of()));
        FilePlan anchor = plans.stream().filter(file -> file.fileId == preferredFileId).findFirst().orElseThrow();
        String token = sha(identityConfirmed + "\n" + libraryId + "\n" + targetBookId + "\n" + json(plans) + "\n" + snapshot + "\n" + quarantine.retentionDays());
        List<Member> members = new ArrayList<>();
        for (FileRow file : files) if (members.stream().noneMatch(member -> member.bookId == file.bookId)) members.add(member(file, authors.getOrDefault(file.bookId, List.of())));
        Preview preview = new Preview(libraryId, targetBookId, members, anchor.path, anchor.sha256, totalBytes,
                reasons.isEmpty(), reasons.stream().distinct().toList(), related, differences, token, plans, quarantine.retentionDays(), identityConfirmed);
        return new Inspection(preview, schema, before, changes, files, fileTargets, fingerprintRepairs);
    }

    private void checkExternalReferences(List<FilePlan> plans, List<Long> selectedFiles, List<String> reasons) {
        Set<String> moved = new HashSet<>(plans.stream().filter(file -> file.action.equals("QUARANTINE_COPY")).map(FilePlan::path).toList());
        if (moved.isEmpty()) return;
        List<String> names = moved.stream().map(path -> Path.of(path).getFileName().toString()).distinct().toList();
        var refs = jdbc.query("SELECT f.id,p.path,f.file_sub_path,f.file_name FROM book_file f JOIN book b ON b.id=f.book_id JOIN library_path p ON p.id=b.library_path_id WHERE f.file_name IN ("
                        + CatalogueReconciliationStore.placeholders(names.size()) + ") LIMIT 200001",
                (rs, n) -> Map.entry(rs.getLong(1), Path.of(rs.getString(2), rs.getString(3), rs.getString(4)).toAbsolutePath().normalize()), names.toArray());
        if (refs.size() > MAX_LIBRARY_FILES) {reasons.add("Too many file references for a safe interactive quarantine."); return;}
        for (var ref : refs) if (!selectedFiles.contains(ref.getKey())) {
            try {String sharedPath = ref.getValue().toRealPath().toString();
                if (moved.contains(sharedPath)) for (int i = 0; i < plans.size(); i++) {
                    FilePlan plan = plans.get(i);
                    if (plan.action.equals("QUARANTINE_COPY") && plan.path.equals(sharedPath))
                        plans.set(i, new FilePlan(plan.fileId, plan.bookId, plan.path, plan.bookType, plan.sha256, plan.bytes,
                                "SHARED_REFERENCE", plan.retainedFileId, plan.retainedPath));
                }}
            catch (Exception ignored) {if (moved.contains(ref.getValue().toString())) reasons.add("An external file reference cannot be verified.");}
        }
    }

    private Map<Long, List<String>> authorsInLibrary(long libraryId) {
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.query("SELECT ma.book_id,a.name FROM book_metadata_author_mapping ma JOIN author a ON a.id=ma.author_id JOIN book b ON b.id=ma.book_id WHERE b.library_id=? ORDER BY ma.book_id,a.name LIMIT 1000001", rs -> {
            result.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        }, libraryId);
        if (result.values().stream().mapToInt(List::size).sum() > 1_000_000) throw ApiError.CONFLICT.createException("Too much author data for an interactive duplicate audit.");
        return result;
    }
    private static String candidateKey(FileRow file, Map<Long, List<String>> authors) {
        String title = normalise(file.title);
        List<String> names = authors.getOrDefault(file.bookId, List.of()).stream().map(CatalogueReconciliationService::normalise)
                .filter(name -> !name.isBlank()).distinct().sorted().toList();
        return title.isBlank() || names.isEmpty() ? "unique-" + file.bookId : title + "\n" + String.join("\n", names);
    }

    private long preferredTargetFile(Map<String, List<Map<String, String>>> before, long targetBookId) {
        var priority = new org.booklore.convertor.FormatPriorityConverter(mapper).convertToEntityAttribute(
                before.get("library").getFirst().get("format_priority"));
        var library = org.booklore.model.entity.LibraryEntity.builder().formatPriority(priority).build();
        Set<org.booklore.model.entity.BookFileEntity> targetFiles = new HashSet<>();
        for (var row : before.get("book_file")) if (Long.toString(targetBookId).equals(row.get("book_id"))) {
            targetFiles.add(org.booklore.model.entity.BookFileEntity.builder().id(Long.valueOf(row.get("id")))
                    .bookType(org.booklore.model.enums.BookFileType.valueOf(row.get("book_type")))
                    .isBookFormat(storedBoolean(row.get("is_book"))).preferredPrimary(storedBoolean(row.get("preferred_primary"))).build());
        }
        return org.booklore.model.entity.BookEntity.builder().library(library).bookFiles(targetFiles).build().getPrimaryBookFile().getId();
    }

    private static boolean storedBoolean(String value) {return "1".equals(value) || "true".equalsIgnoreCase(value);}

    private static String normalise(String value) {return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();}
    private static long root(Map<Long, Long> parents, long id) {while (parents.get(id) != id) {parents.put(id, parents.get(parents.get(id))); id = parents.get(id);} return id;}
    private static void union(Map<Long, Long> parents, long first, long second) {long a = root(parents, first), b = root(parents, second); if (a != b) parents.put(Math.max(a,b), Math.min(a,b));}

    private List<FileRow> libraryFiles(long libraryId) {return libraryFiles(libraryId, null);}

    private List<FileRow> libraryFiles(long libraryId, List<Long> selectedIds) {
        List<Object> parameters = new ArrayList<>(); parameters.add(libraryId);
        String selected = "";
        if (selectedIds != null) {
            selected = " AND b.id IN (" + CatalogueReconciliationStore.placeholders(selectedIds.size()) + ")";
            parameters.addAll(selectedIds);
        }
        parameters.add(selectedIds == null ? MAX_LIBRARY_FILES + 1 : 41);
        List<FileRow> rows = jdbc.query("""
                SELECT b.id,f.id,b.library_path_id,p.path,f.file_sub_path,f.file_name,m.title,f.current_hash,
                       b.metadata_match_score,b.is_physical,b.deleted,f.book_type,f.is_book,f.is_folder_based
                FROM book_file f JOIN book b ON b.id=f.book_id JOIN library_path p ON p.id=b.library_path_id
                LEFT JOIN book_metadata m ON m.book_id=b.id WHERE b.library_id=?
                """ + selected + " ORDER BY b.id,f.id LIMIT ?", (rs, index) -> new FileRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getDouble(9), rs.getBoolean(10), rs.getBoolean(11), rs.getString(12), rs.getBoolean(13), rs.getBoolean(14)), parameters.toArray());
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
