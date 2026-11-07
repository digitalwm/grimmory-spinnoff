package org.booklore.service.bookdrop;

import org.booklore.config.AppProperties;
import org.booklore.exception.APIException;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Timeout;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookDropServiceFinalizeTest {

    @Test
    void bulkImportUsesEmbeddedIdentityInsteadOfAnOldMismatchedProposal() throws Exception {
        var file = new org.booklore.model.entity.BookdropFileEntity();
        file.setId(94202L);
        file.setOriginalMetadata("original"); file.setFetchedMetadata("stale");
        var original = org.booklore.model.dto.BookMetadata.builder().title("Fate on Fire")
                .authors(List.of("E. L. Todd")).build();
        var stale = org.booklore.model.dto.BookMetadata.builder().title("Feudlings")
                .authors(List.of("Wendy Knight")).build();
        when(objectMapper.readValue("original", org.booklore.model.dto.BookMetadata.class)).thenReturn(original);
        when(objectMapper.readValue("stale", org.booklore.model.dto.BookMetadata.class)).thenReturn(stale);
        Object context = org.springframework.test.util.ReflectionTestUtils.invokeMethod(bookDropService,
                "prepareFileProcessingContext", file, null, 1L, 1L);
        assertSame(original, org.springframework.test.util.ReflectionTestUtils.getField(context, "metadata"));
    }

    @Mock
    private BookdropFileRepository bookdropFileRepository;
    @Mock
    private BookdropMonitoringService bookdropMonitoringService;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private MonitoringRegistrationService monitoringRegistrationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private FileMovingHelper fileMovingHelper;
    @Mock
    private AppProperties appProperties;
    @Mock
    private BookdropNotificationService bookdropNotificationService;
    @Mock
    private KoboAutoShelfService koboAutoShelfService;
    @Mock
    private BookdropFinalizeProgressService finalizeProgressService;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private BookDropService bookDropService;

    @Test
    @Timeout(10)
    void overlappingImportsAndDeletionsAreRejectedUntilTheImportFinishes() throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            paused.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(bookdropMonitoringService).pauseMonitoring();
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(false);
        request.setFiles(List.of());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> bookDropService.finalizeImport(request));
            try {
                assertTrue(paused.await(5, TimeUnit.SECONDS));
                assertThrows(APIException.class, () -> bookDropService.finalizeImport(request));
                assertThrows(APIException.class, () -> bookDropService.discardSelectedFiles(false, List.of(), List.of()));
                verify(bookdropMonitoringService, never()).resumeMonitoring();
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
        // A later operation can proceed after the first one releases the guard.
        bookDropService.finalizeImport(request);
        verify(bookdropMonitoringService, times(2)).pauseMonitoring();
        verify(bookdropMonitoringService, times(2)).resumeMonitoring();
    }

    @Test
    void monitoringFailureDoesNotLeaveTheOperationGuardStuck() {
        doThrow(new IllegalStateException("monitor unavailable")).doNothing()
                .when(bookdropMonitoringService).resumeMonitoring();
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(false);
        request.setFiles(List.of());

        assertThrows(IllegalStateException.class, () -> bookDropService.finalizeImport(request));
        assertDoesNotThrow(() -> bookDropService.finalizeImport(request));
    }

    @Test
    void finalizeImport_selectAll_emptyExcludedIds_shouldCallFindAllIds() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(true);
        request.setExcludedIds(Collections.emptyList());
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);

        when(bookdropFileRepository.findAllIds()).thenReturn(List.of(1L, 2L));
        when(bookdropFileRepository.findAllById(anyList())).thenReturn(Collections.emptyList()); // Mock chunk processing

        bookDropService.finalizeImport(request);

        verify(bookdropFileRepository).findAllIds();
        verify(bookdropFileRepository, never()).findAllExcludingIdsFlat(anyList());
    }

    @Test
    void finalizeImport_selectAll_withExcludedIds_shouldCallFindAllExcludingIdsFlat() {
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(true);
        request.setExcludedIds(List.of(3L));
        request.setDefaultLibraryId(1L);
        request.setDefaultPathId(1L);

        when(bookdropFileRepository.findAllExcludingIdsFlat(anyList())).thenReturn(List.of(1L, 2L));
        when(bookdropFileRepository.findAllById(anyList())).thenReturn(Collections.emptyList()); // Mock chunk processing

        bookDropService.finalizeImport(request);

        verify(bookdropFileRepository).findAllExcludingIdsFlat(List.of(3L));
        verify(bookdropFileRepository, never()).findAllIds();
    }
}
