-- V2: what authentication needs beyond V1 (plan, iteration 1).
-- Lockout (DI-31), session cut-off (DI-28) and one-time account links (DI-29).
-- Names follow docs/glosario_en.md (DI-20).

-- ---------------------------------------------------------------------------
-- Lockout and session cut-off on the user
-- ---------------------------------------------------------------------------
-- failed_login_attempts: wrong passwords in a row; a successful login resets it (DI-31).
-- locked_until: while it is in the future, the account cannot log in (DI-31).
-- sessions_valid_from: a token issued before this instant is rejected. It moves forward
--   when the password changes, which closes every open session (DI-28).
ALTER TABLE app_user
    ADD COLUMN failed_login_attempts integer     NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    ADD COLUMN locked_until          timestamptz,
    ADD COLUMN sessions_valid_from   timestamptz NOT NULL DEFAULT now();

-- ---------------------------------------------------------------------------
-- One-time links: invitation and password reset (D-11, DI-29)
-- ---------------------------------------------------------------------------
-- Only a hash of the token is stored, so a copy of the database cannot be used to
-- log in. A link is used once: used_at is set, never cleared, and the row is never
-- deleted (the API role has no DELETE).
CREATE TABLE account_link (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL REFERENCES app_user (id),
    purpose    text        NOT NULL CHECK (purpose IN ('INVITATION', 'PASSWORD_RESET')),
    token_hash text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR used_at >= created_at)
);

-- A link is looked up by its token hash.
CREATE UNIQUE INDEX account_link_token_hash_uq ON account_link (token_hash);

-- Password reset requests per email in the last hour (DI-31).
CREATE INDEX account_link_user_created_ix ON account_link (user_id, created_at);

-- ---------------------------------------------------------------------------
-- Privileges of the API role (DI-05): no DELETE, as in V1.
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON account_link TO ${appRole};
