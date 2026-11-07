package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrowseCountServiceTest {

    @Mock private AuthenticationService authenticationService;
    @Mock private EntityManager entityManager;
    @Mock private Query authorQuery;
    @Mock private Query seriesQuery;
    private BrowseCountService service;

    @BeforeEach
    void setUp() {
        service = new BrowseCountService(authenticationService, entityManager);
    }

    @Test
    void noAssignedLibrariesReturnsZeroWithoutQueryingOrUsingAdminCache() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user(false, List.of()));

        assertEquals(new BrowseCountService.Counts(0, 0), service.getCounts());
        verifyNoInteractions(entityManager);
    }

    @Test
    void repeatedAdminRequestsReuseCountsWithoutLoadingBookOrAuthorPages() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user(true, List.of()));
        stubCounts(12, 7);

        assertEquals(new BrowseCountService.Counts(12, 7), service.getCounts());
        assertEquals(new BrowseCountService.Counts(12, 7), service.getCounts());

        verify(entityManager, times(2)).createNativeQuery(anyString());
        verify(authorQuery, never()).setParameter(anyString(), any());
        verify(seriesQuery, never()).setParameter(anyString(), any());
        verifyNoMoreInteractions(entityManager);
    }

    @Test
    void permissionChangesUseSeparateLibraryScopesAndCannotReuseAdminCounts() {
        BookLoreUser user = user(true, List.of());
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        stubCounts(12, 7);
        assertEquals(new BrowseCountService.Counts(12, 7), service.getCounts());

        user.getPermissions().setAdmin(false);
        user.setAssignedLibraries(List.of(Library.builder().id(4L).build()));
        stubCounts(3, 2);
        assertEquals(new BrowseCountService.Counts(3, 2), service.getCounts());

        verify(entityManager, times(2)).createNativeQuery(contains("AND b.library_id IN (:libraryIds)"));
        verify(authorQuery).setParameter("libraryIds", Set.of(4L));
        verify(seriesQuery).setParameter("libraryIds", Set.of(4L));

        user.setAssignedLibraries(List.of());
        assertEquals(new BrowseCountService.Counts(0, 0), service.getCounts());
        verify(entityManager, times(4)).createNativeQuery(anyString());
    }

    private void stubCounts(long authors, long series) {
        when(entityManager.createNativeQuery(contains("mapping.author_id"))).thenReturn(authorQuery);
        when(entityManager.createNativeQuery(contains("metadata.series_name"))).thenReturn(seriesQuery);
        when(authorQuery.getSingleResult()).thenReturn(authors);
        when(seriesQuery.getSingleResult()).thenReturn(series);
    }

    private BookLoreUser user(boolean admin, List<Library> libraries) {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        return BookLoreUser.builder().id(1L).permissions(permissions).assignedLibraries(libraries).build();
    }
}
