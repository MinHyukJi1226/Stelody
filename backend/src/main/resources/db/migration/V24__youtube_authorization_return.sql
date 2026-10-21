-- Nullable additions preserve in-flight authorizations and compatibility with the previous runtime.
ALTER TABLE app.youtube_authorization
    ADD COLUMN return_path TEXT CHECK (return_path IS NULL OR (length(return_path) BETWEEN 1 AND 2048 AND left(return_path,1)='/')),
    ADD COLUMN callback_client VARCHAR(8) NOT NULL DEFAULT 'default' CHECK (callback_client IN ('default','local')),
    ADD COLUMN callback_uri TEXT;
