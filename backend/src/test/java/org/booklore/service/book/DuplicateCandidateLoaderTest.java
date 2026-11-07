package org.booklore.service.book;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DuplicateCandidateLoaderTest {
    static class CountingJdbc extends JdbcTemplate {
        int queries;
        CountingJdbc(DriverManagerDataSource source){super(source);}
        @Override public void query(String sql,RowCallbackHandler callback,Object... args){queries++;super.query(sql,callback,args);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){queries++;return super.query(sql,mapper,args);}
    }
    @Test void fixedQueriesLoadDetachedMatchingAndDisplayFieldsWithoutOtherLibrariesOrDeletedBooks() {
        var jdbc=new CountingJdbc(new DriverManagerDataSource("jdbc:h2:mem:duplicate_loader;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE library(id BIGINT,name VARCHAR,format_priority VARCHAR)");
        jdbc.execute("CREATE TABLE library_path(id BIGINT,path VARCHAR)");
        jdbc.execute("CREATE TABLE book(id BIGINT,library_id BIGINT,library_path_id BIGINT,deleted BOOLEAN,is_physical BOOLEAN,metadata_match_score FLOAT)");
        jdbc.execute("CREATE TABLE book_metadata(book_id BIGINT,title VARCHAR,isbn_13 VARCHAR,isbn_10 VARCHAR,goodreads_id VARCHAR,hardcover_id VARCHAR,google_id VARCHAR,asin VARCHAR,audible_id VARCHAR,comicvine_id VARCHAR,applebooks_id VARCHAR,openlibrary_id VARCHAR,cover_updated_on TIMESTAMP)");
        jdbc.execute("CREATE TABLE book_file(id BIGINT,book_id BIGINT,file_name VARCHAR,file_sub_path VARCHAR,book_type VARCHAR,is_book BOOLEAN,is_folder_based BOOLEAN,is_fixed_layout BOOLEAN,file_size_kb BIGINT,preferred_primary BOOLEAN)");
        jdbc.execute("CREATE TABLE author(id BIGINT,name VARCHAR)");
        jdbc.execute("CREATE TABLE book_metadata_author_mapping(book_id BIGINT,author_id BIGINT,sort_order INT)");
        jdbc.update("INSERT INTO library VALUES(1,'Fixture','[\"EPUB\",\"PDF\"]')");
        jdbc.update("INSERT INTO library_path VALUES(1,'/books')");
        for(int i=1;i<=1000;i++){
            jdbc.update("INSERT INTO book VALUES(?,1,1,false,false,90)",i);
            jdbc.update("INSERT INTO book_metadata(book_id,title,isbn_13) VALUES(?,'The Book','9780989375832')",i);
            jdbc.update("INSERT INTO book_file VALUES(?,?,'book.epub','Fixture','EPUB',true,false,false,100,true)",i,i);
            jdbc.update("INSERT INTO book_metadata_author_mapping VALUES(?,1,0)",i);
        }
        jdbc.update("INSERT INTO book VALUES(1001,1,1,true,false,90),(1002,2,1,false,false,90)");
        jdbc.update("INSERT INTO author VALUES(1,'Fixture Author')");
        jdbc.queries=0;var books=new DuplicateCandidateLoader(jdbc,new ObjectMapper()).load(1);
        assertEquals(4,jdbc.queries);assertEquals(1000,books.size());
        var first=books.getFirst();assertEquals("The Book",first.getMetadata().getTitle());
        assertEquals("Fixture Author",first.getMetadata().getAuthors().getFirst().getName());
        assertEquals("9780989375832",first.getMetadata().getIsbn13());
        assertTrue(first.getPrimaryBookFile().isPreferredPrimary());assertEquals(100L,first.getPrimaryBookFile().getFileSizeKb());
        assertEquals("/books",first.getLibraryPath().getPath());
    }
}
