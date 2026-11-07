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
    public record InspectRequest(@NotNull Long libraryId, @NotEmpty @Size(min = 2, max = 20) List<@NotNull Long> bookIds, @NotNull Long targetBookId) {}
    public record MergeRequest(@NotNull Long libraryId, @NotEmpty @Size(min = 2, max = 20) List<@NotNull Long> bookIds,
                               @NotNull Long targetBookId, @NotNull String token, boolean confirmed) {}

    @GetMapping
    public ResponseEntity<CatalogueReconciliationService.Scan> scan(@RequestParam long libraryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.scan(libraryId, page, size));
    }
    @PostMapping("/preview")
    public ResponseEntity<CatalogueReconciliationService.Preview> preview(@RequestBody @Valid InspectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(request.libraryId, request.bookIds, request.targetBookId));
    }
    @PostMapping("/merge")
    public ResponseEntity<CatalogueReconciliationService.Receipt> merge(@RequestBody @Valid MergeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.merge(request.libraryId, request.bookIds,
                request.targetBookId, request.token, request.confirmed));
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
