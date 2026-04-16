-- PostgreSQL schema for Sentinel AI
-- Run once: psql -U postgres -d sentinel_ai -f schema-pg.sql

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS t_project_config (
    id              BIGSERIAL PRIMARY KEY,
    project_name    VARCHAR(100) NOT NULL,
    git_repo_path   VARCHAR(500),
    git_remote_url  VARCHAR(500),
    git_branch      VARCHAR(100) DEFAULT 'master',
    webhook_secret  VARCHAR(200),
    git_project_path VARCHAR(200),
    last_scan_commit VARCHAR(64),
    last_scan_time  TIMESTAMP,
    table_schema_source VARCHAR(20) DEFAULT 'MANUAL',
    db_connection_url VARCHAR(500),
    status          SMALLINT DEFAULT 1,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Migration: add git integration fields
-- ALTER TABLE t_project_config ADD COLUMN IF NOT EXISTS git_remote_url VARCHAR(500);
-- ALTER TABLE t_project_config ADD COLUMN IF NOT EXISTS git_branch VARCHAR(100) DEFAULT 'master';
-- ALTER TABLE t_project_config ADD COLUMN IF NOT EXISTS webhook_secret VARCHAR(200);
-- ALTER TABLE t_project_config ALTER COLUMN git_repo_path DROP NOT NULL;
-- ALTER TABLE t_project_config ADD COLUMN IF NOT EXISTS git_project_path VARCHAR(200);
-- (git_platform/git_api_url/git_access_token moved to global config file sentinel.git.*)

CREATE TABLE IF NOT EXISTS t_table_meta (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT,
    table_name      VARCHAR(128) NOT NULL,
    ddl_text        TEXT,
    estimated_rows  BIGINT DEFAULT 0,
    avg_row_length  INT DEFAULT 0,
    index_info      TEXT,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_sql_record (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    sql_hash        VARCHAR(64) NOT NULL,
    sql_text        TEXT NOT NULL,
    sql_normalized  TEXT,
    sql_type        VARCHAR(10),
    source_type     VARCHAR(20) NOT NULL,
    source_file     VARCHAR(500) NOT NULL,
    source_location VARCHAR(200),
    owner           VARCHAR(50),
    first_scan_id   BIGINT,
    last_scan_id    BIGINT,
    status          SMALLINT DEFAULT 1,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sql_record_project_status ON t_sql_record (project_id, status);
CREATE INDEX IF NOT EXISTS idx_sql_record_sql_type ON t_sql_record (sql_type);
CREATE INDEX IF NOT EXISTS idx_sql_record_source_type ON t_sql_record (source_type);
CREATE INDEX IF NOT EXISTS idx_sql_record_sql_hash ON t_sql_record (sql_hash);
CREATE INDEX IF NOT EXISTS idx_sql_record_update_time ON t_sql_record (update_time DESC);

CREATE TABLE IF NOT EXISTS t_scan_batch (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    scan_type       VARCHAR(20) NOT NULL,
    from_commit     VARCHAR(64),
    to_commit       VARCHAR(64),
    total_sql_count INT DEFAULT 0,
    new_sql_count   INT DEFAULT 0,
    changed_sql_count INT DEFAULT 0,
    removed_sql_count INT DEFAULT 0,
    risk_sql_count  INT DEFAULT 0,
    scan_duration_ms BIGINT,
    status          VARCHAR(20) DEFAULT 'RUNNING',
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_sql_analysis (
    id                      BIGSERIAL PRIMARY KEY,
    sql_record_id           BIGINT NOT NULL,
    scan_batch_id           BIGINT NOT NULL,
    rule_risk_level         VARCHAR(10),
    rule_issues             TEXT,
    ai_risk_level           VARCHAR(10),
    ai_analysis             TEXT,
    ai_index_suggestion     TEXT,
    ai_rewrite_suggestion   TEXT,
    ai_estimated_scan_rows  BIGINT,
    ai_estimated_exec_time_ms INT,
    ai_confidence           DECIMAL(3,2),
    ai_model                VARCHAR(50),
    ai_tokens_used          INT,
    final_risk_level        VARCHAR(10),
    handle_status           VARCHAR(20) DEFAULT 'PENDING',
    handle_note             TEXT,
    handler                 VARCHAR(50),
    handle_time             TIMESTAMP,
    create_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_exemption_rule (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT,
    rule_type       VARCHAR(30) NOT NULL,
    pattern         VARCHAR(500) NOT NULL,
    reason          VARCHAR(500),
    status          SMALLINT DEFAULT 1,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_prompt_template (
    id              BIGSERIAL PRIMARY KEY,
    template_key    VARCHAR(50) NOT NULL UNIQUE,
    template_name   VARCHAR(100) NOT NULL,
    content         TEXT NOT NULL,
    description     VARCHAR(500),
    status          SMALLINT DEFAULT 1,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_knowledge_entry (
    id              BIGSERIAL PRIMARY KEY,
    knowledge_type  VARCHAR(30) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT NOT NULL,
    related_tables  VARCHAR(500),
    source          VARCHAR(20) DEFAULT 'MANUAL',
    embedded        SMALLINT DEFAULT 0,
    status          SMALLINT DEFAULT 1,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
