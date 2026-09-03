-- Delivery attempts are per desktop session. A claim expires after a crash; acknowledgment never marks an inbox item read.
CREATE TABLE IF NOT EXISTS notification_deliveries (
    notification_id BIGINT UNSIGNED NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    PRIMARY KEY (notification_id, session_id),
    KEY ix_notification_delivery_claim (session_id, claim_id),
    CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_session FOREIGN KEY (session_id) REFERENCES desktop_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_delivery_lease CHECK (lease_until > claimed_at),
    CONSTRAINT ck_delivery_time CHECK (delivered_at IS NULL OR delivered_at >= claimed_at)
) ENGINE = InnoDB;
