-- Initial task/product schema; no destructive migration or historical backfill.
CREATE TABLE IF NOT EXISTS products (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO products (id, display_name) VALUES
    ('HELLO_GSM', 'Hello,GSM'), ('DATA_GSM', 'DataGSM'),
    ('EVERY_GSM', 'EveryGSM'), ('READY_GSM', 'Ready,GSM');

CREATE TABLE IF NOT EXISTS project_integrations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    github_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    project_number INT UNSIGNED NOT NULL,
    title VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    access_state ENUM('PENDING','AVAILABLE','UNAVAILABLE') NOT NULL DEFAULT 'PENDING',
    repository_link_warning BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_project_node (github_node_id),
    UNIQUE KEY ux_project_number (project_number),
    UNIQUE KEY ux_project_product (id, product_id),
    CONSTRAINT fk_project_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT ck_project_not_template CHECK (project_number > 0 AND project_number <> 19),
    CONSTRAINT ck_project_available CHECK (enabled = FALSE OR (github_node_id IS NOT NULL AND access_state = 'AVAILABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS repository_integrations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    github_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    owner VARCHAR(100) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    access_state ENUM('PENDING','AVAILABLE','UNAVAILABLE') NOT NULL DEFAULT 'PENDING',
    tracking_started_at DATETIME(6) NULL,
    default_project_id BIGINT UNSIGNED NULL,
    last_incremental_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_repository_node (github_node_id),
    UNIQUE KEY ux_repository_name (owner, name),
    UNIQUE KEY ux_repository_product (id, product_id),
    CONSTRAINT fk_repository_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_repository_default_project FOREIGN KEY (default_project_id, product_id) REFERENCES project_integrations(id, product_id),
    CONSTRAINT ck_repository_available CHECK (enabled = FALSE OR (github_node_id IS NOT NULL AND tracking_started_at IS NOT NULL AND access_state = 'AVAILABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_field_mappings (
    project_id BIGINT UNSIGNED NOT NULL,
    semantic ENUM('STATUS','PRIORITY','START_DATE','TARGET_DATE') NOT NULL,
    field_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    option_ids_json JSON NOT NULL,
    PRIMARY KEY (project_id, semantic),
    UNIQUE KEY ux_project_field (project_id, field_node_id),
    CONSTRAINT fk_field_project FOREIGN KEY (project_id) REFERENCES project_integrations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS task_projections (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    canonical_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repository_id BIGINT UNSIGNED NULL,
    kind ENUM('ISSUE','PULL_REQUEST','DRAFT') NOT NULL,
    number INT UNSIGNED NULL,
    title VARCHAR(1024) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    is_open BOOLEAN NOT NULL,
    is_completed BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    converted_at DATETIME(6) NULL,
    lifecycle_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    snapshot_json JSON NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_task_canonical (canonical_node_id),
    UNIQUE KEY ux_task_repository_number (repository_id, number),
    KEY ix_task_product_open (product_id, is_open, updated_at),
    CONSTRAINT fk_task_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_task_repository_product FOREIGN KEY (repository_id, product_id) REFERENCES repository_integrations(id, product_id),
    CONSTRAINT ck_task_source CHECK ((kind = 'DRAFT' AND repository_id IS NULL AND number IS NULL) OR (kind <> 'DRAFT' AND repository_id IS NOT NULL AND number > 0)),
    CONSTRAINT ck_task_lifecycle CHECK (lifecycle_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS task_assignees (
    task_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    github_login VARCHAR(100) NOT NULL,
    github_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    user_id BIGINT UNSIGNED NULL,
    PRIMARY KEY (task_id, github_user_id),
    KEY ix_assignee_user (user_id, task_id),
    CONSTRAINT fk_assignee_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT fk_assignee_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_assignee_numeric CHECK (github_user_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS task_projects (
    task_id BIGINT UNSIGNED NOT NULL,
    project_id BIGINT UNSIGNED NOT NULL,
    item_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    status ENUM('TODO','IN_PROGRESS','DONE','UNSET') NOT NULL DEFAULT 'UNSET',
    priority ENUM('HIGH','MEDIUM','LOW','IN_REVIEW','UNSET') NOT NULL DEFAULT 'UNSET',
    start_date DATE NULL,
    target_date DATE NULL,
    PRIMARY KEY (task_id, project_id),
    UNIQUE KEY ux_project_item (item_node_id),
    CONSTRAINT fk_task_project_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT fk_task_project_board FOREIGN KEY (project_id) REFERENCES project_integrations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS review_assignments (
    task_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    github_login VARCHAR(100) NOT NULL,
    github_node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    user_id BIGINT UNSIGNED NULL,
    request_occurrence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (task_id, github_user_id),
    KEY ix_review_user_active (user_id, active),
    CONSTRAINT fk_review_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS review_task_write_grants (
    user_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    request_occurrence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    closed_occurrence_ids JSON NOT NULL,
    lifecycle_version BIGINT UNSIGNED NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, task_id, github_user_id, request_occurrence_id),
    KEY ix_review_write_grant_task (task_id),
    CONSTRAINT fk_review_write_grant_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_review_write_grant_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT fk_review_write_grant_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT ck_review_write_grant_numeric CHECK (github_user_id > 0),
    CONSTRAINT ck_review_write_grant_lifecycle CHECK (lifecycle_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS completion_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    occurrence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    product_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind ENUM('ISSUE_CLOSE','PR_MERGE') NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    processing_seconds BIGINT UNSIGNED NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_completion_occurrence (occurrence_id),
    KEY ix_completion_product_time (product_id, occurred_at),
    CONSTRAINT fk_completion_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT fk_completion_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS completion_credits (
    event_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    organization_role VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    positions_json JSON NOT NULL,
    pocs_json JSON NOT NULL,
    PRIMARY KEY (event_id, user_id),
    KEY ix_credit_user (user_id, event_id),
    CONSTRAINT fk_credit_event FOREIGN KEY (event_id) REFERENCES completion_events(id),
    CONSTRAINT fk_credit_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS global_reminder_policies (
    kind ENUM('ISSUE','PULL_REQUEST','REVIEW_REQUEST','DRAFT') NOT NULL,
    hours INT UNSIGNED NOT NULL,
    PRIMARY KEY (kind),
    CONSTRAINT ck_global_reminder_hours CHECK (hours > 0 AND hours <= 87600)
) ENGINE=InnoDB;

INSERT IGNORE INTO global_reminder_policies (kind,hours) VALUES
    ('ISSUE',336),('DRAFT',336),('PULL_REQUEST',72),('REVIEW_REQUEST',72);

CREATE TABLE IF NOT EXISTS reminder_policies (
    project_id BIGINT UNSIGNED NOT NULL,
    priority VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hours INT UNSIGNED NOT NULL,
    PRIMARY KEY (project_id, priority),
    CONSTRAINT fk_reminder_policy_project FOREIGN KEY (project_id) REFERENCES project_integrations(id),
    CONSTRAINT ck_reminder_policy_hours CHECK (hours > 0 AND hours <= 87600),
    CONSTRAINT ck_reminder_priority CHECK (priority IN ('DEFAULT','HIGH','MEDIUM','LOW','IN_REVIEW','UNSET'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_reminder_overrides (
    task_id BIGINT UNSIGNED NOT NULL,
    hours INT UNSIGNED NULL,
    suppressed BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_version BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT fk_reminder_override_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT ck_reminder_override_hours CHECK (hours IS NULL OR (hours > 0 AND hours <= 87600))
) ENGINE=InnoDB;

-- A review request is a separate assignment cycle, not the canonical PR's reminder setting.
CREATE TABLE IF NOT EXISTS review_reminder_overrides (
    task_id BIGINT UNSIGNED NOT NULL,
    github_user_id BIGINT UNSIGNED NOT NULL,
    request_occurrence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hours INT UNSIGNED NULL,
    suppressed BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_version BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (task_id, github_user_id, request_occurrence_id),
    CONSTRAINT fk_review_reminder_task FOREIGN KEY (task_id) REFERENCES task_projections(id),
    CONSTRAINT ck_review_reminder_hours CHECK (hours IS NULL OR (hours > 0 AND hours <= 87600)),
    CONSTRAINT ck_review_reminder_github_id CHECK (github_user_id > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reminder_states (
    task_id BIGINT UNSIGNED NOT NULL,
    assignee_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle_version BIGINT UNSIGNED NOT NULL,
    due_at DATETIME(6) NULL,
    first_sent_at DATETIME(6) NULL,
    next_due_at DATETIME(6) NULL,
    target_date DATE NULL,
    imminent_handled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (task_id, assignee_key),
    CONSTRAINT fk_reminder_state_task FOREIGN KEY (task_id) REFERENCES task_projections(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS provider_health (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state ENUM('HEALTHY','DEGRADED','RECOVERING') NOT NULL DEFAULT 'HEALTHY',
    failure_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    retry_at DATETIME(6) NULL,
    changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (provider)
) ENGINE=InnoDB;

INSERT IGNORE INTO provider_health (provider) VALUES ('GITHUB');

CREATE TABLE IF NOT EXISTS scheduler_leases (
    job_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 1,
    consecutive_failures INT UNSIGNED NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NOT NULL,
    next_run_at DATETIME(6) NOT NULL,
    PRIMARY KEY (job_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS scheduler_requests (
    job_name VARCHAR(128) NOT NULL PRIMARY KEY,
    pending BOOLEAN NOT NULL DEFAULT FALSE,
    bypass_failure_backoff BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS external_write_operations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NULL,
    request_hash BINARY(32) NOT NULL,
    kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status ENUM('PREPARED','IN_FLIGHT','CONFIRMED','UNKNOWN','FAILED') NOT NULL,
    result_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_external_operation_expiry (expires_at),
    CONSTRAINT fk_external_operation_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_external_operation_task FOREIGN KEY (task_id) REFERENCES task_projections(id)
) ENGINE=InnoDB;

-- Candidates remain disabled until installation access and immutable GitHub identity are checked.
INSERT IGNORE INTO project_integrations (product_id,project_number) VALUES
    ('DATA_GSM',23),('DATA_GSM',24),('HELLO_GSM',25),('HELLO_GSM',26),
    ('READY_GSM',27),('READY_GSM',28),('EVERY_GSM',29),('EVERY_GSM',30);

INSERT IGNORE INTO repository_integrations (product_id,owner,name) VALUES
    ('HELLO_GSM','themoment-team','hellogsm-server-26'),
    ('HELLO_GSM','themoment-team','hellogsm-client-26'),
    ('HELLO_GSM','themoment-team','go-hellogsm'),
    ('HELLO_GSM','themoment-team','go-hellogsm-score-calculator'),
    ('HELLO_GSM','themoment-team','go-hellogsm-ops'),
    ('DATA_GSM','themoment-team','datagsm-server'),
    ('DATA_GSM','themoment-team','datagsm-client'),
    ('DATA_GSM','themoment-team','datagsm-oauth-sdk-java'),
    ('DATA_GSM','themoment-team','datagsm-openapi-sdk-java'),
    ('DATA_GSM','themoment-team','datagsm-oauth-sdk-react'),
    ('DATA_GSM','themoment-team','datagsm-openapi-sdk-python'),
    ('DATA_GSM','themoment-team','datagsm-openapi-sdk-javascript'),
    ('EVERY_GSM','themoment-team','everygsm-server-v2'),
    ('EVERY_GSM','themoment-team','everygsm-client-v2'),
    ('READY_GSM','themoment-team','readygsm-server'),
    ('READY_GSM','themoment-team','readygsm-client');
