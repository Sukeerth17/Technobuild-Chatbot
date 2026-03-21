ALTER TABLE documents
    ADD COLUMN job_id VARCHAR(36) NULL AFTER id;

UPDATE documents
SET job_id = UUID()
WHERE job_id IS NULL;

ALTER TABLE documents
    MODIFY COLUMN job_id VARCHAR(36) NOT NULL,
    ADD UNIQUE KEY uq_documents_job_id (job_id),
    ADD INDEX idx_job_id (job_id);
