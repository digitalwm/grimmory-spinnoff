package org.booklore.mapper;

import org.booklore.model.entity.BookdropFileEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BookdropFileMapperTest {
    private BookdropFileMapper mapper() {
        var mapper=Mappers.getMapper(BookdropFileMapper.class);
        ReflectionTestUtils.setField(mapper,"objectMapper",new ObjectMapper());return mapper;
    }
    private BookdropFileEntity file(String fetched) {
        return BookdropFileEntity.builder().id(95576L).fileName("Elle Scott - Ever Fallen.epub")
                .originalMetadata("{\"title\":\"Ever Fallen\",\"authors\":[\"Elle Scott\"]}").fetchedMetadata(fetched).build();
    }
    @Test void rejectsTheStoredEverFallenCollisionWithoutMutatingTheStoredReceipt() {
        var file=file("{\"title\":\"Never Have I Ever Fallen for a Movie Star\",\"authors\":[\"Juliet Bardsley\"]}");
        var result=mapper().toDto(file);
        assertTrue(result.isMetadataMatchRejected());assertNull(result.getFetchedMetadata());
        assertEquals("Ever Fallen",result.getOriginalMetadata().getTitle());
        assertTrue(result.getMetadataMatchMessage().contains("Juliet Bardsley"));
        assertTrue(file.getFetchedMetadata().contains("Juliet Bardsley"));
    }
    @Test void acceptsACompatibleSubtitleAndAuthor() {
        var result=mapper().toDto(file("{\"title\":\"Ever Fallen: A Novel\",\"authors\":[\"Elle Scott\"]}"));
        assertFalse(result.isMetadataMatchRejected());assertNotNull(result.getFetchedMetadata());
    }
    @Test void rejectsMatchingTitlesWithTheWrongAuthor() {
        assertTrue(mapper().toDto(file("{\"title\":\"Ever Fallen\",\"authors\":[\"Someone Else\"]}")).isMetadataMatchRejected());
    }
    @Test void malformedStoredMetadataIsRejectedInsteadOfOfferedForCopy() {
        var result=mapper().toDto(file("not valid json"));assertTrue(result.isMetadataMatchRejected());assertNull(result.getFetchedMetadata());
    }
    @Test void noFetchedMetadataHasNoFalseMismatchWarning() {
        var result=mapper().toDto(file(null));assertFalse(result.isMetadataMatchRejected());assertNull(result.getFetchedMetadata());
    }
}
