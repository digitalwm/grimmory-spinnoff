package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Durable filesystem journal. Renames stay on the source filesystem; files are never overwritten. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatalogueQuarantineService {
    private final JdbcTemplate jdbc;
    @Value("${app.catalogue-quarantine-retention-days:30}") private int retentionDays = 30;

    @Value("${app.disk-type:LOCAL}") private String diskType = "LOCAL";
    public boolean supportsQuarantine() {return "LOCAL".equalsIgnoreCase(diskType);}

    public record Move(String originalPath, String quarantinePath, String retainedPath, long retainedFileId, String sha256) {}
    public int retentionDays() {return Math.max(0, Math.min(3650, retentionDays));}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(String receiptId, long libraryId, List<Move> moves) {
        Instant now = Instant.now();
        for (Move move : moves) jdbc.update("""
                INSERT INTO catalogue_quarantine(id,receipt_id,library_id,original_path,quarantine_path,retained_path,retained_file_id,file_sha256,state,purge_after)
                VALUES(?,?,?,?,?,?,?,?,'PREPARED',?)
                """, UUID.randomUUID().toString(), receiptId, libraryId, move.originalPath, move.quarantinePath,
                move.retainedPath, move.retainedFileId, move.sha256, retentionDays() == 0 ? null : Timestamp.from(now.plus(retentionDays(), ChronoUnit.DAYS)));
    }

    public void move(List<Move> moves) {
        if (!moves.isEmpty() && !supportsQuarantine()) throw ApiError.CONFLICT.createException("Atomic quarantine requires local storage. Network libraries need a separate file plan.");
        try {
            for (Move move : moves) {
                Path original = Path.of(move.originalPath), destination = Path.of(move.quarantinePath);
                verify(original, move.sha256); verify(Path.of(move.retainedPath), move.sha256);
                Path quarantineRoot = destination.getParent().getParent();
                Files.createDirectories(quarantineRoot);
                if (Files.isSymbolicLink(quarantineRoot) || !quarantineRoot.toRealPath().equals(quarantineRoot))
                    throw new IllegalStateException("Quarantine directory must not be a symbolic link.");
                if (!Files.exists(quarantineRoot.resolve(".ignore"))) Files.writeString(quarantineRoot.resolve(".ignore"), "Grimmory recoverable duplicate quarantine\n", StandardOpenOption.CREATE_NEW);
                if (!Files.exists(destination.getParent())) Files.createDirectory(destination.getParent());
                if (Files.isSymbolicLink(destination.getParent()) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("Quarantine destination is already occupied.");
                Files.move(original, destination, StandardCopyOption.ATOMIC_MOVE);
                verify(destination, move.sha256);
            }
        } catch (Exception e) {throw ApiError.CONFLICT.createException("Could not quarantine a verified duplicate. The merge was rolled back; recovery details are retained in the journal. " + e.getMessage());}
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String receiptId, boolean committed) {
        lock(); // Serialize completion callbacks with crash recovery and retention cleanup.
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM catalogue_quarantine WHERE receipt_id=? AND state='PREPARED'", receiptId)) {
            try {
                if (!committed) restore(row);
                jdbc.update("UPDATE catalogue_quarantine SET state=?,completed_at=CURRENT_TIMESTAMP,detail=NULL WHERE id=?",
                        committed ? "QUARANTINED" : "RESTORED", row.get("id"));
            } catch (Exception e) {
                // Leave PREPARED for durable recovery; never replace a file that appeared at the original path.
                jdbc.update("UPDATE catalogue_quarantine SET detail=? WHERE id=?", "Recovery pending: " + e.getMessage(), row.get("id"));
                log.error("Quarantine recovery pending for journal {}", row.get("id"), e);
            }
        }
    }

    @Transactional
    public void maintain() {
        lock();
        // A crash can happen on either side of the main DB commit. The receipt is the commit marker.
        for (String receipt : jdbc.queryForList("SELECT DISTINCT receipt_id FROM catalogue_quarantine WHERE state='PREPARED'", String.class)) {
            boolean committed = jdbc.queryForObject("SELECT COUNT(*) FROM catalogue_reconciliation_audit WHERE id=?", Long.class, receipt) > 0;
            finishInCurrentTransaction(receipt, committed);
        }
        if (retentionDays() == 0) return;
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM catalogue_quarantine WHERE state='QUARANTINED' AND purge_after IS NOT NULL AND purge_after<CURRENT_TIMESTAMP LIMIT 100")) {
            try {
                // An expired copy is removable only while the retained file is still verifiably intact.
                List<Path> registered = jdbc.query("SELECT p.path,f.file_sub_path,f.file_name FROM book_file f JOIN book b ON b.id=f.book_id JOIN library_path p ON p.id=b.library_path_id WHERE f.id=?",
                        (rs, index) -> Path.of(rs.getString(1), rs.getString(2), rs.getString(3)).toAbsolutePath().normalize(), row.get("retained_file_id"));
                if (registered.size() != 1 || !registered.getFirst().toRealPath().equals(Path.of(row.get("retained_path").toString())))
                    throw new IllegalStateException("The retained file is no longer registered at the verified path.");
                verify(Path.of(row.get("retained_path").toString()), row.get("file_sha256").toString());
                Path copy = Path.of(row.get("quarantine_path").toString());
                if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
                    verify(copy, row.get("file_sha256").toString()); Files.delete(copy);
                } // A previous process may have purged the bytes immediately before losing its DB commit.
                jdbc.update("UPDATE catalogue_quarantine SET state='PURGED',completed_at=CURRENT_TIMESTAMP,detail=NULL WHERE id=?", row.get("id"));
            } catch (Exception e) {
                jdbc.update("UPDATE catalogue_quarantine SET detail=? WHERE id=?", "Retained for review: " + e.getMessage(), row.get("id"));
            }
        }
    }

    private void finishInCurrentTransaction(String receipt, boolean committed) {finish(receipt, committed);}
    public void lock() {jdbc.queryForObject("SELECT id FROM catalogue_reconciliation_lock WHERE id=1 FOR UPDATE", Integer.class);}
    public List<Map<String, String>> entries(String receiptId) {
        return jdbc.query("SELECT id,original_path,quarantine_path,retained_path,file_sha256,state,created_at,purge_after,completed_at,detail FROM catalogue_quarantine WHERE receipt_id=? ORDER BY id", (rs, index) -> {
            Map<String, String> row = new TreeMap<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) row.put(rs.getMetaData().getColumnLabel(c).toLowerCase(Locale.ROOT), rs.getString(c));
            return row;
        }, receiptId);
    }

    public record Download(String name, org.springframework.core.io.InputStreamResource resource) {}
    @Transactional
    public Download open(String id) {
        lock();
        var rows = jdbc.queryForList("SELECT original_path,quarantine_path,file_sha256 FROM catalogue_quarantine WHERE id=? AND state='QUARANTINED'", id);
        if (rows.isEmpty()) throw ApiError.RESOURCE_NOT_FOUND.createException("Quarantined file", id);
        var row = rows.getFirst();
        Path path = Path.of(row.get("quarantine_path").toString());
        try {
            verify(path, row.get("file_sha256").toString());
            return new Download(Path.of(row.get("original_path").toString()).getFileName().toString(),
                    new org.springframework.core.io.InputStreamResource(Files.newInputStream(path)));
        } catch (Exception e) {throw ApiError.CONFLICT.createException("Quarantined file changed or is unavailable. Its journal entry has been preserved.");}
    }

    private void restore(Map<String, Object> row) throws Exception {
        Path original = Path.of(row.get("original_path").toString()), copy = Path.of(row.get("quarantine_path").toString());
        String sha = row.get("file_sha256").toString();
        if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
            verify(copy, sha);
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Original path is occupied; quarantined bytes preserved for manual recovery.");
            if (!original.getParent().toRealPath().equals(original.getParent())) throw new IllegalStateException("Original parent path changed; backup retained for review.");
            Files.move(copy, original, StandardCopyOption.ATOMIC_MOVE);
        } else verify(original, sha); // No move occurred, or an earlier recovery already restored the file.
    }

    static void verify(Path file, String expected) throws Exception {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("File missing or no longer regular: " + file);
        if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) throw new IllegalStateException("File parent path changed: " + file);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int n; long bytes = 0;
            while ((n = input.read(buffer)) != -1) {
                if ((bytes += n) > 512L * 1024 * 1024) throw new IllegalStateException("File grew beyond the verification limit.");
                digest.update(buffer, 0, n);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IllegalStateException("File checksum changed: " + file);
    }
}
