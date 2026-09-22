-- Share requests: a watcher asks a friend (the future share owner / mover) to share arrivals at a place.
-- Privacy note: the place is still an opaque client-encrypted blob; sealed keys are opaque ciphertext.
-- Everything cascades on account deletion (users) and device deletion (devices).

CREATE TABLE share_requests (
    id               UUID         PRIMARY KEY,
    requester_id     VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,  -- the watcher
    target_id        VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,  -- the friend who would share
    encrypted_place  BYTEA        NOT NULL,             -- E2E ciphertext, opaque to the server
    transitions      VARCHAR(32)  NOT NULL,             -- comma-separated: ENTER,EXIT
    note             VARCHAR(140),                      -- optional short message typed by the requester
    status           VARCHAR(16)  NOT NULL,             -- PENDING | ACCEPTED | DECLINED
    share_id         UUID         REFERENCES shares (id) ON DELETE SET NULL,           -- set on accept
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,             -- 7-day TTL, purged hourly
    CONSTRAINT chk_share_requests_not_self CHECK (requester_id <> target_id),
    CONSTRAINT chk_share_requests_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED'))
);
CREATE INDEX idx_share_requests_requester ON share_requests (requester_id);
CREATE INDEX idx_share_requests_target ON share_requests (target_id);
CREATE INDEX idx_share_requests_expires ON share_requests (expires_at);
CREATE INDEX idx_share_requests_share ON share_requests (share_id);

-- Content key sealed per device. role OWNER = target's devices (they decrypt + geofence after accepting),
-- role RECIPIENT = requester's devices (they decrypt the place name when an event arrives).
CREATE TABLE share_request_keys (
    request_id    UUID         NOT NULL REFERENCES share_requests (id) ON DELETE CASCADE,
    device_id     VARCHAR(64)  NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    user_id       VARCHAR(128) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role          VARCHAR(16)  NOT NULL,
    sealed_key    BYTEA        NOT NULL,
    PRIMARY KEY (request_id, device_id),
    CONSTRAINT chk_share_request_keys_role CHECK (role IN ('OWNER', 'RECIPIENT'))
);
CREATE INDEX idx_share_request_keys_user ON share_request_keys (user_id);
CREATE INDEX idx_share_request_keys_device ON share_request_keys (device_id);

-- Content key sealed to the share OWNER's own devices. Needed when the owner did not encrypt the place
-- themselves (shares created by accepting a request) so the owner's device can decrypt and geofence it.
CREATE TABLE share_owner_keys (
    share_id      UUID         NOT NULL REFERENCES shares (id) ON DELETE CASCADE,
    device_id     VARCHAR(64)  NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    sealed_key    BYTEA        NOT NULL,
    PRIMARY KEY (share_id, device_id)
);
CREATE INDEX idx_share_owner_keys_device ON share_owner_keys (device_id);
