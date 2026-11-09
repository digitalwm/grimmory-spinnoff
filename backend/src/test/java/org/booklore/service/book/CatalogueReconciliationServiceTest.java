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
    private CatalogueQuarantineService quarantine;

    @BeforeEach void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:reconcile_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (String ddl : List.of(
                "CREATE TABLE library(id BIGINT PRIMARY KEY,format_priority VARCHAR(200))",
                "CREATE TABLE library_path(id BIGINT PRIMARY KEY,path VARCHAR(2000))",
                "CREATE TABLE book(id BIGINT PRIMARY KEY,library_id BIGINT,library_path_id BIGINT,metadata_match_score FLOAT,is_physical BOOLEAN,deleted BOOLEAN)",
                "CREATE TABLE book_file(id BIGINT PRIMARY KEY,book_id BIGINT NOT NULL REFERENCES book(id) ON DELETE CASCADE,file_sub_path VARCHAR(512),file_name VARCHAR(1000),current_hash VARCHAR(128),book_type VARCHAR(20) DEFAULT 'EPUB',is_book BOOLEAN DEFAULT true,is_folder_based BOOLEAN DEFAULT false,preferred_primary BOOLEAN DEFAULT false)",
                "CREATE TABLE book_metadata(book_id BIGINT PRIMARY KEY REFERENCES book(id) ON DELETE CASCADE,title VARCHAR(1000),description VARCHAR(5000))",
                "CREATE TABLE author(id BIGINT PRIMARY KEY,name VARCHAR(255))",
                "CREATE TABLE book_metadata_author_mapping(book_id BIGINT REFERENCES book_metadata(book_id) ON DELETE CASCADE,author_id BIGINT REFERENCES author(id),PRIMARY KEY(book_id,author_id))",
                "CREATE TABLE book_shelf_mapping(book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,shelf_id BIGINT,PRIMARY KEY(book_id,shelf_id))",
                "CREATE TABLE user_book_progress(id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,user_id BIGINT,epub_progress VARCHAR(1000),UNIQUE(user_id,book_id))",
                "CREATE TABLE user_book_file_progress(id BIGINT PRIMARY KEY,book_file_id BIGINT REFERENCES book_file(id) ON DELETE CASCADE,user_id BIGINT,position_data VARCHAR(1000),UNIQUE(user_id,book_file_id))",
                "CREATE TABLE book_marks(id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES book(id) ON DELETE CASCADE,user_id BIGINT,title VARCHAR(1000))",
                "CREATE TABLE catalogue_reconciliation_audit(id VARCHAR(36) PRIMARY KEY,user_id BIGINT,library_id BIGINT,target_book_id BIGINT,created_at TIMESTAMP,file_path VARCHAR(2000),file_sha256 VARCHAR(64),before_json CLOB,after_json CLOB)")) jdbc.execute(ddl);
        jdbc.execute("CREATE TABLE catalogue_reconciliation_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO catalogue_reconciliation_lock VALUES(1)");
        jdbc.execute("CREATE TABLE catalogue_quarantine(id VARCHAR(36) PRIMARY KEY,receipt_id VARCHAR(36),library_id BIGINT,original_path VARCHAR(2000),quarantine_path VARCHAR(2000),retained_path VARCHAR(2000),retained_file_id BIGINT,file_sha256 VARCHAR(64),state VARCHAR(32),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,purge_after TIMESTAMP,completed_at TIMESTAMP,detail VARCHAR(4000))");
        jdbc.execute("CREATE TABLE catalogue_duplicate_exclusion(library_id BIGINT,book_low_id BIGINT REFERENCES book(id) ON DELETE CASCADE,book_high_id BIGINT REFERENCES book(id) ON DELETE CASCADE,user_id BIGINT,PRIMARY KEY(library_id,book_low_id,book_high_id))");
        jdbc.update("INSERT INTO library(id) VALUES(1)");
        jdbc.update("INSERT INTO library_path VALUES(1,?)", folder.toString());
        jdbc.update("INSERT INTO book VALUES(1,1,1,80,false,false),(2,1,1,70,false,false)");
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash) VALUES(11,1,'','shared.epub','same-hash'),(22,2,'','shared.epub','same-hash')");
        jdbc.update("INSERT INTO book_metadata VALUES(1,'The Book','Chosen description'),(2,'The Book','Original source description')");
        jdbc.update("INSERT INTO author VALUES(1,'Ada Author')");
        jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(1,1),(2,1)");
        var permissions = new BookLoreUser.UserPermissions(); permissions.setAdmin(true);
        user = BookLoreUser.builder().id(7L).permissions(permissions).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        var proxy = new org.springframework.aop.framework.ProxyFactory(new CatalogueQuarantineService(jdbc));
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        quarantine = (CatalogueQuarantineService) proxy.getProxy();
        service = new CatalogueReconciliationService(jdbc, new CatalogueReconciliationStore(jdbc),
                new ObjectMapper(), mock(AuditService.class), mock(NotificationService.class), quarantine,
                mock(org.booklore.service.monitoring.MonitoringRegistrationService.class));
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

    @Test void aNewOutsideSharedReferenceIsPreservedWithoutExpandingTheSelectedMerge() {
        var preview = preview();
        jdbc.update("INSERT INTO book VALUES(3,1,1,70,false,false)");
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash) VALUES(33,3,'','shared.epub','same-hash')");
        merge(preview);assertEquals(2,count("book"));
        assertEquals(3L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=33",Long.class));
        assertTrue(Files.exists(file));
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

    @Test void missingAdditionalFileRequiresReview() {
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash) VALUES(23,2,'','other.epub','another-hash')");
        assertFalse(preview().eligible()); assertEquals(3, count("book_file"));
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

    @Test void caseDistinctPathsAreStillCandidatesButMustVerifyEachActualFile() {
        jdbc.update("UPDATE book_file SET file_name='SHARED.epub' WHERE id=22");
        assertEquals(1, service.scan(1, 0, 20).totalGroups());
        assertFalse(preview().eligible());
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

    private void mixedGroup() throws Exception {
        Path pdf = folder.resolve("shared.pdf"); Files.move(file, pdf); file = pdf;
        jdbc.update("UPDATE book_file SET file_name='shared.pdf',book_type='PDF'");
        jdbc.update("INSERT INTO book VALUES(3,1,1,60,false,false),(4,1,1,90,false,false)");
        jdbc.update("INSERT INTO book_metadata VALUES(3,'The Book','Third metadata'),(4,'The Book','EPUB metadata')");
        jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(3,1),(4,1)");
        Files.createDirectories(folder.resolve("another"));
        Files.copy(file, folder.resolve("another/shared.pdf"));
        Files.writeString(folder.resolve("different.epub"), "A distinct EPUB format worth retaining");
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash,book_type) VALUES(33,3,'another','shared.pdf',?,'PDF'),(44,4,'','different.epub',?,'EPUB')",
                org.booklore.service.file.FileFingerprint.generateHash(folder.resolve("another/shared.pdf")),
                org.booklore.service.file.FileFingerprint.generateHash(folder.resolve("different.epub")));
    }
    private CatalogueReconciliationService.Preview mixedPreview(long keeper) {return service.preview(1,List.of(1L,2L,3L,4L),keeper);}
    private CatalogueReconciliationService.Receipt apply(CatalogueReconciliationService.Preview preview) {
        return transaction.execute(status -> service.merge(1,preview.members().stream().map(CatalogueReconciliationService.Member::bookId).toList(),
                preview.targetBookId(),preview.token(),true));
    }

    @Test void mixedSharedPdfSeparateIdenticalCopyAndEpubMergeInOneReviewedOperation() throws Exception {
        mixedGroup();
        jdbc.update("INSERT INTO user_book_file_progress VALUES(101,22,7,'pdf-position'),(102,44,7,'epub-position')");
        var preview=mixedPreview(1); assertTrue(preview.eligible(),preview.reasons().toString());
        assertEquals(4,preview.members().size());
        assertEquals(2,preview.files().stream().filter(plan -> plan.action().equals("KEEP")).count());
        assertEquals(1,preview.files().stream().filter(plan -> plan.action().equals("SHARED_REFERENCE")).count());
        assertEquals(1,preview.files().stream().filter(plan -> plan.action().equals("QUARANTINE_COPY")).count());
        var receipt=apply(preview);
        assertEquals(1,count("book")); assertEquals(2,count("book_file"));
        assertEquals(1L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=44",Long.class));
        assertEquals(11L,jdbc.queryForObject("SELECT book_file_id FROM user_book_file_progress WHERE id=101",Long.class));
        assertEquals(44L,jdbc.queryForObject("SELECT book_file_id FROM user_book_file_progress WHERE id=102",Long.class));
        assertTrue(Files.exists(file)); assertTrue(Files.exists(folder.resolve("different.epub")));
        assertFalse(Files.exists(folder.resolve("another/shared.pdf")));
        var entry=quarantine.entries(receipt.id()).getFirst();
        assertEquals("QUARANTINED",entry.get("state"));
        assertArrayEquals(Files.readAllBytes(file),Files.readAllBytes(Path.of(entry.get("quarantine_path"))));
        assertNotNull(entry.get("purge_after")); assertEquals(30,receipt.quarantineRetentionDays());
        try(var input=quarantine.open(entry.get("id")).resource().getInputStream()) {assertArrayEquals(Files.readAllBytes(file),input.readAllBytes());}
    }

    @Test void epubKeeperRetainsPdfAndReassignsAllFilesBeforeDeletingSourceRecords() throws Exception {
        mixedGroup(); var p=mixedPreview(4); assertTrue(p.eligible(),p.reasons().toString()); apply(p);
        assertEquals(1,count("book")); assertEquals(2,count("book_file"));
        assertEquals(4L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=11",Long.class));
        assertEquals("EPUB metadata",jdbc.queryForObject("SELECT description FROM book_metadata WHERE book_id=4",String.class));
    }

    @Test void incompleteSelectionExpandsSharedReferencesAndRequiresConfirmingTheExpandedPreview() throws Exception {
        mixedGroup(); var p=service.preview(1,List.of(1L,4L),1);
        assertEquals(List.of(1L,2L,3L,4L),p.members().stream().map(CatalogueReconciliationService.Member::bookId).toList());
        assertTrue(p.eligible(),p.reasons().toString());
        assertThrows(APIException.class,()->transaction.execute(status->service.merge(1,List.of(1L,4L),1,p.token(),true)));
        apply(p); assertEquals(1,count("book")); assertFalse(Files.exists(folder.resolve("another/shared.pdf")));
    }

    @Test void sourceWithSeveralVerifiedFormatsTransfersEveryUniqueFormat() throws Exception {
        mixedGroup(); jdbc.update("UPDATE book_file SET book_id=2 WHERE id=44");
        jdbc.update("DELETE FROM book_metadata_author_mapping WHERE book_id=4");
        jdbc.update("DELETE FROM book_metadata WHERE book_id=4"); jdbc.update("DELETE FROM book WHERE id=4");
        var p=service.preview(1,List.of(1L,2L,3L),1); assertTrue(p.eligible(),p.reasons().toString()); apply(p);
        assertEquals(1,count("book")); assertEquals(2,count("book_file")); assertTrue(Files.exists(folder.resolve("different.epub")));
    }

    @Test void sameFormatWithDifferentBytesIsRetainedAsAnotherEditionInsteadOfQuarantined() throws Exception {
        mixedGroup(); Files.writeString(folder.resolve("another/shared.pdf"),"A different PDF edition");
        jdbc.update("UPDATE book_file SET current_hash=? WHERE id=33",org.booklore.service.file.FileFingerprint.generateHash(folder.resolve("another/shared.pdf")));
        var p=mixedPreview(1); assertTrue(p.eligible(),p.reasons().toString());
        assertEquals(0,p.files().stream().filter(plan->plan.action().equals("QUARANTINE_COPY")).count()); apply(p);
        assertEquals(3,count("book_file")); assertEquals(0,count("catalogue_quarantine")); assertTrue(Files.exists(folder.resolve("another/shared.pdf")));
    }

    @Test void mixedMergeAuditFailureRestoresMovedCopyAndRollsBackEveryCatalogueChange() throws Exception {
        mixedGroup(); var p=mixedPreview(1); jdbc.execute("DROP TABLE catalogue_reconciliation_audit");
        assertThrows(RuntimeException.class,()->apply(p));
        assertEquals(4,count("book")); assertEquals(4,count("book_file")); assertTrue(Files.exists(folder.resolve("another/shared.pdf")));
        assertEquals("RESTORED",jdbc.queryForObject("SELECT state FROM catalogue_quarantine",String.class));
    }

    @Test void changedSeparateCopyMakesTheWholeMixedPreviewStale() throws Exception {
        mixedGroup(); var p=mixedPreview(1); Files.writeString(folder.resolve("another/shared.pdf"),"Changed copy");
        assertThrows(APIException.class,()->apply(p)); assertEquals(4,count("book")); assertTrue(Files.exists(folder.resolve("another/shared.pdf")));
    }

    @Test void conflictingFileProgressIsBlockedWhileDistinctFormatsCanKeepSeparatePositions() throws Exception {
        mixedGroup(); jdbc.update("INSERT INTO user_book_file_progress VALUES(101,11,7,'pdf-one'),(102,33,7,'pdf-two')");
        var p=mixedPreview(1); assertFalse(p.eligible()); assertThrows(APIException.class,()->apply(p)); assertEquals(4,count("book"));
    }

    @Test void aCopyReferencedByAnotherLibraryStaysInPlaceWhileSelectedRecordsMerge() throws Exception {
        mixedGroup(); jdbc.update("INSERT INTO library(id) VALUES(2)");
        jdbc.update("INSERT INTO book VALUES(9,2,1,0,false,false)");
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash,book_type) VALUES(99,9,'another','shared.pdf',?,'PDF')",org.booklore.service.file.FileFingerprint.generateHash(file));
        var p=mixedPreview(1); assertTrue(p.eligible()); var receipt=apply(p);
        assertEquals(2,count("book")); assertEquals(0,count("catalogue_quarantine"));
        assertTrue(Files.exists(folder.resolve("another/shared.pdf"))); assertEquals(9L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=99",Long.class));
    }

    @Test void candidateSearchFindsAllCopiesAndFormatsWithoutModifyingAnything() throws Exception {
        mixedGroup(); var scan=service.scan(1,0,20,"THE BOOK");
        assertEquals(1,scan.totalGroups()); assertEquals(4,scan.groups().getFirst().members().size());
        assertEquals(0,service.scan(1,0,20,"not this title").totalGroups()); assertEquals(4,count("book")); assertEquals(0,count("catalogue_quarantine"));
    }

    @Test void retentionPurgesOnlyTheExpiredVerifiedCopyWhileTheRegisteredKeeperRemainsIntact() throws Exception {
        mixedGroup(); var receipt=apply(mixedPreview(1)); var entry=quarantine.entries(receipt.id()).getFirst();
        quarantine.maintain(); assertTrue(Files.exists(Path.of(entry.get("quarantine_path"))));
        jdbc.update("UPDATE catalogue_quarantine SET purge_after=TIMESTAMP '2000-01-01 00:00:00'"); quarantine.maintain();
        assertFalse(Files.exists(Path.of(entry.get("quarantine_path")))); assertTrue(Files.exists(file));
        assertEquals("PURGED",quarantine.entries(receipt.id()).getFirst().get("state"));
        assertNotNull(service.receipt(receipt.id()).get("before_json"));
    }

    @Test void retentionPreservesBackupIfTheKeeperIsMissingOrChangedOrUnregistered() throws Exception {
        mixedGroup(); var receipt=apply(mixedPreview(1)); var entry=quarantine.entries(receipt.id()).getFirst();
        jdbc.update("UPDATE catalogue_quarantine SET purge_after=TIMESTAMP '2000-01-01 00:00:00'");
        byte[] original=Files.readAllBytes(file); Files.delete(file); quarantine.maintain(); assertTrue(Files.exists(Path.of(entry.get("quarantine_path"))));
        Files.writeString(file,"Changed keeper"); quarantine.maintain(); assertTrue(Files.exists(Path.of(entry.get("quarantine_path"))));
        Files.write(file,original); jdbc.update("DELETE FROM book_file WHERE id=11"); quarantine.maintain();
        assertEquals("QUARANTINED",quarantine.entries(receipt.id()).getFirst().get("state")); assertTrue(Files.exists(Path.of(entry.get("quarantine_path"))));
    }

    @Test void retentionNeverDeletesAnAlteredQuarantineFile() throws Exception {
        mixedGroup(); var receipt=apply(mixedPreview(1)); var entry=quarantine.entries(receipt.id()).getFirst();
        Path copy=Path.of(entry.get("quarantine_path")); Files.writeString(copy,"Altered backup");
        jdbc.update("UPDATE catalogue_quarantine SET purge_after=TIMESTAMP '2000-01-01 00:00:00'"); quarantine.maintain();
        assertEquals("Altered backup",Files.readString(copy));
        assertTrue(quarantine.entries(receipt.id()).getFirst().get("detail").contains("checksum changed"));
    }

    @Test void retentionZeroKeepsQuarantinedCopiesIndefinitely() throws Exception {
        mixedGroup(); CatalogueQuarantineService raw = org.springframework.test.util.AopTestUtils.getTargetObject(quarantine);
        org.springframework.test.util.ReflectionTestUtils.setField(raw,"retentionDays",0);
        var receipt=apply(mixedPreview(1)); var entry=quarantine.entries(receipt.id()).getFirst(); assertNull(entry.get("purge_after"));
        quarantine.maintain(); assertEquals("QUARANTINED",quarantine.entries(receipt.id()).getFirst().get("state"));
    }

    private CatalogueQuarantineService.Move interruptedMove(String receipt) throws Exception {
        mixedGroup(); String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var move=new CatalogueQuarantineService.Move(folder.resolve("another/shared.pdf").toString(),folder.resolve(".grimmory-quarantine/"+receipt+"/33.quarantined").toString(),file.toString(),11,sha);
        quarantine.prepare(receipt,1,List.of(move)); quarantine.move(List.of(move)); return move;
    }
    @Test void recoveryRestoresAnInterruptedMoveWithoutACommittedReceipt() throws Exception {
        var move=interruptedMove("interrupted"); assertFalse(Files.exists(Path.of(move.originalPath()))); quarantine.maintain();
        assertTrue(Files.exists(Path.of(move.originalPath()))); assertFalse(Files.exists(Path.of(move.quarantinePath())));
        assertEquals("RESTORED",quarantine.entries("interrupted").getFirst().get("state"));
    }
    @Test void recoveryRecognizesTheCommittedReceiptAfterAProcessLostItsCompletionCallback() throws Exception {
        var move=interruptedMove("committed");
        jdbc.update("INSERT INTO catalogue_reconciliation_audit(id,user_id,library_id,target_book_id,file_path,file_sha256,before_json,after_json) VALUES('committed',7,1,1,?,?,'{}','{}')",move.retainedPath(),move.sha256());
        quarantine.maintain(); assertEquals("QUARANTINED",quarantine.entries("committed").getFirst().get("state")); assertTrue(Files.exists(Path.of(move.quarantinePath())));
    }
    @Test void recoveryNeverOverwritesANewFileAtTheOriginalPath() throws Exception {
        var move=interruptedMove("occupied"); Files.writeString(Path.of(move.originalPath()),"A new unrelated file"); quarantine.maintain();
        assertEquals("A new unrelated file",Files.readString(Path.of(move.originalPath()))); assertTrue(Files.exists(Path.of(move.quarantinePath())));
        assertEquals("PREPARED",quarantine.entries("occupied").getFirst().get("state"));
    }

    @Test void networkStorageBlocksQuarantineWithoutWeakeningTheSharedReferenceOnlyWorkflow() throws Exception {
        mixedGroup(); CatalogueQuarantineService raw=org.springframework.test.util.AopTestUtils.getTargetObject(quarantine);
        org.springframework.test.util.ReflectionTestUtils.setField(raw,"diskType","NETWORK");
        var p=mixedPreview(1); assertFalse(p.eligible()); assertTrue(p.reasons().stream().anyMatch(reason->reason.contains("local storage")));
        assertEquals(0,count("catalogue_quarantine")); assertTrue(Files.exists(folder.resolve("another/shared.pdf")));
    }

    @Test void explicitOffsetsDoNotSkipTheNextGroupAfterAPrecedingCandidateWasRemoved() {
        jdbc.update("INSERT INTO book VALUES(3,1,1,80,false,false),(4,1,1,80,false,false),(5,1,1,80,false,false),(6,1,1,80,false,false)");
        jdbc.update("INSERT INTO book_metadata VALUES(3,'Alpha',''),(4,'Alpha',''),(5,'Beta',''),(6,'Beta','')");
        jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(3,1),(4,1),(5,1),(6,1)");
        jdbc.update("INSERT INTO book_file(id,book_id,file_sub_path,file_name,current_hash) VALUES(33,3,'','alpha.epub','hash'),(44,4,'','alpha.epub','hash'),(55,5,'','beta.epub','hash'),(66,6,'','beta.epub','hash')");
        assertEquals(2,service.scan(1,0,2).groups().size());
        jdbc.update("DELETE FROM book WHERE id IN (3,4)");
        var next=service.scan(1,1,2,null,1);
        assertEquals(1,next.offset()); assertEquals(1,next.groups().size());
        assertEquals(List.of(1L,2L),next.groups().getFirst().members().stream().map(CatalogueReconciliationService.Member::bookId).toList());
    }

    private CatalogueReconciliationService.QuickMergeResult quick(List<Long> ids,long target) {
        return transaction.execute(status->service.quickMerge(1,ids,target));
    }
    @Test void quickMergeCommitsAStraightforwardGroupWithItsReceiptAndCompatibleProgress() {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,2,8,'chapter-4')");
        var result=quick(List.of(1L,2L),1);
        assertEquals("MERGED",result.status());assertNotNull(result.receipt());assertNull(result.preview());
        assertEquals(1,count("book"));assertEquals(1,count("catalogue_reconciliation_audit"));
        assertEquals(1L,jdbc.queryForObject("SELECT book_id FROM user_book_progress WHERE id=101",Long.class));
        assertThrows(APIException.class,()->quick(List.of(1L,2L),1));assertEquals(1,count("catalogue_reconciliation_audit"));
    }
    @Test void quickMergeHandlesVerifiedIdenticalCopiesAndDifferentFormatsWithoutMandatoryReview() throws Exception {
        mixedGroup();var result=quick(List.of(1L,2L,3L,4L),1);
        assertEquals("MERGED",result.status());assertEquals(2,count("book_file"));assertEquals(1,count("catalogue_quarantine"));
        assertEquals("QUARANTINED",quarantine.entries(result.receipt().id()).getFirst().get("state"));
    }
    @Test void quickMergeUsesOnlyTheSelectedRecordsWithoutExpandingMatchingTitles() throws Exception {
        mixedGroup();var result=quick(List.of(1L,2L,4L),1);
        assertEquals("MERGED",result.status());assertNull(result.preview());assertEquals(List.of(2L,4L),result.receipt().removedBookIds());
        assertEquals(2,count("book"));assertEquals(0,count("catalogue_quarantine"));assertTrue(Files.exists(folder.resolve("another/shared.pdf")));
    }
    @Test void quickMergeKeepsDifferentFilesInTheSameFormatAndUsesTheSelectedTarget() throws Exception {
        mixedGroup();Files.writeString(folder.resolve("another/shared.pdf"),"A different PDF edition");
        jdbc.update("UPDATE book_file SET current_hash=? WHERE id=33",org.booklore.service.file.FileFingerprint.generateHash(folder.resolve("another/shared.pdf")));
        var result=quick(List.of(1L,2L,3L,4L),3);assertEquals("MERGED",result.status());
        assertEquals(3,count("book_file"));assertEquals(33L,jdbc.queryForObject("SELECT id FROM book_file WHERE preferred_primary=true",Long.class));
        assertEquals(1,count("catalogue_reconciliation_audit"));assertEquals(0,count("catalogue_quarantine"));
    }
    @Test void quickMergeUsesChosenMetadataDespiteLockedSourceFieldsAndArchivesTheOriginals() {
        jdbc.execute("ALTER TABLE book_metadata ADD COLUMN description_locked BOOLEAN DEFAULT false");
        jdbc.execute("ALTER TABLE book_metadata ADD COLUMN cover_locked BOOLEAN DEFAULT false");
        jdbc.update("UPDATE book_metadata SET description_locked=true,cover_locked=true WHERE book_id=2");
        var result=quick(List.of(1L,2L),1);assertEquals("MERGED",result.status());
        assertEquals("Chosen description",jdbc.queryForObject("SELECT description FROM book_metadata",String.class));
        assertTrue(service.receipt(result.receipt().id()).get("before_json").toString().contains("Original source description"));
    }
    @Test void quickMergeUsesTargetLanguageAndSeriesDespiteConflictingSourceValues() throws Exception {
        mixedGroup();jdbc.execute("ALTER TABLE book_metadata ADD COLUMN language VARCHAR(20) DEFAULT 'en'");
        jdbc.execute("ALTER TABLE book_metadata ADD COLUMN series_name VARCHAR(100) DEFAULT 'Chosen series'");
        jdbc.update("UPDATE book_metadata SET language='ro',series_name='Other series' WHERE book_id=4");
        jdbc.update("INSERT INTO author VALUES(2,'Additional Narrator')");jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(4,2)");
        var result=quick(List.of(1L,2L,3L,4L),1);assertEquals("MERGED",result.status());assertEquals(1,count("book"));
        assertEquals("en",jdbc.queryForObject("SELECT language FROM book_metadata",String.class));
        assertEquals("Chosen series",jdbc.queryForObject("SELECT series_name FROM book_metadata",String.class));
    }
    @Test void quickMergeLeavesOutsideSharedReferencesAndTheirFileUntouched() throws Exception {
        mixedGroup();var result=quick(List.of(1L,3L),3);assertEquals("MERGED",result.status());
        assertEquals(3,count("book"));assertEquals(0,count("catalogue_quarantine"));assertTrue(Files.exists(file));
        assertEquals(2L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=22",Long.class));
        assertEquals(33L,jdbc.queryForObject("SELECT id FROM book_file WHERE preferred_primary=true",Long.class));
    }
    @Test void quickMergeRetainsTheTargetsExistingFormatPriorityAndPrimaryChoice() throws Exception {
        mixedGroup();jdbc.update("UPDATE library SET format_priority='[\"EPUB\",\"PDF\"]'");
        jdbc.update("UPDATE book_file SET book_id=1 WHERE id=44");
        var result=quick(List.of(1L,2L,3L),1);assertEquals("MERGED",result.status());
        assertEquals(44L,jdbc.queryForObject("SELECT id FROM book_file WHERE preferred_primary=true",Long.class));
    }

    @Test void quickMergeNeverBypassesConflictingReadingProgressOrAdministratorChecks() {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,1,8,'one'),(102,2,8,'two')");
        var result=quick(List.of(1L,2L),1);assertEquals("REVIEW_REQUIRED",result.status());assertFalse(result.preview().eligible());
        assertEquals(2,count("book"));assertEquals(0,count("catalogue_reconciliation_audit"));
        user.getPermissions().setAdmin(false);assertThrows(APIException.class,()->quick(List.of(1L,2L),1));
    }
    @Test void quickMergeAllowsCompatibleSubtitleDifferencesWithoutAnExtraJudgmentCall() {
        jdbc.update("UPDATE book_metadata SET title='The Book: A Subtitle' WHERE book_id=2");
        var result=quick(List.of(1L,2L),1);assertEquals("MERGED",result.status());
        assertEquals(1,count("book"));assertEquals(1,count("catalogue_reconciliation_audit"));
    }

    @Test void quickMergeRejectsMetadataChangedBetweenDiscoveryAndItsLockedSnapshot() {
        var changingStore=spy(new CatalogueReconciliationStore(jdbc));
        doAnswer(call->{jdbc.update("UPDATE book_metadata SET title='A different work' WHERE book_id=2");return call.callRealMethod();})
                .when(changingStore).snapshot(any(),anyList(),anyList(),eq(true));
        var changingService=new CatalogueReconciliationService(jdbc,changingStore,new ObjectMapper(),mock(AuditService.class),mock(NotificationService.class),quarantine,
                mock(org.booklore.service.monitoring.MonitoringRegistrationService.class));
        assertThrows(APIException.class,()->transaction.execute(status->changingService.quickMerge(1,List.of(1L,2L),1)));
        assertEquals(2,count("book"));assertEquals(0,count("catalogue_reconciliation_audit"));
    }

    @Test void inlineIdentityDecisionMergesSelectedTargetAndArchivesHumanConfirmation() {
        jdbc.update("INSERT INTO author VALUES(2,'Another author')");
        jdbc.update("UPDATE book_metadata_author_mapping SET author_id=2 WHERE book_id=2");
        assertEquals("REVIEW_REQUIRED",quick(List.of(1L,2L),2).status());
        assertThrows(APIException.class,()->transaction.execute(status->service.confirmIdentityAndMerge(1,List.of(1L,2L),2,false)));
        var result=transaction.execute(status->service.confirmIdentityAndMerge(1,List.of(1L,2L),2,true));
        assertEquals("MERGED",result.status());assertEquals(2,result.receipt().targetBookId());
        assertEquals(1,count("book"));
        assertTrue(jdbc.queryForObject("SELECT after_json FROM catalogue_reconciliation_audit",String.class).contains("\"_human_identity_confirmed\":true"));
    }
    @Test void inlineIdentityDecisionStillBlocksConflictingReadingData() {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,1,8,'one'),(102,2,8,'two')");
        var result=transaction.execute(status->service.confirmIdentityAndMerge(1,List.of(1L,2L),1,true));
        assertEquals("REVIEW_REQUIRED",result.status());assertTrue(result.preview().identityConfirmed());
        assertEquals(2,count("book"));assertEquals(0,count("catalogue_reconciliation_audit"));
    }

    @Test void humanIdentityDecisionAllowsBenAndBenjaminWithSelectedMetadataAndFile() {
        jdbc.update("UPDATE book_metadata SET title='The Rest of Our Lives'");
        jdbc.update("UPDATE author SET name='Ben Markovits' WHERE id=1");
        jdbc.update("INSERT INTO author VALUES(2,'Benjamin Markovits')");
        jdbc.update("UPDATE book_metadata_author_mapping SET author_id=2 WHERE book_id=2");
        var blocked=service.preview(1,List.of(1L,2L),2,false);assertFalse(blocked.eligible());
        assertEquals("REVIEW_REQUIRED",quick(List.of(1L,2L),2).status());
        var decision=service.preview(1,List.of(1L,2L),2,true);assertTrue(decision.eligible());assertTrue(decision.identityConfirmed());
        assertThrows(APIException.class,()->transaction.execute(status->service.merge(1,List.of(1L,2L),2,decision.token(),true,false)));
        var receipt=transaction.execute(status->service.merge(1,List.of(1L,2L),2,decision.token(),true,true));
        assertEquals(2,receipt.targetBookId());assertEquals(1,count("book"));
    }
    @Test void humanIdentityDecisionCannotBypassConflictingReadingData() {
        jdbc.update("INSERT INTO user_book_progress VALUES(101,1,8,'one'),(102,2,8,'two')");
        var decision=service.preview(1,List.of(1L,2L),1,true);assertFalse(decision.eligible());
        assertThrows(APIException.class,()->transaction.execute(status->service.merge(1,List.of(1L,2L),1,decision.token(),true,true)));
        assertEquals(2,count("book"));
    }
    @Test void exclusionPersistsAcrossScansAndDoesNotModifyBooksOrFiles() {
        assertEquals(1,service.scan(1,0,20).totalGroups());
        transaction.executeWithoutResult(status->service.exclude(1,List.of(1L,2L)));
        transaction.executeWithoutResult(status->service.exclude(1,List.of(2L,1L)));
        assertEquals(0,service.scan(1,0,20).totalGroups());assertEquals(1,count("catalogue_duplicate_exclusion"));
        assertEquals(2,count("book"));assertEquals(2,count("book_file"));assertEquals(0,count("catalogue_reconciliation_audit"));
    }

    @Test void separatingOneBookPreservesOtherPairsInALargerGroup() throws Exception {
        mixedGroup();transaction.executeWithoutResult(status->service.exclude(1,List.of(1L,2L,3L,4L),4L));
        assertEquals(3,count("catalogue_duplicate_exclusion"));
        var scan=service.scan(1,0,20);assertEquals(1,scan.totalGroups());
        assertEquals(List.of(1L,2L,3L),scan.groups().getFirst().members().stream().map(CatalogueReconciliationService.Member::bookId).toList());
        assertEquals(4,count("book"));
    }

    @Test void laterExplicitMergeOfSeparatedRecordsArchivesAndRemovesTheirPairDecision() {
        transaction.executeWithoutResult(status->service.exclude(1,List.of(1L,2L)));
        var decision=service.preview(1,List.of(1L,2L),1,true);assertTrue(decision.eligible(),decision.reasons().toString());
        var receipt=transaction.execute(status->service.merge(1,List.of(1L,2L),1,decision.token(),true,true));
        assertEquals(0,count("catalogue_duplicate_exclusion"));
        assertTrue(service.receipt(receipt.id()).get("before_json").toString().contains("book_low_id"));
    }

    @Test void reviewedMergeOfRemainingBooksDoesNotReintroduceASeparatedMember() throws Exception {
        mixedGroup();transaction.executeWithoutResult(status->service.exclude(1,List.of(1L,2L,3L,4L),4L));
        var preview=service.preview(1,List.of(1L,2L,3L),1,false);assertTrue(preview.eligible(),preview.reasons().toString());
        var receipt=transaction.execute(status->service.merge(1,List.of(1L,2L,3L),1,preview.token(),true,false));
        assertEquals(List.of(2L,3L),receipt.removedBookIds());assertEquals(2,count("book"));
        assertEquals(4L,jdbc.queryForObject("SELECT book_id FROM book_file WHERE id=44",Long.class));
    }

}
