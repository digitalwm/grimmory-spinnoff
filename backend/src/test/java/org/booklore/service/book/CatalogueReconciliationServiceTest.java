package org.booklore.service.book;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL, constraints, transactions and filesystem reads against an isolated H2 fixture. */
class CatalogueReconciliationServiceTest {
    @TempDir Path folder;
    private JdbcTemplate jdbc;
    private CatalogueReconciliationService service;
    private TransactionTemplate transaction;
    private Path file;
    private BookLoreUser user;

    @BeforeEach void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:reconcile_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (String ddl : List.of(
                "CREATE TABLE library(id BIGINT PRIMARY KEY)",
                "CREATE TABLE library_path(id BIGINT PRIMARY KEY,path VARCHAR(2000))",
                "CREATE TABLE book(id BIGINT PRIMARY KEY,library_id BIGINT,library_path_id BIGINT,metadata_match_score FLOAT,is_physical BOOLEAN,deleted BOOLEAN)",
                "CREATE TABLE book_file(id BIGINT PRIMARY KEY,book_id BIGINT NOT NULL REFERENCES book(id) ON DELETE CASCADE,file_sub_path VARCHAR(512),file_name VARCHAR(1000),current_hash VARCHAR(128))",
                "CREATE TABLE book_metadata(book_id BIGINT PRIMARY KEY REFERENCES book(id) ON DELETE CASCADE,title VARCHAR(1000),description VARCHAR(5000))",
                "CREATE TABLE author(id BIGINT PRIMARY KEY,name VARCHAR(255))",
                "CREATE TABLE book_metadata_author_mapping(book_id BIGINT REFERENCES book_metadata(book_id) ON DELETE CASCADE,author_id BIGINT REFERENCES author(id),PRIMARY KEY(book_id,author_id))",
                "CREATE TABLE book_shelf_mapping(book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,shelf_id BIGINT,PRIMARY KEY(book_id,shelf_id))",
                "CREATE TABLE user_book_progress(id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,user_id BIGINT,epub_progress VARCHAR(1000),UNIQUE(user_id,book_id))",
                "CREATE TABLE user_book_file_progress(id BIGINT PRIMARY KEY,book_file_id BIGINT REFERENCES book_file(id) ON DELETE CASCADE,user_id BIGINT,position_data VARCHAR(1000),UNIQUE(user_id,book_file_id))",
                "CREATE TABLE book_marks(id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,user_id BIGINT,title VARCHAR(1000))",
                "CREATE TABLE catalogue_reconciliation_audit(id VARCHAR(36) PRIMARY KEY,user_id BIGINT,library_id BIGINT,target_book_id BIGINT,created_at TIMESTAMP,file_path VARCHAR(2000),file_sha256 VARCHAR(64),before_json CLOB,after_json CLOB)")) jdbc.execute(ddl);
        jdbc.update("INSERT INTO library VALUES(1)");
        jdbc.update("INSERT INTO library_path VALUES(1,?)", folder.toString());
        jdbc.update("INSERT INTO book VALUES(1,1,1,80,false,false),(2,1,1,70,false,false)");
        jdbc.update("INSERT INTO book_file VALUES(11,1,'','shared.epub','same-hash'),(22,2,'','shared.epub','same-hash')");
        jdbc.update("INSERT INTO book_metadata VALUES(1,'The Book','Chosen description'),(2,'The Book','Original source description')");
        jdbc.update("INSERT INTO author VALUES(1,'Ada Author')");
        jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(1,1),(2,1)");
        var permissions = new BookLoreUser.UserPermissions(); permissions.setAdmin(true);
        user = BookLoreUser.builder().id(7L).permissions(permissions).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        service = new CatalogueReconciliationService(jdbc, new CatalogueReconciliationStore(jdbc),
                new ObjectMapper(), mock(AuditService.class), mock(NotificationService.class));
        file = folder.resolve("shared.epub"); Files.writeString(file, "Original physical EPUB bytes");
        jdbc.update("UPDATE book_file SET current_hash=?", org.booklore.service.file.FileFingerprint.generateHash(file));
    }

    @AfterEach void clearAuthentication() {SecurityContextHolder.clearContext();}

    private CatalogueReconciliationService.Preview preview() {return service.preview(1, List.of(1L, 2L), 1);}
    private CatalogueReconciliationService.Receipt merge(CatalogueReconciliationService.Preview preview) {
        return transaction.execute(status -> service.merge(1, List.of(1L, 2L), 1, preview.token(), true));
    }
    private long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);}

    @Test void scanAndPreviewAreReadOnlyAndReportDifferences() throws Exception {
        var scan = service.scan(1, 0, 20);
        assertEquals(1, scan.totalGroups()); assertEquals(1, scan.extraRecords());
        assertEquals(1, scan.groups().getFirst().suggestedTargetBookId());
        var preview = preview();
        assertTrue(preview.eligible(), preview.reasons().toString());
        assertEquals(64, preview.fileSha256().length());
        assertEquals("Original source description", preview.metadataDifferences().get("description").get(2L));
        assertEquals(2, count("book")); assertEquals(2, count("book_file")); assertEquals(0, count("catalogue_reconciliation_audit"));
        assertEquals("Original physical EPUB bytes", Files.readString(file));
    }

    @Test void mergeTransfersUserAndFileDataCombinesShelvesAndArchivesOriginals() throws Exception {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,2,8,'chapter-4')");
        jdbc.update("INSERT INTO user_book_file_progress VALUES(102,22,8,'cfi-position')");
        jdbc.update("INSERT INTO book_marks VALUES(103,2,8,'Keep this bookmark')");
        jdbc.update("INSERT INTO book_shelf_mapping VALUES(1,10),(2,10),(2,20)");
        var before = preview(); assertTrue(before.eligible(), before.reasons().toString());
        var receipt = merge(before);
        assertEquals(List.of(2L), receipt.removedBookIds());
        assertEquals(1, count("book")); assertEquals(1, count("book_file"));
        assertEquals(1L, jdbc.queryForObject("SELECT book_id FROM user_book_progress WHERE id=101", Long.class));
        assertEquals(11L, jdbc.queryForObject("SELECT book_file_id FROM user_book_file_progress WHERE id=102", Long.class));
        assertEquals("Keep this bookmark", jdbc.queryForObject("SELECT title FROM book_marks WHERE book_id=1", String.class));
        assertEquals(2, count("book_shelf_mapping"));
        assertEquals("Chosen description", jdbc.queryForObject("SELECT description FROM book_metadata WHERE book_id=1", String.class));
        String archive = jdbc.queryForObject("SELECT before_json FROM catalogue_reconciliation_audit", String.class);
        assertTrue(archive.contains("Original source description")); assertTrue(archive.contains("chapter-4")); assertTrue(archive.contains("Ada Author"));
        assertEquals(1, count("catalogue_reconciliation_audit"));
        assertEquals("Original physical EPUB bytes", Files.readString(file));
        assertEquals(before.fileSha256(), receipt.fileSha256());
        assertTrue(service.receipt(receipt.id()).get("before_json") instanceof String);
        assertEquals(receipt.id(), service.receipts(1).getFirst().id());
    }

    @Test void auditFailureRollsBackEveryRecordTransferAndRemoval() throws Exception {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,2,8,'chapter-4')");
        var preview = preview();
        jdbc.execute("DROP TABLE catalogue_reconciliation_audit");
        assertThrows(RuntimeException.class, () -> merge(preview));
        assertEquals(2, count("book")); assertEquals(2, count("book_file")); assertEquals(2, count("book_metadata"));
        assertEquals(2L, jdbc.queryForObject("SELECT book_id FROM user_book_progress WHERE id=101", Long.class));
        assertEquals("Original physical EPUB bytes", Files.readString(file));
    }

    @Test void conflictingProgressForTheSameUserIsNeverOverwritten() {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,1,8,'chapter-1'),(102,2,8,'chapter-4')");
        var preview = preview(); assertFalse(preview.eligible());
        assertThrows(APIException.class, () -> merge(preview));
        assertEquals(2, count("user_book_progress")); assertEquals(2, count("book"));
    }

    @Test void stalePreviewAfterUserDataChangesCannotBeApplied() {
        var preview = preview();
        jdbc.update("INSERT INTO book_marks VALUES(103,2,8,'Added after preview')");
        assertThrows(APIException.class, () -> merge(preview));
        assertEquals(2, count("book")); assertEquals(0, count("catalogue_reconciliation_audit"));
        assertEquals(2L, jdbc.queryForObject("SELECT book_id FROM book_marks WHERE id=103", Long.class));
    }

    @Test void stalePreviewAfterFileChangesCannotBeApplied() throws Exception {
        var preview = preview(); Files.writeString(file, "Changed by another process");
        assertThrows(APIException.class, () -> merge(preview));
        assertEquals(2, count("book")); assertEquals(0, count("catalogue_reconciliation_audit"));
        assertEquals("Changed by another process", Files.readString(file));
    }

    @Test void changedGroupMembershipCannotBeApplied() {
        var preview = preview();
        jdbc.update("INSERT INTO book VALUES(3,1,1,70,false,false)");
        jdbc.update("INSERT INTO book_file VALUES(33,3,'','shared.epub','same-hash')");
        assertThrows(APIException.class, () -> merge(preview)); assertEquals(3, count("book"));
    }

    @Test void missingFileConflictingStoredHashesAndMetadataBlockMerges() throws Exception {
        Files.delete(file); assertFalse(preview().eligible()); Files.writeString(file, "restored");
        jdbc.update("UPDATE book_file SET current_hash='different' WHERE id=22");
        assertFalse(preview().eligible());
        jdbc.update("UPDATE book_file SET current_hash=?", org.booklore.service.file.FileFingerprint.generateHash(file));
        jdbc.update("UPDATE book_metadata SET title='Unrelated book' WHERE book_id=2");
        assertFalse(preview().eligible()); assertEquals(0, count("catalogue_reconciliation_audit"));
    }

    @Test void unknownRelationshipsFailClosedEvenBeforeTheyContainData() {
        jdbc.execute("CREATE TABLE future_user_data(id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES book(id),value_text VARCHAR(255))");
        assertFalse(preview().eligible()); assertEquals(2, count("book"));
    }

    @Test void sourceWithAdditionalFilesRequiresSeparateReview() {
        jdbc.update("INSERT INTO book_file VALUES(23,2,'','other.epub','another-hash')");
        assertThrows(APIException.class, this::preview); assertEquals(3, count("book_file"));
    }

    @Test void administratorAndExplicitConfirmationAreRequired() {
        var preview = preview();
        assertThrows(APIException.class, () -> transaction.execute(status -> service.merge(1, List.of(1L,2L), 1, preview.token(), false)));
        user.getPermissions().setAdmin(false);
        assertThrows(APIException.class, this::preview);
        assertThrows(APIException.class, () -> service.scan(1, 0, 20));
        assertThrows(APIException.class, () -> service.receipt("anything"));
        assertEquals(2, count("book"));
    }

    @Test void sourceFileProgressCollisionIsBlocked() {
        jdbc.update("INSERT INTO user_book_file_progress VALUES(101,11,8,'first'),(102,22,8,'second')");
        assertFalse(preview().eligible()); assertEquals(2, count("user_book_file_progress"));
    }

    @Test void caseDistinctPathsAreNotGrouped() {
        jdbc.update("UPDATE book_file SET file_name='SHARED.epub' WHERE id=22");
        assertEquals(0, service.scan(1, 0, 20).totalGroups());
    }
    @Test void matchingStoredHashesAreNotEnoughIfThePhysicalFingerprintHasDrifted() {
        jdbc.update("UPDATE book_file SET current_hash='outdated-for-both-records'");
        var preview = preview();
        assertFalse(preview.eligible());
        assertTrue(preview.reasons().stream().anyMatch(reason -> reason.contains("stored fingerprint")));
    }

    @Test void mergeOfOldReceiptCannotRunTwice() {
        var preview = preview(); merge(preview);
        assertThrows(APIException.class, () -> merge(preview)); assertEquals(1, count("catalogue_reconciliation_audit"));
    }
}
