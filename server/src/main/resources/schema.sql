-- This is the initial Axia schema. Every statement is intentionally additive and idempotent.
-- Schema evolution is managed explicitly; startup must never drop or rewrite existing data.

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    datagsm_user_id BIGINT UNSIGNED NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    email VARCHAR(254) NOT NULL,
    datagsm_account_status VARCHAR(64) NOT NULL,
    datagsm_student_role VARCHAR(64) NOT NULL,
    major_club_id BIGINT UNSIGNED NULL,
    major_club_name VARCHAR(128) NULL,
    datagsm_github_login VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deactivated_at DATETIME(6) NULL,
    profile_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    profile_synced_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_users_datagsm_user_id (datagsm_user_id),
    CONSTRAINT ck_users_datagsm_id CHECK (datagsm_user_id > 0),
    CONSTRAINT ck_users_profile_version CHECK (profile_version > 0),
    CONSTRAINT ck_users_major_club_snapshot CHECK (
        (major_club_id IS NULL AND major_club_name IS NULL)
        OR (major_club_id IS NOT NULL AND major_club_id > 0 AND major_club_name IS NOT NULL)
    ),
    CONSTRAINT ck_users_deactivation_state CHECK (
        (is_active = TRUE AND deactivated_at IS NULL)
        OR (is_active = FALSE AND deactivated_at IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_github_links (
    user_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NULL,
    github_login VARCHAR(255) NULL,
    organization_membership ENUM('UNKNOWN', 'MEMBER', 'NOT_MEMBER') NOT NULL DEFAULT 'UNKNOWN',
    membership_checked_at DATETIME(6) NULL,
    linked_at DATETIME(6) NULL,
    unlinked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    UNIQUE KEY ux_user_github_links_current_numeric_id (github_user_id),
    CONSTRAINT fk_user_github_links_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_github_links_identity_pair CHECK (
        (github_user_id IS NULL AND github_login IS NULL AND organization_membership = 'UNKNOWN')
        OR (
            github_user_id IS NOT NULL
            AND github_user_id > 0
            AND github_login IS NOT NULL
            AND linked_at IS NOT NULL
            AND unlinked_at IS NULL
        )
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS oauth_transactions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_hash BINARY(32) NOT NULL,
    poll_secret_hash BINARY(32) NOT NULL,
    pkce_challenge_digest BINARY(32) NOT NULL,
    nonce_hash BINARY(32) NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'EXPIRED') NOT NULL DEFAULT 'PENDING',
    approved_user_id BIGINT UNSIGNED NULL,
    failure_code VARCHAR(64) NULL,
    failure_display_name VARCHAR(128) NULL,
    device_name VARCHAR(128) NOT NULL,
    device_platform VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    processing_started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    terminal_delivered_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_oauth_transactions_state_hash (state_hash),
    UNIQUE KEY ux_oauth_transactions_poll_secret_hash (poll_secret_hash),
    KEY ix_oauth_transactions_expiry (status, expires_at),
    CONSTRAINT fk_oauth_transactions_approved_user
        FOREIGN KEY (approved_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_oauth_transactions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_oauth_transactions_terminal_state CHECK (
        (
            status = 'PENDING'
            AND approved_user_id IS NULL
            AND failure_code IS NULL
            AND failure_display_name IS NULL
            AND processing_started_at IS NULL
            AND completed_at IS NULL
            AND terminal_delivered_at IS NULL
        )
        OR (
            status = 'PROCESSING'
            AND approved_user_id IS NULL
            AND failure_code IS NULL
            AND failure_display_name IS NULL
            AND processing_started_at IS NOT NULL
            AND completed_at IS NULL
            AND terminal_delivered_at IS NULL
        )
        OR (
            status = 'SUCCEEDED'
            AND approved_user_id IS NOT NULL
            AND failure_code IS NULL
            AND failure_display_name IS NULL
            AND processing_started_at IS NOT NULL
            AND completed_at IS NOT NULL
        )
        OR (
            status = 'FAILED'
            AND approved_user_id IS NULL
            AND failure_code IS NOT NULL
            AND processing_started_at IS NOT NULL
            AND completed_at IS NOT NULL
        )
        OR (
            status = 'EXPIRED'
            AND approved_user_id IS NULL
            AND failure_code IS NOT NULL
            AND failure_display_name IS NULL
            AND completed_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_oauth_transactions_processing_time CHECK (
        processing_started_at IS NULL OR processing_started_at >= created_at
    ),
    CONSTRAINT ck_oauth_transactions_completion_time CHECK (
        completed_at IS NULL
        OR (
            completed_at >= created_at
            AND (processing_started_at IS NULL OR completed_at >= processing_started_at)
        )
    ),
    CONSTRAINT ck_oauth_transactions_terminal_delivery CHECK (
        terminal_delivered_at IS NULL
        OR (
            status IN ('SUCCEEDED', 'FAILED', 'EXPIRED')
            AND completed_at IS NOT NULL
            AND terminal_delivered_at >= completed_at
        )
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    dedupe_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_notifications_dedupe_key (dedupe_key),
    KEY ix_notifications_recipient_inbox (recipient_user_id, read_at, created_at),
    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notifications_read_time CHECK (read_at IS NULL OR read_at >= created_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS github_link_conflicts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    conflicting_datagsm_user_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    conflicting_display_name VARCHAR(128) NOT NULL,
    dedupe_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    notified_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_github_link_conflicts_dedupe_key (dedupe_key),
    KEY ix_github_link_conflicts_owner (owner_user_id, resolved_at),
    KEY ix_github_link_conflicts_episode (
        owner_user_id,
        conflicting_datagsm_user_id,
        github_user_id,
        resolved_at
    ),
    CONSTRAINT fk_github_link_conflicts_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_github_link_conflicts_datagsm_id CHECK (conflicting_datagsm_user_id > 0),
    CONSTRAINT ck_github_link_conflicts_github_id CHECK (github_user_id > 0),
    CONSTRAINT ck_github_link_conflicts_episode_state CHECK (
        (resolved_at IS NULL AND dedupe_key IS NOT NULL)
        OR (resolved_at IS NOT NULL AND dedupe_key IS NULL)
    ),
    CONSTRAINT ck_github_link_conflicts_notified_time CHECK (
        notified_at IS NULL OR notified_at >= created_at
    ),
    CONSTRAINT ck_github_link_conflicts_resolved_time CHECK (
        resolved_at IS NULL OR resolved_at >= created_at
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS desktop_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    device_platform VARCHAR(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    absolute_expires_at DATETIME(6)
        GENERATED ALWAYS AS (TIMESTAMPADD(DAY, 240, issued_at)) STORED NOT NULL,
    last_refreshed_at DATETIME(6) NULL,
    suspended_at DATETIME(6) NULL,
    suspension_reason VARCHAR(64) NULL,
    revoked_at DATETIME(6) NULL,
    revocation_reason VARCHAR(64) NULL,
    refresh_reuse_detected_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_desktop_sessions_user (user_id, absolute_expires_at),
    KEY ix_desktop_sessions_expiry (absolute_expires_at),
    CONSTRAINT fk_desktop_sessions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_desktop_sessions_refresh_time CHECK (
        last_refreshed_at IS NULL OR last_refreshed_at >= issued_at
    ),
    CONSTRAINT ck_desktop_sessions_suspension CHECK (
        (suspended_at IS NULL AND suspension_reason IS NULL)
        OR (suspended_at IS NOT NULL AND suspension_reason IS NOT NULL AND suspended_at >= issued_at)
    ),
    CONSTRAINT ck_desktop_sessions_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL AND revoked_at >= issued_at)
    ),
    CONSTRAINT ck_desktop_sessions_refresh_reuse CHECK (
        refresh_reuse_detected_at IS NULL
        OR (revoked_at IS NOT NULL AND refresh_reuse_detected_at >= issued_at)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS refresh_credentials (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    credential_hash BINARY(32) NOT NULL,
    hash_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumed_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    reuse_detected_at DATETIME(6) NULL,
    active_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE
                WHEN consumed_at IS NULL AND revoked_at IS NULL THEN session_id
                ELSE NULL
            END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY ux_refresh_credentials_hash (credential_hash),
    UNIQUE KEY ux_refresh_credentials_session_generation (session_id, generation),
    UNIQUE KEY ux_refresh_credentials_active_session (active_session_id),
    KEY ix_refresh_credentials_session (session_id, issued_at),
    CONSTRAINT fk_refresh_credentials_session
        FOREIGN KEY (session_id) REFERENCES desktop_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_refresh_credentials_generation CHECK (generation > 0),
    CONSTRAINT ck_refresh_credentials_consumed_at CHECK (
        consumed_at IS NULL OR consumed_at >= issued_at
    ),
    CONSTRAINT ck_refresh_credentials_revoked_at CHECK (
        revoked_at IS NULL OR revoked_at >= issued_at
    ),
    CONSTRAINT ck_refresh_credentials_reuse CHECK (
        reuse_detected_at IS NULL
        OR (consumed_at IS NOT NULL AND reuse_detected_at >= consumed_at)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
