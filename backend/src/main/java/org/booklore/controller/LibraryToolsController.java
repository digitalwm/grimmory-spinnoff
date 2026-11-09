package org.booklore.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.booklore.service.book.LibraryToolsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/library-tools")
@PreAuthorize("@securityUtil.isAdmin()")
public class LibraryToolsController {
    private final LibraryToolsService service;

    public record IdentityAssessmentRequest(@NotEmpty @Size(min = 2, max = 6) List<@NotNull Long> bookIds) {}

    @PostMapping("/identity-assessment")
    public ResponseEntity<Map<String, Object>> identify(@RequestBody @Valid IdentityAssessmentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.identify(request.bookIds()));
    }
}
