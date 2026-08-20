ALTER TABLE refresh_tokens
    ADD COLUMN auth_method VARCHAR(40) NOT NULL DEFAULT 'pwd';
