package org.booklore.model.entity;

import org.booklore.mapper.BookMapper;
import org.booklore.mapper.v2.BookMapperV2;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PrimaryBookFileSelectionTest {
    @Test void explicitMergedTargetWinsOverOlderFilesAndLibraryFormatPriorityInEveryReader() {
        var library=LibraryEntity.builder().formatPriority(List.of(BookFileType.PDF,BookFileType.EPUB)).build();
        var book=BookEntity.builder().library(library).build();
        var source=BookFileEntity.builder().id(11L).book(book).isBookFormat(true).bookType(BookFileType.PDF).build();
        var chosen=BookFileEntity.builder().id(44L).book(book).isBookFormat(true).bookType(BookFileType.EPUB).preferredPrimary(true).build();
        book.setBookFiles(Set.of(source,chosen));
        assertEquals(chosen,book.getPrimaryBookFile());
        assertEquals(chosen,book.getBookFiles().stream().min(BookFileEntity.preferredOrder()).orElseThrow());
        assertEquals(chosen,mock(BookMapper.class,CALLS_REAL_METHODS).getPrimaryBookFile(book.getBookFiles()));
        assertEquals(chosen,mock(BookMapperV2.class,CALLS_REAL_METHODS).getPrimaryBookFile(book.getBookFiles()));
    }
    @Test void booksWithoutAnExplicitMergePreferenceKeepTheirFormatPriority() {
        var book=BookEntity.builder().library(LibraryEntity.builder().formatPriority(List.of(BookFileType.EPUB)).build()).build();
        var pdf=BookFileEntity.builder().id(11L).book(book).isBookFormat(true).bookType(BookFileType.PDF).build();
        var epub=BookFileEntity.builder().id(44L).book(book).isBookFormat(true).bookType(BookFileType.EPUB).build();
        book.setBookFiles(Set.of(pdf,epub));
        assertEquals(epub,book.getPrimaryBookFile());
        assertEquals(epub,mock(BookMapper.class,CALLS_REAL_METHODS).getPrimaryBookFile(book.getBookFiles()));
        assertEquals(epub,mock(BookMapperV2.class,CALLS_REAL_METHODS).getPrimaryBookFile(book.getBookFiles()));
    }
}
