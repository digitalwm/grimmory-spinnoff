package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import org.booklore.convertor.FormatPriorityConverter;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Small detached snapshots: no ORM collection hydration while scanning or mapping duplicate cards. */
@Component
@RequiredArgsConstructor
public class DuplicateCandidateLoader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final int MAX_ROWS = 200_000;

    public Set<DuplicateExclusions.Pair> exclusions(long libraryId) {return DuplicateExclusions.load(jdbc,libraryId);}

    @Transactional(readOnly = true)
    public List<BookEntity> load(long libraryId) {
        var libraries=jdbc.query("SELECT id,name,format_priority FROM library WHERE id=?",(rs,n)->LibraryEntity.builder()
                .id(rs.getLong("id")).name(rs.getString("name"))
                .formatPriority(new FormatPriorityConverter(mapper).convertToEntityAttribute(rs.getString("format_priority"))).build(),libraryId);
        if(libraries.isEmpty()) throw ApiError.LIBRARY_NOT_FOUND.createException(libraryId);
        LibraryEntity library=libraries.getFirst();
        Map<Long,BookEntity> books=new LinkedHashMap<>();
        jdbc.query("""
                SELECT b.id,b.metadata_match_score,b.is_physical,b.library_path_id,p.path,
                       m.title,m.isbn_13,m.isbn_10,m.goodreads_id,m.hardcover_id,m.google_id,
                       m.asin,m.audible_id,m.comicvine_id,m.applebooks_id,m.openlibrary_id,m.cover_updated_on
                FROM book b LEFT JOIN book_metadata m ON m.book_id=b.id
                LEFT JOIN library_path p ON p.id=b.library_path_id
                WHERE b.library_id=? AND (b.deleted IS NULL OR b.deleted=false) ORDER BY b.id LIMIT 200001
                """,rs->{
            long id=rs.getLong("id");
            var cover=rs.getTimestamp("cover_updated_on");
            var metadata=BookMetadataEntity.builder().bookId(id).title(rs.getString("title"))
                    .isbn13(rs.getString("isbn_13")).isbn10(rs.getString("isbn_10"))
                    .goodreadsId(rs.getString("goodreads_id")).hardcoverId(rs.getString("hardcover_id"))
                    .googleId(rs.getString("google_id")).asin(rs.getString("asin")).audibleId(rs.getString("audible_id"))
                    .comicvineId(rs.getString("comicvine_id")).applebooksId(rs.getString("applebooks_id"))
                    .openlibraryId(rs.getString("openlibrary_id")).coverUpdatedOn(cover==null?null:cover.toInstant())
                    .authors(new ArrayList<>()).build();
            var path=rs.getObject("library_path_id")==null?null:LibraryPathEntity.builder()
                    .id(rs.getLong("library_path_id")).path(rs.getString("path")).library(library).build();
            books.put(id,BookEntity.builder().id(id).library(library).libraryPath(path).metadata(metadata)
                    .metadataMatchScore(rs.getObject("metadata_match_score")==null?null:rs.getFloat("metadata_match_score"))
                    .isPhysical(rs.getBoolean("is_physical")).bookFiles(new LinkedHashSet<>()).build());
        },libraryId);
        if(books.size()>MAX_ROWS) throw ApiError.CONFLICT.createException("Library exceeds the interactive duplicate scan limit.");
        final int[] files={0},authors={0};
        jdbc.query("""
                SELECT f.id,f.book_id,f.file_name,f.file_sub_path,f.book_type,f.is_book,
                       f.is_folder_based,f.is_fixed_layout,f.file_size_kb,f.preferred_primary
                FROM book_file f JOIN book b ON b.id=f.book_id
                WHERE b.library_id=? AND (b.deleted IS NULL OR b.deleted=false) ORDER BY f.id LIMIT 200001
                """,rs->{
            files[0]++;var book=books.get(rs.getLong("book_id"));if(book==null)return;
            book.getBookFiles().add(BookFileEntity.builder().id(rs.getLong("id")).book(book)
                    .fileName(rs.getString("file_name")).fileSubPath(rs.getString("file_sub_path"))
                    .bookType(BookFileType.valueOf(rs.getString("book_type"))).isBookFormat(rs.getBoolean("is_book"))
                    .folderBased(rs.getBoolean("is_folder_based")).isFixedLayout(rs.getBoolean("is_fixed_layout"))
                    .fileSizeKb(rs.getObject("file_size_kb")==null?null:rs.getLong("file_size_kb"))
                    .preferredPrimary(rs.getBoolean("preferred_primary")).build());
        },libraryId);
        if(files[0]>MAX_ROWS)throw ApiError.CONFLICT.createException("Library has too many files for an interactive duplicate scan.");
        jdbc.query("""
                SELECT a.id,a.name,ma.book_id FROM book_metadata_author_mapping ma
                JOIN author a ON a.id=ma.author_id JOIN book b ON b.id=ma.book_id
                WHERE b.library_id=? AND (b.deleted IS NULL OR b.deleted=false)
                ORDER BY ma.book_id,ma.sort_order,a.id LIMIT 1000001
                """,rs->{
            authors[0]++;var book=books.get(rs.getLong("book_id"));if(book==null)return;
            book.getMetadata().getAuthors().add(AuthorEntity.builder().id(rs.getLong("id")).name(rs.getString("name")).build());
        },libraryId);
        if(authors[0]>1_000_000)throw ApiError.CONFLICT.createException("Library has too many author credits for an interactive duplicate scan.");
        return new ArrayList<>(books.values());
    }
}
