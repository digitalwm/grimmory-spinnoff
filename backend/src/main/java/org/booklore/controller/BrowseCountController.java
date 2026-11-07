package org.booklore.controller;

import lombok.RequiredArgsConstructor;
import org.booklore.service.browse.BrowseCountService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/browse/counts")
public class BrowseCountController {

    private final BrowseCountService browseCountService;

    @GetMapping
    public ResponseEntity<BrowseCountService.Counts> getCounts() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(browseCountService.getCounts());
    }
}
