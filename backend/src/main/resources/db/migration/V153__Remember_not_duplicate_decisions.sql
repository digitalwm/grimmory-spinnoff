CREATE TABLE catalogue_duplicate_exclusion (
    library_id BIGINT NOT NULL,
    book_low_id BIGINT NOT NULL,
    book_high_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(library_id, book_low_id, book_high_id),
    FOREIGN KEY(library_id) REFERENCES library(id) ON DELETE CASCADE,
    FOREIGN KEY(book_low_id) REFERENCES book(id) ON DELETE CASCADE,
    FOREIGN KEY(book_high_id) REFERENCES book(id) ON DELETE CASCADE
);
