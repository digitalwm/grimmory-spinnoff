CREATE TABLE catalogue_reconciliation_audit (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    library_id BIGINT NOT NULL,
    target_book_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    file_path TEXT NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    INDEX idx_catalogue_reconciliation_library_created (library_id, created_at)
);

-- Legacy viewer preferences lacked book FKs. Parent locks during catalogue merges
-- must also protect these rows from concurrent inserts referencing removed books.
-- Existing orphans deliberately fail migration rather than being silently removed.
ALTER TABLE cbx_viewer_preference ADD CONSTRAINT fk_reconciliation_cbx_book FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE;
ALTER TABLE epub_viewer_preference ADD CONSTRAINT fk_reconciliation_epub_book FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE;
ALTER TABLE pdf_viewer_preference ADD CONSTRAINT fk_reconciliation_pdf_book FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE;
ALTER TABLE new_pdf_viewer_preference ADD CONSTRAINT fk_reconciliation_new_pdf_book FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE;
