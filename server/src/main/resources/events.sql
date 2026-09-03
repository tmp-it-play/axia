-- Shared signed webhook delivery receipts. Raw request bodies and credentials are never persisted.
CREATE TABLE IF NOT EXISTS github_sync_state (
    id TINYINT UNSIGNED NOT NULL,
    last_full_completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_github_sync_singleton CHECK (id = 1)
) ENGINE=InnoDB;
INSERT IGNORE INTO github_sync_state(id) VALUES (1);

-- Short-lived, payload-free intent/confirmation state for the failed-delivery recovery worker.
CREATE TABLE IF NOT EXISTS github_webhook_redeliveries (
    delivery_guid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_delivery_id BIGINT UNSIGNED NOT NULL,
    source_delivered_at DATETIME(6) NOT NULL,
    status ENUM('REQUESTING','ACCEPTED','UNCERTAIN','RETRYABLE','DELIVERED') NOT NULL,
    requested_at DATETIME(6) NULL,
    retry_at DATETIME(6) NULL,
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_seen_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_guid),
    KEY ix_github_redelivery_expiry (last_seen_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_assignment_events (
    occurrence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    assigned BOOLEAN NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (occurrence_id),
    CONSTRAINT fk_assignment_event_task FOREIGN KEY (task_id) REFERENCES task_projections(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS webhook_receipts (
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_sha256 BINARY(32) NOT NULL,
    event_type VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    attempts BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    received_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (source, delivery_id),
    KEY ix_webhook_receipts_retryable (source, status, lease_until),
    CONSTRAINT ck_webhook_receipts_source CHECK (source IN ('DATAGSM', 'GITHUB')),
    CONSTRAINT ck_webhook_receipts_lease CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_webhook_receipts_completion CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND completed_at IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Initial profile changes/revocations commit before external GitHub verification.
-- These jobs let the same delivery resume safely without replaying those lifecycle changes.
CREATE TABLE IF NOT EXISTS datagsm_event_verifications (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    datagsm_student_id BIGINT UNSIGNED NOT NULL,
    profile_version BIGINT UNSIGNED NOT NULL,
    github_login VARCHAR(255) NULL,
    status ENUM('PENDING', 'SUCCEEDED', 'STALE') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (event_id, user_id),
    KEY ix_datagsm_event_verifications_pending (event_id, status),
    CONSTRAINT fk_datagsm_event_verifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Never compare dispatch timestamps as if they were database revisions.
-- This ledger records exactly which delivery already applied its initial projection.
CREATE TABLE IF NOT EXISTS datagsm_event_projections (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
