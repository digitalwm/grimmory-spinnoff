package org.booklore.service.bookdrop;

import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.repository.BookdropFileRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookdropMetadataHelperTest {
    @Test void bulkEditsStartWithEmbeddedMetadataWhenACachedMatchWasWrong() {
        var repository=mock(BookdropFileRepository.class);var helper=new BookdropMetadataHelper(repository,new ObjectMapper());
        var file=BookdropFileEntity.builder().originalMetadata("{\"title\":\"Ever Fallen\",\"authors\":[\"Elle Scott\"]}")
                .fetchedMetadata("{\"title\":\"Never Have I Ever Fallen for a Movie Star\",\"authors\":[\"Juliet Bardsley\"]}").build();
        assertEquals("Ever Fallen",helper.getCurrentMetadata(file).getTitle());verifyNoInteractions(repository);
    }
    @Test void malformedCachedMetadataStillPreservesTheEmbeddedIdentity() {
        var helper=new BookdropMetadataHelper(mock(BookdropFileRepository.class),new ObjectMapper());
        var file=BookdropFileEntity.builder().originalMetadata("{\"title\":\"Ever Fallen\",\"authors\":[\"Elle Scott\"]}").fetchedMetadata("broken").build();
        assertEquals("Ever Fallen",helper.getCurrentMetadata(file).getTitle());
    }
    @Test void aValidatedMatchRemainsAvailableForBulkEdits() {
        var helper=new BookdropMetadataHelper(mock(BookdropFileRepository.class),new ObjectMapper());
        var file=BookdropFileEntity.builder().originalMetadata("{\"title\":\"Ever Fallen\",\"authors\":[\"Elle Scott\"]}")
                .fetchedMetadata("{\"title\":\"Ever Fallen: A Novel\",\"authors\":[\"Elle Scott\"]}").build();
        assertEquals("Ever Fallen: A Novel",helper.getCurrentMetadata(file).getTitle());
    }
}
