package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.booklore.model.entity.BookMetadataEntity;
import static org.junit.jupiter.api.Assertions.*;

class IsbnUtilsTest {
    @Test void acceptsValidFormattedIdentifiersAndConvertsValidIsbn10() {
        assertEquals("9780306406157",IsbnUtils.normalize13("urn:isbn:978-0-306-40615-7"));
        assertEquals("080442957X",IsbnUtils.normalize10("ISBN-10: 0-8044-2957-x"));
        assertEquals("9780306406157",IsbnUtils.canonical13("978-0-9893758","0306406152"));
    }
    @Test void rejectsTruncationsBadChecksumsAndUnrelatedText() {
        for(String value:new String[]{"978-0-9893758","9780306406158","9780306406157123","words9780306406157", "978_0306406157"}) assertNull(IsbnUtils.normalize13(value));
        assertNull(IsbnUtils.normalize10("0306406153")); assertNull(IsbnUtils.canonical13(null,null));
    }
    @Test void entityNormalizesValidValuesWithoutSilentlyRemovingLegacyInvalidValues() {
        var metadata=new BookMetadataEntity();metadata.setIsbn13("978-0-306-40615-7");metadata.setIsbn10("0-306-40615-2");metadata.updateSearchText();
        assertEquals("9780306406157",metadata.getIsbn13());assertEquals("0306406152",metadata.getIsbn10());
        metadata.setIsbn13("978-0-9893758");metadata.updateSearchText();assertEquals("978-0-9893758",metadata.getIsbn13());
    }
}
