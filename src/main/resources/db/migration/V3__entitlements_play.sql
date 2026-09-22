-- Real Google Play verification: store the Play lifecycle state instead of a bare `pro` flag.
-- Named V3 (not V2) to avoid a version clash with a parallel branch that also adds a V2 migration.
--
-- Token storage decision: `token_hash` = hex SHA-256 of the purchase token, UNIQUE. It drives RTDN lookups and
-- the one-token-one-account rule. The raw `purchase_token` is kept because re-verification must send it to the
-- Play Developer API; on its own it grants nothing (it only works together with our service-account key).
-- Still one row per user, ON DELETE CASCADE from users (account deletion removes it).

ALTER TABLE entitlements
    ADD COLUMN token_hash     CHAR(64),
    ADD COLUMN state          VARCHAR(32),
    ADD COLUMN auto_renewing  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledged   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN test_purchase  BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill rows written by the stub verifier.
UPDATE entitlements
SET token_hash = encode(sha256(convert_to(purchase_token, 'UTF8')), 'hex'),
    state      = CASE WHEN pro THEN 'ACTIVE' ELSE 'INVALID' END;

ALTER TABLE entitlements
    ALTER COLUMN token_hash SET NOT NULL,
    ALTER COLUMN state SET NOT NULL,
    ADD CONSTRAINT uq_entitlements_token_hash UNIQUE (token_hash),
    ADD CONSTRAINT chk_entitlements_state CHECK (state IN (
        'ACTIVE', 'IN_GRACE_PERIOD', 'CANCELED', 'ON_HOLD', 'PAUSED', 'PENDING',
        'EXPIRED', 'REVOKED', 'REPLACED', 'INVALID'
    ));

-- The hash replaces the unique index on the raw token.
ALTER TABLE entitlements DROP CONSTRAINT entitlements_purchase_token_key;
ALTER TABLE entitlements DROP COLUMN pro;
ALTER TABLE entitlements RENAME COLUMN verified_at TO last_verified_at;
