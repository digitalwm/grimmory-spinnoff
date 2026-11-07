package org.booklore.service.browse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrowseCountService {

    private static final String AUTHOR_COUNT_SQL = """
            SELECT COUNT(DISTINCT mapping.author_id)
            FROM book_metadata_author_mapping mapping
            JOIN book b ON b.id = mapping.book_id
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND EXISTS (SELECT 1 FROM book_file f WHERE f.book_id = b.id)
            """;
    private static final String SERIES_COUNT_SQL = """
            SELECT COUNT(DISTINCT metadata.series_name)
            FROM book_metadata metadata
            JOIN book b ON b.id = metadata.book_id
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (b.is_physical = true OR EXISTS (SELECT 1 FROM book_file f WHERE f.book_id = b.id))
              AND metadata.series_name IS NOT NULL
            """;

    private final AuthenticationService authenticationService;
    private final EntityManager entityManager;

    // Coalesce requests from open tabs without sharing counts across library scopes.
    private final Cache<Scope, Counts> counts = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    public record Counts(long authors, long series) {}

    private record Scope(boolean allLibraries, Set<Long> libraryIds) {}

    @Transactional(readOnly = true)
    public Counts getCounts() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        boolean admin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = admin || user.getAssignedLibraries() == null
                ? Set.of()
                : user.getAssignedLibraries().stream().map(Library::getId).collect(Collectors.toUnmodifiableSet());
        if (!admin && libraryIds.isEmpty()) {
            return new Counts(0, 0);
        }
        return counts.get(new Scope(admin, libraryIds), this::loadCounts);
    }

    private Counts loadCounts(Scope scope) {
        return new Counts(count(AUTHOR_COUNT_SQL, scope), count(SERIES_COUNT_SQL, scope));
    }

    private long count(String sql, Scope scope) {
        Query query = entityManager.createNativeQuery(sql + (scope.allLibraries() ? "" : " AND b.library_id IN (:libraryIds)"));
        if (!scope.allLibraries()) {
            query.setParameter("libraryIds", scope.libraryIds());
        }
        return ((Number) query.getSingleResult()).longValue();
    }
}
