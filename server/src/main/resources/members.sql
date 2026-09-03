-- Current authorization/availability and functional completion attribution; no generic change audit history.
-- Load after schema.sql and the products/task_projections schema.

-- Functional as-of snapshots for immutable completion attribution, not a generic audit log.
CREATE TABLE IF NOT EXISTS member_contribution_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    organization_role VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    positions_json JSON NOT NULL,
    pocs_json JSON NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NULL,
    current_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN valid_until IS NULL THEN user_id ELSE NULL END) STORED,
    current_github_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN valid_until IS NULL THEN github_user_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY ux_contribution_current_user (current_user_id),
    UNIQUE KEY ux_contribution_current_github (current_github_user_id),
    KEY ix_contribution_asof (github_user_id,valid_from,valid_until),
    CONSTRAINT fk_contribution_history_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_contribution_history_interval CHECK (valid_until IS NULL OR valid_until > valid_from)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS member_management_lock (
    id TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_member_management_lock_singleton CHECK (id = 1)
) ENGINE = InnoDB;

INSERT IGNORE INTO member_management_lock (id) VALUES (1);

CREATE TABLE IF NOT EXISTS organization_role_assignments (
    user_id BIGINT UNSIGNED NOT NULL,
    role ENUM(
        'TEAM_LEADER', 'SERVER_HEAD', 'CLOUD_HEAD', 'FRONT_HEAD', 'DESIGN_HEAD', 'C_LEADER',
        'HELLO_GSM_PO', 'DATA_GSM_PO', 'EVERY_GSM_PO', 'READY_GSM_PO'
    ) NOT NULL,
    singleton_role VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN role = 'TEAM_LEADER' THEN NULL ELSE CAST(role AS CHAR(32)) END
        ) STORED,
    PRIMARY KEY (user_id),
    UNIQUE KEY ux_organization_role_singleton (singleton_role),
    CONSTRAINT fk_organization_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_positions (
    user_id BIGINT UNSIGNED NOT NULL,
    position ENUM('SERVER', 'FRONTEND', 'CLOUD', 'DESIGN') NOT NULL,
    PRIMARY KEY (user_id, position),
    CONSTRAINT fk_user_positions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_pocs (
    user_id BIGINT UNSIGNED NOT NULL,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT fk_user_pocs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_pocs_product CHECK (product_id IN ('HELLO_GSM', 'DATA_GSM', 'EVERY_GSM', 'READY_GSM'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS explicit_admins (
    user_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_explicit_admin_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS task_write_grants (
    user_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    closed_occurrence_ids JSON NOT NULL,
    unassignment_occurrence_ids JSON NOT NULL,
    lifecycle_version BIGINT UNSIGNED NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, task_id),
    KEY ix_task_write_grants_task (task_id),
    CONSTRAINT fk_task_write_grants_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_write_grants_task FOREIGN KEY (task_id) REFERENCES task_projections (id) ON DELETE CASCADE,
    CONSTRAINT ck_task_write_grants_github_id CHECK (github_user_id > 0),
    CONSTRAINT ck_task_write_grants_lifecycle CHECK (lifecycle_version > 0),
    CONSTRAINT ck_task_write_grants_product CHECK (
        product_id IN ('HELLO_GSM', 'DATA_GSM', 'EVERY_GSM', 'READY_GSM')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS member_onboarding_flags (
    user_id BIGINT UNSIGNED NOT NULL,
    positionless_signup_processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_member_onboarding_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS member_bootstrap_state (
    id TINYINT UNSIGNED NOT NULL,
    status ENUM('DISABLED', 'PENDING', 'APPLIED', 'BLOCKED') NOT NULL,
    initialized_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_member_bootstrap_singleton CHECK (id = 1),
    CONSTRAINT ck_member_bootstrap_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status <> 'PENDING' AND completed_at IS NOT NULL)
    )
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS unavailability_periods (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    type ENUM('VACATION', 'WORK_UNAVAILABLE') NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_unavailability_user_period (user_id, starts_at, ends_at),
    KEY ix_unavailability_period (starts_at, ends_at),
    CONSTRAINT fk_unavailability_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_unavailability_finite_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_unavailability_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_unavailability_version CHECK (version > 0),
    CONSTRAINT ck_unavailability_update_time CHECK (updated_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
