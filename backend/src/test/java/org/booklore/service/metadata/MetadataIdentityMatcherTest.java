package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataIdentityMatcherTest {
    private BookMetadata book(String title, String author) {
        return BookMetadata.builder().title(title).authors(author == null ? List.of() : List.of(author)).build();
    }

    @Test void rejectsFateOnFireSeriesCollision() {
        BookMetadata wrong = book("Feudlings", "Wendy Knight");
        wrong.setSeriesName("Fate on Fire");
        assertFalse(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), wrong));
    }
    @Test void rejectsWrongAuthorEvenWhenTitleMatches() {
        assertFalse(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), book("Fate on Fire", "Wendy Knight")));
    }
    @Test void rejectsWrongTitleEvenWhenAuthorMatches() {
        assertFalse(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), book("All I Want Is You", "E. L. Todd")));
    }
    @Test void acceptsPunctuationAccentsAndCaseVariations() {
        assertTrue(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), book("FATE ON FIRE", "E L Todd")));
        assertTrue(MetadataIdentityMatcher.matches(book("Les Misérables", "Victor Hugo"), book("Les Miserables", "Victor Hugo")));
    }
    @Test void acceptsInitialsWithSameSurnameAndCompatibleGivenNames() {
        assertTrue(MetadataIdentityMatcher.matches(book("The Book", "J. R. Tolkien"), book("The Book", "John Ronald Tolkien")));
        assertFalse(MetadataIdentityMatcher.matches(book("The Book", "J. R. Tolkien"), book("The Book", "John William Tolkien")));
    }
    @Test void acceptsExplicitSubtitleAndNumberedSeriesSuffix() {
        assertTrue(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), book("Fate on Fire (Stars #3)", "E. L. Todd")));
        assertTrue(MetadataIdentityMatcher.matches(book("The Book", "Ada Author"), book("The Book: A Memoir", "Ada Author")));
    }
    @Test void rejectsTitleContainedOnlyInLongerTitle() {
        assertFalse(MetadataIdentityMatcher.matches(book("Fire", "Ada Author"), book("Fate on Fire", "Ada Author")));
    }
    @Test void rejectsConflictingExplicitSubtitles() {
        assertFalse(MetadataIdentityMatcher.matches(book("The Book: Volume One", "Ada Author"),
                book("The Book: Volume Two", "Ada Author")));
    }
    @Test void missingCandidateAuthorDoesNotEraseKnownIdentity() {
        assertFalse(MetadataIdentityMatcher.matches(book("Fate on Fire", "E. L. Todd"), book("Fate on Fire", null)));
    }
    @Test void sharedIdentifierDoesNotOverrideExplicitIdentityConflict() {
        BookMetadata source = book("Fate on Fire", "E. L. Todd"), candidate = book("Feudlings", "Wendy Knight");
        source.setGoodreadsId("17661909"); candidate.setGoodreadsId("17661909");
        assertFalse(MetadataIdentityMatcher.matches(source, candidate));
    }
    @Test void unknownIdentityRequiresSharedIdentifier() {
        BookMetadata source = book(null, null), candidate = book("The Book", "Ada Author");
        assertFalse(MetadataIdentityMatcher.matches(source, candidate));
        source.setGoogleId("known-id"); candidate.setGoogleId("known-id");
        assertTrue(MetadataIdentityMatcher.matches(source, candidate));
    }
}
