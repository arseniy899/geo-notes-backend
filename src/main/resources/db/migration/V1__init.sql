-- WhenHere backend schema.
-- Privacy note: no table stores coordinates. Shared places are opaque, client-encrypted blobs.

CREATE TABLE users (
    id            VARCHAR(128) PRIMARY KEY,           -- Firebase UID
    display_name  VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE devices (
    id            VARCHAR(64)  PRIMARY KEY,           -- client-generated installation id
    user_id       VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    fcm_token     TEXT,
    public_key    BYTEA        NOT NULL,
    platform      VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_devices_user ON devices (user_id);

CREATE TABLE invites (
    code          VARCHAR(16)  PRIMARY KEY,
    inviter_id    VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    accepted_by   VARCHAR(128) REFERENCES users (id) ON DELETE CASCADE,
    accepted_at   TIMESTAMPTZ
);
CREATE INDEX idx_invites_inviter ON invites (inviter_id);
CREATE INDEX idx_invites_expires ON invites (expires_at);

-- One row per friendship, canonical ordering user_a < user_b.
CREATE TABLE friendships (
    user_a        VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_b        VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_a, user_b),
    CONSTRAINT chk_friendships_order CHECK (user_a < user_b)
);
CREATE INDEX idx_friendships_user_b ON friendships (user_b);

CREATE TABLE shares (
    id               UUID         PRIMARY KEY,
    owner_id         VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    encrypted_place  BYTEA        NOT NULL,             -- E2E ciphertext, opaque to the server
    transitions      VARCHAR(32)  NOT NULL,             -- comma-separated: ENTER,EXIT
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    paused_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_shares_owner ON shares (owner_id);

CREATE TABLE share_recipients (
    share_id      UUID         NOT NULL REFERENCES shares (id) ON DELETE CASCADE,
    device_id     VARCHAR(64)  NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    user_id       VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sealed_key    BYTEA        NOT NULL,                -- content key sealed to the device public key
    PRIMARY KEY (share_id, device_id)
);
CREATE INDEX idx_share_recipients_user ON share_recipients (user_id);

CREATE TABLE events (
    id            UUID         PRIMARY KEY,
    share_id      UUID         NOT NULL REFERENCES shares (id) ON DELETE CASCADE,
    owner_id      VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    transition    VARCHAR(8)   NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL                 -- 7-day TTL, purged hourly
);
CREATE INDEX idx_events_share ON events (share_id);
CREATE INDEX idx_events_expires ON events (expires_at);

CREATE TABLE entitlements (
    user_id         VARCHAR(128) PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    product_id      VARCHAR(64)  NOT NULL,
    purchase_token  TEXT         NOT NULL UNIQUE,
    pro             BOOLEAN      NOT NULL,
    expires_at      TIMESTAMPTZ,
    verified_at     TIMESTAMPTZ  NOT NULL
);
