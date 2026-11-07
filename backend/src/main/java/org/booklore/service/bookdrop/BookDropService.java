package org.booklore.service.bookdrop;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookdropFileMapper;
import org.booklore.model.FileProcessResult;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookdropFile;
import org.booklore.model.dto.BookdropFileNotification;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.model.dto.response.BookdropFileResult;
import org.booklore.model.dto.response.BookdropFinalizeResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.metadata.MetadataIdentityMatcher;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
@Service
@Transactional
public class BookDropService {

    private final BookdropFileRepository bookdropFileRepository;
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookdropMonitoringService bookdropMonitoringService;
    private final NotificationService notificationService;
    private final MetadataRefreshService metadataRefreshService;
    private final BookdropNotificationService bookdropNotificationService;
    private final BookFileProcessorRegistry processorRegistry;
    private final AppProperties appProperties;
    private final BookdropFileMapper mapper;
    private final ObjectMapper objectMapper;
    private final FileMovingHelper fileMovingHelper;
    private final ApplicationEventPublisher eventPublisher;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final BookdropFinalizeProgressService finalizeProgressService;
    private final PlatformTransactionManager transactionManager;
    private final ThreadLocal<String> activeProgressId = new ThreadLocal<>();
    private final AtomicBoolean operationInProgress = new AtomicBoolean();

    private static final int CHUNK_SIZE = 100;

    public BookdropFileNotification getFileNotificationSummary() {
        long pendingCount = bookdropFileRepository.countByStatus(BookdropFileEntity.Status.PENDING_REVIEW);
        long totalCount = bookdropFileRepository.count();
        return new BookdropFileNotification((int) pendingCount, (int) totalCount, Instant.now().toString());
    }

    public Page<BookdropFile> getFilesByStatus(String status, Pageable pageable) {
        Pageable stablePageable = withStableBookdropSort(pageable);
        if ("pending".equalsIgnoreCase(status)) {
            return bookdropFileRepository.findAllByStatus(BookdropFileEntity.Status.PENDING_REVIEW, stablePageable).map(mapper::toDto);
        } else {
            return bookdropFileRepository.findAll(stablePageable).map(mapper::toDto);
        }
    }

    private Pageable withStableBookdropSort(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    public Resource getBookdropCover(long bookdropId) {
        String coverPath = Paths.get(appProperties.getPathConfig(), "bookdrop_temp", bookdropId + ".jpg").toString();
        File coverFile = new File(coverPath);
        if (coverFile.exists() && coverFile.isFile()) {
            return new FileSystemResource(coverFile.toPath());
        } else {
            return null;
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookdropFinalizeResult finalizeImport(BookdropFinalizeRequest request) {
        beginOperation();
        try {
            activeProgressId.set(request.getProgressId());
            finalizeProgressService.update(request.getProgressId(), "Preparing import", 0, 0, 0, 0, null, false);
            bookdropMonitoringService.pauseMonitoring();
            return processFinalizationRequest(request);
        } finally {
            endOperation();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void discardSelectedFiles(boolean selectAll, List<Long> excludedIds, List<Long> selectedIds) {
        discardSelectedFiles(selectAll, excludedIds, selectedIds, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void discardSelectedFiles(boolean selectAll, List<Long> excludedIds, List<Long> selectedIds, String progressId) {
        beginOperation();
        AtomicInteger deletedFiles = new AtomicInteger();
        AtomicInteger deletedDirs = new AtomicInteger();
        AtomicInteger deletedCovers = new AtomicInteger();

        try {
            activeProgressId.set(progressId);
            bookdropMonitoringService.pauseMonitoring();
            Path bookdropPath = Path.of(appProperties.getBookdropFolder());
            if (!Files.exists(bookdropPath)) {
                log.info("Bookdrop folder does not exist: {}", bookdropPath);
                finalizeProgressService.update(progressId, "Complete", 0, 0, 0, 0, null, true);
                return;
            }

            List<BookdropFileEntity> filesToDelete = getFilesToDelete(selectAll, excludedIds, selectedIds);
            finalizeProgressService.update(progressId, "Deleting selected Bookdrop files", 0, filesToDelete.size(), 0, 0, null, false);
            deleteFilesAndCovers(filesToDelete, deletedFiles, deletedCovers, progressId);
            finalizeProgressService.update(progressId, "Cleaning up empty folders", filesToDelete.size(), filesToDelete.size(), filesToDelete.size(), 0, null, false);
            deleteEmptyDirectories(bookdropPath, deletedDirs);

            bookdropFileRepository.deleteAllById(filesToDelete.stream().map(BookdropFileEntity::getId).toList());
            log.info("Deleted {} bookdrop DB entries", filesToDelete.size());
            finalizeProgressService.update(progressId, "Complete", filesToDelete.size(), filesToDelete.size(), filesToDelete.size(), 0, null, true);

            bookdropNotificationService.sendBookdropFileSummaryNotification();
            log.info("Bookdrop cleanup summary: deleted {} files, {} folders, {} DB entries, {} covers",
                    deletedFiles.get(), deletedDirs.get(), filesToDelete.size(), deletedCovers.get());

        } finally {
            endOperation();
        }
    }

    private void beginOperation() {
        if (!operationInProgress.compareAndSet(false, true)) {
            throw ApiError.CONFLICT.createException("A BookDrop import or deletion is already running. Wait for it to finish before starting another operation.");
        }
    }

    private void endOperation() {
        try {
            bookdropMonitoringService.resumeMonitoring();
        } finally {
            activeProgressId.remove();
            operationInProgress.set(false);
            log.info("Bookdrop monitoring resumed");
        }
    }

    private BookdropFinalizeResult processFinalizationRequest(BookdropFinalizeRequest request) {
        BookdropFinalizeResult results = BookdropFinalizeResult.builder()
                .processedAt(Instant.now())
                .totalFiles(0)
                .successfullyImported(0)
                .failed(0)
                .build();

        Long defaultLibraryId = request.getDefaultLibraryId();
        Long defaultPathId = request.getDefaultPathId();
        Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> metadataById = getMetadataMap(request);

        AtomicInteger failedCount = new AtomicInteger();
        AtomicInteger totalFilesProcessed = new AtomicInteger();

        log.info("Starting finalizeImport: selectAll={}, provided file count={}, defaultLibraryId={}, defaultPathId={}", request.getSelectAll(), metadataById.size(), defaultLibraryId, defaultPathId);

        if (Boolean.TRUE.equals(request.getSelectAll())) {
            processAllFiles(request, metadataById, defaultLibraryId, defaultPathId, results, failedCount, totalFilesProcessed);
        } else {
            processSelectedFiles(request, metadataById, defaultLibraryId, defaultPathId, results, failedCount, totalFilesProcessed);
        }

        updateFinalResults(results, totalFilesProcessed, failedCount);
        finalizeProgressService.update(request.getProgressId(), "Complete", totalFilesProcessed.get(), totalFilesProcessed.get(), results.getSuccessfullyImported(), results.getFailed(), null, true);
        return results;
    }

    private Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> getMetadataMap(BookdropFinalizeRequest request) {
        return Optional.ofNullable(request.getFiles())
                .orElse(List.of())
                .stream()
                .collect(Collectors.toMap(BookdropFinalizeRequest.BookdropFinalizeFile::getFileId, Function.identity()));
    }

    private void processAllFiles(BookdropFinalizeRequest request,
                                 Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> metadataById,
                                 Long defaultLibraryId,
                                 Long defaultPathId,
                                 BookdropFinalizeResult results,
                                 AtomicInteger failedCount,
                                 AtomicInteger totalFilesProcessed) {
        List<Long> excludedIds = Optional.ofNullable(request.getExcludedIds()).orElse(List.of());
        List<Long> allIds;
        if (excludedIds.isEmpty()) {
            allIds = bookdropFileRepository.findAllIds();
        } else {
            allIds = bookdropFileRepository.findAllExcludingIdsFlat(excludedIds);
        }
        log.info("SelectAll: Total files to finalize (after exclusions): {}, Excluded IDs: {}", allIds.size(), excludedIds);

        processFileChunks(allIds, metadataById, defaultLibraryId, defaultPathId, results, failedCount, totalFilesProcessed);
    }

    private void processSelectedFiles(BookdropFinalizeRequest request,
                                      Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> metadataById,
                                      Long defaultLibraryId,
                                      Long defaultPathId,
                                      BookdropFinalizeResult results,
                                      AtomicInteger failedCount,
                                      AtomicInteger totalFilesProcessed) {
        List<Long> ids = Optional.ofNullable(request.getFiles())
                .orElse(List.of())
                .stream()
                .map(BookdropFinalizeRequest.BookdropFinalizeFile::getFileId)
                .toList();

        log.info("Processing {} manually selected files in chunks of {}. File IDs: {}", ids.size(), CHUNK_SIZE, ids);
        processFileChunks(ids, metadataById, defaultLibraryId, defaultPathId, results, failedCount, totalFilesProcessed);
    }

    private void processFileChunks(List<Long> ids,
                                   Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> metadataById,
                                   Long defaultLibraryId,
                                   Long defaultPathId,
                                   BookdropFinalizeResult results,
                                   AtomicInteger failedCount,
                                   AtomicInteger totalFilesProcessed) {

        int total = ids.size();
        int completed = 0;
        int succeeded = 0;
        int failed = 0;
        TransactionTemplate fileTransaction = new TransactionTemplate(transactionManager);
        fileTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        Set<Long> affectedLibraries = collectAffectedLibraries(ids, metadataById, defaultLibraryId, defaultPathId);
        affectedLibraries.forEach(monitoringRegistrationService::suppressLibraryEvents);
        finalizeProgressService.update(activeProgressId.get(), "Moving books into their libraries", 0, total, 0, 0, null, false);
        try {
            for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
                int end = Math.min(i + CHUNK_SIZE, ids.size());
                List<Long> chunk = ids.subList(i, end);

                log.info("Processing chunk {}/{} ({} files): IDs={}", (i / CHUNK_SIZE + 1), (int) Math.ceil((double) ids.size() / CHUNK_SIZE), chunk.size(), chunk);

                List<BookdropFileEntity> chunkFiles = bookdropFileRepository.findAllById(chunk);
                Map<Long, BookdropFileEntity> fileMap = chunkFiles.stream().collect(Collectors.toMap(BookdropFileEntity::getId, Function.identity()));

                for (Long id : chunk) {
                    BookdropFileEntity file = fileMap.get(id);
                    if (file == null) {
                    log.warn("File ID {} missing in DB during finalizeImport chunk processing", id);
                    results.getResults().add(failureResult(id, "Unknown file", "File was not found in the Bookdrop review list"));
                    failedCount.incrementAndGet();
                    totalFilesProcessed.incrementAndGet();
                    completed++;
                    failed++;
                    finalizeProgressService.update(activeProgressId.get(), "Moving books into their libraries", completed, total, succeeded, failed, "Bookdrop entry " + id, false);
                        continue;
                    }
                    finalizeProgressService.update(activeProgressId.get(), "Moving books into their libraries", completed, total, succeeded, failed, file.getFileName(), false);
                    int failuresBefore = failedCount.get();
                    int resultCountBefore = results.getResults().size();
                    try {
                        fileTransaction.executeWithoutResult(status -> {
                            processFile(file, metadataById.get(id), defaultLibraryId, defaultPathId, results, failedCount);
                            if (failedCount.get() > failuresBefore) status.setRollbackOnly();
                        });
                    } catch (Exception e) {
                        while (results.getResults().size() > resultCountBefore) {
                            results.getResults().remove(results.getResults().size() - 1);
                        }
                        if (failedCount.get() == failuresBefore) failedCount.incrementAndGet();
                        results.getResults().add(failureResult(id, file.getFileName(), "Import transaction failed: " + e.getMessage()));
                    }
                    totalFilesProcessed.incrementAndGet();
                    completed++;
                    if (failedCount.get() == failuresBefore) succeeded++; else failed++;
                    finalizeProgressService.update(activeProgressId.get(), "Moving books into their libraries", completed, total, succeeded, failed, file.getFileName(), false);
                }
            }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        affectedLibraries.forEach(monitoringRegistrationService::resumeLibraryEvents);
                    }
                });
            } else {
                affectedLibraries.forEach(monitoringRegistrationService::resumeLibraryEvents);
            }
        }
    }

    private void updateFinalResults(BookdropFinalizeResult results, AtomicInteger totalFilesProcessed, AtomicInteger failedCount) {
        results.setTotalFiles(totalFilesProcessed.get());
        results.setFailed(failedCount.get());
        results.setSuccessfullyImported(totalFilesProcessed.get() - failedCount.get());

        log.info("Finalization complete. Success: {}, Failed: {}, Total processed: {}",
                results.getSuccessfullyImported(),
                results.getFailed(),
                results.getTotalFiles());
    }

    private void processFile(BookdropFileEntity fileEntity,
                             BookdropFinalizeRequest.BookdropFinalizeFile fileReq,
                             Long defaultLibraryId,
                             Long defaultPathId,
                             BookdropFinalizeResult results,
                             AtomicInteger failedCount) {
        try {
            FileProcessingContext context = prepareFileProcessingContext(fileEntity, fileReq, defaultLibraryId, defaultPathId);
            BookdropFileResult result = moveFile(context.libraryId, context.pathId, context.metadata, fileEntity);

            results.getResults().add(result);
            if (!result.isSuccess()) {
                log.warn("Finalization failed (non-exception) for file id={}, name={}, message={}", fileEntity.getId(), fileEntity.getFileName(), result.getMessage());
                failedCount.incrementAndGet();
            } else {
                log.info("Successfully finalized file id={}, name={}", fileEntity.getId(), fileEntity.getFileName());
            }

        } catch (Exception e) {
            failedCount.incrementAndGet();
            String msg = String.format("Error finalizing file [id=%s, name=%s]: %s", fileEntity.getId(), fileEntity.getFileName(), e.getMessage());
            log.error(msg, e);
            notificationService.sendMessage(Topic.LOG, msg);
            results.getResults().add(failureResult(fileEntity.getId(), fileEntity.getFileName(), e.getMessage()));
        }
    }

    private FileProcessingContext prepareFileProcessingContext(BookdropFileEntity fileEntity,
                                                               BookdropFinalizeRequest.BookdropFinalizeFile fileReq,
                                                               Long defaultLibraryId,
                                                               Long defaultPathId) throws Exception {
        Long libraryId;
        Long pathId;
        BookMetadata metadata;

        if (fileReq != null) {
            libraryId = fileReq.getLibraryId() != null ? fileReq.getLibraryId() : defaultLibraryId;
            pathId = fileReq.getPathId() != null ? fileReq.getPathId() : defaultPathId;
            metadata = fileReq.getMetadata();
            log.debug("Processing fileId={}, fileName={} with provided metadata, libraryId={}, pathId={}", fileEntity.getId(), fileEntity.getFileName(), libraryId, pathId);
        } else {
            if (defaultLibraryId == null || defaultPathId == null) {
                log.warn("Missing default metadata for fileId={}", fileEntity.getId());
                throw ApiError.GENERIC_BAD_REQUEST.createException("Missing metadata and defaults for fileId=" + fileEntity.getId());
            }

            BookMetadata original = objectMapper.readValue(fileEntity.getOriginalMetadata(), BookMetadata.class);
            metadata = fileEntity.getFetchedMetadata() != null
                    ? objectMapper.readValue(fileEntity.getFetchedMetadata(), BookMetadata.class)
                    : original;
            if (fileEntity.getFetchedMetadata() != null && !MetadataIdentityMatcher.matches(original, metadata)) {
                log.warn("Ignoring previously fetched mismatched metadata during BookDrop import for file id={}; using embedded metadata", fileEntity.getId());
                metadata = original;
            }

            libraryId = defaultLibraryId;
            pathId = defaultPathId;
            log.debug("Processing fileId={}, fileName={} with default metadata, libraryId={}, pathId={}", fileEntity.getId(), fileEntity.getFileName(), libraryId, pathId);
        }

        return new FileProcessingContext(libraryId, pathId, metadata);
    }

    private BookdropFileResult moveFile(long libraryId, long pathId, BookMetadata metadata, BookdropFileEntity bookdropFile) {
        LibraryEntity library = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        LibraryPathEntity path = library.getLibraryPaths().stream()
                .filter(p -> p.getId().equals(pathId))
                .findFirst()
                .orElseThrow(() -> ApiError.INVALID_LIBRARY_PATH.createException(libraryId));

        BookFileType fileType = BookFileExtension.fromFileName(bookdropFile.getFileName())
                .map(BookFileExtension::getType)
                .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));

        if (!isFormatAllowed(library, fileType)) {
            return failureResult(bookdropFile.getId(), bookdropFile.getFileName(),
                    "Format '" + fileType.name() + "' is not allowed in library '" + library.getName() + "'");
        }

        String filePattern = fileMovingHelper.getFileNamingPattern(library);
        Path source = Path.of(bookdropFile.getFilePath());
        Path target = fileMovingHelper.generateNewFilePath(path.getPath(), metadata, filePattern, bookdropFile.getFilePath());
        File targetFile = target.toFile();

        log.debug("Preparing to move file id={}, name={}, source={}, target={}, library={}, path={}", bookdropFile.getId(), bookdropFile.getFileName(), source, target, library.getName(), path.getPath());

        if (!Files.exists(source)) {
            if (Files.isRegularFile(target)) {
                log.warn("Bookdrop source is missing but the moved target exists; recovering import for id={} at {}", bookdropFile.getId(), target);
                try {
                    BookdropFileResult recovered = processMovedFile(bookdropFile, targetFile, library, path, metadata);
                    if (recovered.isSuccess()) return recovered;
                } catch (Exception e) {
                    log.error("Failed to recover previously moved Bookdrop file id={} from {}", bookdropFile.getId(), target, e);
                }
                return failureResult(bookdropFile.getId(), targetFile.getName(), "The file was already moved, but Grimmory could not finish registering it. The original entry has been kept for retry.");
            }
            bookdropFileRepository.deleteById(bookdropFile.getId());
            log.warn("Source file [id={}] not found at '{}'. Deleting entry from DB.", bookdropFile.getId(), source);
            bookdropNotificationService.sendBookdropFileSummaryNotification();
            return failureResult(bookdropFile.getId(), targetFile.getName(), "Source file does not exist in bookdrop folder");
        }

        if (targetFile.exists()) {
            log.warn("Target file already exists: id={}, name={}, target={}", bookdropFile.getId(), bookdropFile.getFileName(), target);
            return failureResult(bookdropFile.getId(), targetFile.getName(), "File already exists in the library '" + library.getName() + "'");
        }

        return performFileMove(bookdropFile, source, target, library, path, metadata);
    }

    private BookdropFileResult performFileMove(BookdropFileEntity bookdropFile, Path source, Path target, LibraryEntity library, LibraryPathEntity path, BookMetadata metadata) {
        try {
            Files.createDirectories(target.getParent());

            // We used to use `Files.copy` to copy from `/bookdrop` to tmp, then used `Files.move`
            // to get the file to its target destination.  However, there seemed to be a bug
            // that caused moves between filesystems to end up with a file at the target
            // with 0 bytes.
            //
            // Instead, we are using `Files.copy` directly
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            log.info("Copied file id={}, name={} from '{}' to '{}'", bookdropFile.getId(), bookdropFile.getFileName(), source, target);

            BookdropFileResult result;
            try {
                result = processMovedFile(bookdropFile, target.toFile(), library, path, metadata);
            } catch (Exception e) {
                cleanupTargetFile(target, bookdropFile.getId(), "processing exception");
                return failureResult(bookdropFile.getId(), bookdropFile.getFileName(), "Processing failed: " + e.getMessage());
            }

            if (result.isSuccess()) {
                try {
                    Files.delete(source);
                    log.info("Successfully deleted source file '{}' after successful import for file id={}", source, bookdropFile.getId());
                } catch (IOException e) {
                    log.warn("Failed to delete source file '{}' after successful import for file id={}: {}", source, bookdropFile.getId(), e.getMessage());
                    cleanupTargetFile(target, bookdropFile.getId(), "BookDrop source could not be removed");
                    return failureResult(bookdropFile.getId(), bookdropFile.getFileName(),
                            "Import rolled back because the source file could not be removed from BookDrop: " + e.getMessage());
                }
            } else {
                cleanupTargetFile(target, bookdropFile.getId(), "logical failure");
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to move file id={}, name={} from '{}' to '{}': {}", bookdropFile.getId(), bookdropFile.getFileName(), source, target, e.getMessage(), e);
            cleanupFailedMove(target);
            return failureResult(bookdropFile.getId(), bookdropFile.getFileName(), "Failed to move file: " + e.getMessage());
        }
    }

    private BookdropFileResult processMovedFile(BookdropFileEntity bookdropFile,
                                                File targetFile,
                                                LibraryEntity library,
                                                LibraryPathEntity path,
                                                BookMetadata metadata) {
        FileProcessResult fileProcessResult = processFileInLibrary(targetFile.getName(), library, path, targetFile,
                BookFileExtension.fromFileName(bookdropFile.getFileName())
                        .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"))
                        .getType());

        // Post-processing runs in the same transaction as processFile (which uses
        // PROPAGATION_REQUIRED and joins this TX).  The book entity is flushed but
        // not committed a REQUIRES_NEW here would start a separate TX whose
        // REPEATABLE_READ snapshot cannot see the uncommitted book, causing
        // "Book ID missing after import".
        Long bookId = fileProcessResult.getBook().getId();
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("Book ID missing after import"));

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(metadata.getThumbnailUrl() != null)
                .mergeCategories(false)
                .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                .mergeMoods(true)
                .mergeTags(true)
                .build();

        metadataRefreshService.updateBookMetadata(context);
        eventPublisher.publishEvent(new BookAddedEvent(fileProcessResult.getBook()));

        notificationService.sendMessage(Topic.BOOK_ADD, fileProcessResult.getBook());

        cleanupBookdropData(bookdropFile);

        log.info("File import completed: id={}, name={}, library={}, path={}", bookdropFile.getId(), targetFile.getName(), library.getName(), path.getPath());

        return BookdropFileResult.builder()
                .fileId(bookdropFile.getId())
                .fileName(targetFile.getName())
                .message("File successfully imported into the '" + library.getName() + "' library from the Bookdrop folder")
                .success(true)
                .build();
    }

    private void cleanupBookdropData(BookdropFileEntity bookdropFile) {
        bookdropFileRepository.deleteById(bookdropFile.getId());
        bookdropNotificationService.sendBookdropFileSummaryNotification();

        Path cachedCoverPath = Paths.get(appProperties.getPathConfig(), "bookdrop_temp", bookdropFile.getId() + ".jpg");
        if (Files.exists(cachedCoverPath)) {
            try {
                Files.delete(cachedCoverPath);
                log.debug("Deleted cached cover image for bookdropId={}", bookdropFile.getId());
            } catch (IOException e) {
                log.warn("Failed to delete cached cover image for bookdropId={}: {}", bookdropFile.getId(), e.getMessage());
            }
        }
    }

    private void cleanupFailedMove(Path target) {
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Cleaned up partially created target file: {}", target);
            } else {
                log.debug("No partially created target file to cleanup: {}", target);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup partially created target file: {}: {}", target, e.getMessage());
        }
    }

    private void cleanupTargetFile(Path target, Long fileId, String reason) {
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Cleaned up target file '{}' after {} for file id={}", target, reason, fileId);
            } else {
                log.debug("Target file '{}' not present, nothing to cleanup for file id={}", target, fileId);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup target file '{}' after {} for file id={}: {}", 
                    target, reason, fileId, e.getMessage());
        }
    }

    private FileProcessResult processFileInLibrary(String fileName, LibraryEntity library, LibraryPathEntity path, File file, BookFileType type) {
        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(path)
                .fileSubPath(FileUtils.getRelativeSubPath(path.getPath(), file.toPath()))
                .bookFileType(type)
                .fileName(fileName)
                .build();

        BookFileProcessor processor = processorRegistry.getProcessorOrThrow(type);
        return processor.processFile(libraryFile);
    }

    private List<BookdropFileEntity> getFilesToDelete(boolean selectAll, List<Long> excludedIds, List<Long> selectedIds) {
        if (selectAll) {
            List<BookdropFileEntity> filesToDelete = bookdropFileRepository.findAll().stream()
                    .filter(f -> excludedIds == null || !excludedIds.contains(f.getId()))
                    .toList();
            log.info("Discarding all files except excluded IDs: {}", excludedIds);
            return filesToDelete;
        } else {
            List<BookdropFileEntity> filesToDelete = bookdropFileRepository.findAllById(selectedIds == null ? List.of() : selectedIds);
            log.info("Discarding selected files: {}", selectedIds);
            return filesToDelete;
        }
    }

    private void deleteFilesAndCovers(List<BookdropFileEntity> filesToDelete, AtomicInteger deletedFiles, AtomicInteger deletedCovers, String progressId) {
        int processed = 0;
        for (BookdropFileEntity entity : filesToDelete) {
            finalizeProgressService.update(progressId, "Deleting selected Bookdrop files", processed, filesToDelete.size(), processed, 0, entity.getFileName(), false);
            Path filePath = Path.of(entity.getFilePath());
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                try {
                    Files.delete(filePath);
                    deletedFiles.incrementAndGet();
                    log.debug("Deleted file from disk: id={}, path={}", entity.getId(), filePath);
                } catch (IOException e) {
                    log.warn("Failed to delete file from disk for bookdropId={}: {}", entity.getId(), e.getMessage());
                }
            }
            Path coverPath = Paths.get(appProperties.getPathConfig(), "bookdrop_temp", entity.getId() + ".jpg");
            if (Files.exists(coverPath)) {
                try {
                    Files.delete(coverPath);
                    deletedCovers.incrementAndGet();
                    log.debug("Deleted cover image: id={}, path={}", entity.getId(), coverPath);
                } catch (IOException e) {
                    log.warn("Failed to delete cover image for bookdropId={}: {}", entity.getId(), e.getMessage());
                }
            }
            processed++;
            finalizeProgressService.update(progressId, "Deleting selected Bookdrop files", processed, filesToDelete.size(), processed, 0, entity.getFileName(), false);
        }
    }

    private Set<Long> collectAffectedLibraries(List<Long> ids,
                                               Map<Long, BookdropFinalizeRequest.BookdropFinalizeFile> metadataById,
                                               Long defaultLibraryId,
                                               Long defaultPathId) {
        Set<Long> affected = new HashSet<>();
        for (BookdropFileEntity file : bookdropFileRepository.findAllById(ids)) {
            try {
                affected.add(prepareFileProcessingContext(file, metadataById.get(file.getId()), defaultLibraryId, defaultPathId).libraryId());
            } catch (Exception e) {
                log.warn("Failed to determine library for Bookdrop file {}: {}", file.getId(), e.getMessage());
            }
        }
        return affected;
    }

    private void deleteEmptyDirectories(Path bookdropPath, AtomicInteger deletedDirs) {
        try (Stream<Path> paths = Files.walk(bookdropPath)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(bookdropPath) && Files.isDirectory(p))
                    .forEach(p -> {
                        try (Stream<Path> subPaths = Files.list(p)) {
                            if (subPaths.findAny().isEmpty()) {
                                try {
                                    Files.delete(p);
                                    deletedDirs.incrementAndGet();
                                    log.debug("Deleted empty directory: {}", p);
                                } catch (IOException e) {
                                    log.warn("Failed to delete empty directory: {}: {}", p, e.getMessage());
                                }
                            }
                        } catch (IOException e) {
                            log.warn("Failed to delete folder: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan bookdrop folder for empty directories", e);
        }
    }

    private BookdropFileResult failureResult(Long fileId, String fileName, String message) {
        return BookdropFileResult.builder()
                .fileId(fileId)
                .fileName(fileName)
                .message(message)
                .success(false)
                .build();
    }

    private boolean isFormatAllowed(LibraryEntity library, BookFileType fileType) {
        var allowedFormats = library.getAllowedFormats();
        return allowedFormats == null || allowedFormats.isEmpty() || allowedFormats.contains(fileType);
    }

    private record FileProcessingContext(Long libraryId, Long pathId, BookMetadata metadata) {
    }
}
