package org.booklore.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.booklore.service.book.CatalogueReconciliationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/books/catalogue-reconciliation")
@PreAuthorize("@securityUtil.isAdmin()")
public class CatalogueReconciliationController {
    private final CatalogueReconciliationService service;
    private final org.booklore.service.book.CatalogueQuarantineService quarantine;
    public record InspectRequest(@NotNull Long libraryId, @NotEmpty @Size(min = 2, max = 20) List<@NotNull Long> bookIds, @NotNull Long targetBookId, boolean identityConfirmed) {}
    public record MergeRequest(@NotNull Long libraryId, @NotEmpty @Size(min = 2, max = 20) List<@NotNull Long> bookIds,
                               @NotNull Long targetBookId, @NotNull String token, boolean confirmed, boolean identityConfirmed) {}

    @GetMapping
    public ResponseEntity<CatalogueReconciliationService.Scan> scan(@RequestParam long libraryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String search, @RequestParam(required = false) Integer offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.scan(libraryId, page, size, search, offset));
    }
    @PostMapping("/preview")
    public ResponseEntity<CatalogueReconciliationService.Preview> preview(@RequestBody @Valid InspectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(request.libraryId, request.bookIds, request.targetBookId,request.identityConfirmed));
    }
    @PostMapping("/quick-merge")
    public ResponseEntity<CatalogueReconciliationService.QuickMergeResult> quickMerge(@RequestBody @Valid InspectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.quickMerge(request.libraryId, request.bookIds, request.targetBookId));
    }

    public record ExcludeRequest(@NotNull Long libraryId,@NotEmpty @Size(min=2,max=20) List<@NotNull Long> bookIds, Long isolatedBookId) {}
    @PostMapping("/not-duplicates")
    public ResponseEntity<Void> exclude(@RequestBody @Valid ExcludeRequest request) {
        service.exclude(request.libraryId,request.bookIds,request.isolatedBookId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/merge")
    public ResponseEntity<CatalogueReconciliationService.Receipt> merge(@RequestBody @Valid MergeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.merge(request.libraryId, request.bookIds,
                request.targetBookId, request.token, request.confirmed,request.identityConfirmed));
    }
    @GetMapping("/quarantine/{id}/file")
    public ResponseEntity<org.springframework.core.io.Resource> quarantinedFile(@PathVariable String id) {
        var file = quarantine.open(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).body(file.resource());
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.receipt(id));
    }
    @GetMapping("/receipts")
    public ResponseEntity<List<CatalogueReconciliationService.ReceiptSummary>> receipts(@RequestParam long libraryId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.receipts(libraryId));
    }
}
