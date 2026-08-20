-- Spring Security's JdbcUserCredentialRepository binds credential identifiers
-- as VARCHAR values containing base64url text. V9 incorrectly created both
-- identifier columns as BYTEA, which made every password login fail while the
-- service checked whether the account had a passkey.
ALTER TABLE user_credentials
    ALTER COLUMN credential_id TYPE VARCHAR(255)
        USING rtrim(replace(replace(encode(credential_id, 'base64'), '+', '-'), '/', '_'), '='),
    ALTER COLUMN user_entity_user_id TYPE VARCHAR(255)
        USING rtrim(replace(replace(encode(user_entity_user_id, 'base64'), '+', '-'), '/', '_'), '=');
