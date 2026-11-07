-- Serializes catalogue merges with quarantine recovery and retention cleanup.
CREATE TABLE catalogue_reconciliation_lock (id INT NOT NULL PRIMARY KEY);
INSERT INTO catalogue_reconciliation_lock(id) VALUES(1);

-- No FK to the audit receipt: the journal must commit before filesystem moves,
-- including operations whose main transaction later rolls back or loses its process.
CREATE TABLE catalogue_quarantine (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    receipt_id VARCHAR(36) NOT NULL,
    library_id BIGINT NOT NULL,
    original_path TEXT NOT NULL,
    quarantine_path TEXT NOT NULL,
    retained_path TEXT NOT NULL,
    retained_file_id BIGINT NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purge_after TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    detail TEXT NULL,
    INDEX idx_catalogue_quarantine_receipt(receipt_id),
    INDEX idx_catalogue_quarantine_cleanup(state,purge_after)
);
