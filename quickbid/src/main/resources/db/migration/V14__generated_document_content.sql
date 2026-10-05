-- Generated PDFs are small, app-owned artifacts. Persisting their bytes in
-- PostgreSQL keeps them downloadable on hosts with ephemeral filesystems.
ALTER TABLE app_archivos
    ADD COLUMN content_bytes bytea;

ALTER TABLE app_archivos
    ADD CONSTRAINT chk_app_archivos_content_size
    CHECK (content_bytes IS NULL OR octet_length(content_bytes) = size_bytes);
