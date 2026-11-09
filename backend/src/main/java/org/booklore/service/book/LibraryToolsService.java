package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LibraryToolsService {
    private final BookRepository bookRepository;
    private final PlatformTransactionManager transactionManager;
    @Value("${LIBRARY_TOOLS_API_KEY:}") private String internalApiKey;
    @Value("${library-tools.importer-url:http://calibre-importer:8090}") private String importerUrl;

    public Map<String, Object> identify(List<Long> bookIds) {
        if (internalApiKey == null || internalApiKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Library identity analysis is not configured.");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        List<Map<String, Object>> requestBooks = transaction.execute(status -> prepareBooks(bookIds));
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(3));
            requestFactory.setReadTimeout(Duration.ofSeconds(150));
            return RestClient.builder().requestFactory(requestFactory).baseUrl(importerUrl)
                    .defaultHeader("X-Library-Tools-Key", internalApiKey).build()
                    .post().uri("/api/internal/library-tools/identity-assessment")
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("books", requestBooks))
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The local book analysis service could not complete the identity check.");
        }
    }

    private List<Map<String, Object>> prepareBooks(List<Long> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > 6 || new HashSet<>(ids).size() != ids.size())
            throw ApiError.GENERIC_BAD_REQUEST.createException("Choose between two and six different books.");
        List<BookEntity> books = bookRepository.findAllById(ids);
        if (books.size() != ids.size()) throw ApiError.GENERIC_BAD_REQUEST.createException("One or more selected books no longer exist.");
        Long libraryId = books.getFirst().getLibrary().getId();
        if (books.stream().anyMatch(book -> !Objects.equals(book.getLibrary().getId(), libraryId)))
            throw ApiError.GENERIC_BAD_REQUEST.createException("All books must be in the same library.");
        List<Map<String, Object>> requestBooks = new ArrayList<>();
        for (BookEntity book : books) {
            BookFileEntity file = book.getPrimaryBookFile();
            BookMetadataEntity metadata = book.getMetadata();
            if (file == null || metadata == null || file.isFolderBased() || !file.isBookFormat())
                throw ApiError.GENERIC_BAD_REQUEST.createException("Each selected book needs a readable PDF or EPUB file.");
            String extension = Optional.ofNullable(file.getFileName()).orElse("").toLowerCase(Locale.ROOT);
            if (!extension.endsWith(".pdf") && !extension.endsWith(".epub"))
                throw ApiError.GENERIC_BAD_REQUEST.createException("First-page identity checks currently support PDF and EPUB files.");
            if (book.getLibraryPath() == null || book.getLibraryPath().getPath() == null || book.getFullFilePath() == null)
                throw ApiError.GENERIC_BAD_REQUEST.createException("A selected book has no registered file path.");
            Path root = Path.of(book.getLibraryPath().getPath()).toAbsolutePath().normalize();
            Path path = book.getFullFilePath().toAbsolutePath().normalize();
            try {
                Path realRoot = root.toRealPath();
                Path realFile = path.toRealPath();
                Path importerRoot = Path.of("/books").toRealPath();
                if (!realRoot.startsWith(importerRoot) || !realFile.startsWith(realRoot)
                        || !Files.isRegularFile(realFile) || Files.isSymbolicLink(path))
                    throw ApiError.GENERIC_BAD_REQUEST.createException("A selected book file is outside its registered library path.");
            } catch (java.io.IOException e) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("A selected book file is missing or unreadable.");
            }
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("title", metadata.getTitle());
            current.put("authors", metadata.getAuthors().stream().map(AuthorEntity::getName).filter(Objects::nonNull).toList());
            current.put("isbn13", metadata.getIsbn13()); current.put("isbn10", metadata.getIsbn10());
            current.put("publisher", metadata.getPublisher());
            current.put("publishedDate", metadata.getPublishedDate() == null ? null : metadata.getPublishedDate().toString());
            current.put("goodreadsId", metadata.getGoodreadsId()); current.put("openlibraryId", metadata.getOpenlibraryId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", book.getId()); entry.put("filePath", path.toString());
            entry.put("pathHint", root.relativize(path).toString()); entry.put("metadata", current);
            requestBooks.add(entry);
        }
        return requestBooks;
    }
}
