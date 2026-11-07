package org.booklore.service.bookdrop;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BookdropFinalizeProgressService {
    public record Progress(String phase, int processed, int total, int succeeded, int failed, String currentFile, boolean complete, String updatedAt) {}

    private final ConcurrentHashMap<String, Progress> progress = new ConcurrentHashMap<>();

    public void update(String id, String phase, int processed, int total, int succeeded, int failed, String currentFile, boolean complete) {
        if (id == null || id.isBlank()) return;
        progress.put(id, new Progress(phase, processed, total, succeeded, failed, currentFile, complete, Instant.now().toString()));
    }

    public Progress get(String id) {
        return progress.get(id);
    }
}
