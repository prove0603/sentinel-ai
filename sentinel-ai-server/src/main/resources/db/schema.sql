CREATE TABLE IF NOT EXISTS t_project_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_name        VARCHAR(100) NOT NULL,
    git_repo_path       VARCHAR(500) NOT NULL,
    last_scan_commit    VARCHAR(64),
    last_scan_time      DATETIME,
    table_schema_source VARCHAR(20) DEFAULT 'MANUAL',
    db_connection_url   VARCHAR(500),
    status              TINYINT DEFAULT 1,
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_table_meta (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT,
    table_name      VARCHAR(128) NOT NULL,
    ddl_text        CLOB,
    estimated_rows  BIGINT DEFAULT 0,
    avg_row_length  INT DEFAULT 0,
    index_info      CLOB,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_sql_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    sql_hash        VARCHAR(64) NOT NULL,
    sql_text        CLOB NOT NULL,
    sql_normalized  CLOB,
    sql_type        VARCHAR(10),
    source_type     VARCHAR(20) NOT NULL,
    source_file     VARCHAR(500) NOT NULL,
    source_location VARCHAR(200),
    first_scan_id   BIGINT,
    last_scan_id    BIGINT,
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_scan_batch (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id          BIGINT NOT NULL,
    scan_type           VARCHAR(20) NOT NULL,
    from_commit         VARCHAR(64),
    to_commit           VARCHAR(64),
    total_sql_count     INT DEFAULT 0,
    new_sql_count       INT DEFAULT 0,
    changed_sql_count   INT DEFAULT 0,
    removed_sql_count   INT DEFAULT 0,
    risk_sql_count      INT DEFAULT 0,
    scan_duration_ms    BIGINT,
    status              VARCHAR(20) DEFAULT 'RUNNING',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_sql_analysis (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    sql_record_id           BIGINT NOT NULL,
    scan_batch_id           BIGINT NOT NULL,
    rule_risk_level         VARCHAR(10),
    rule_issues             CLOB,
    ai_risk_level           VARCHAR(10),
    ai_analysis             CLOB,
    ai_index_suggestion     CLOB,
    ai_rewrite_suggestion   CLOB,
    ai_estimated_scan_rows  BIGINT,
    ai_estimated_exec_time_ms INT,
    ai_confidence           DECIMAL(3,2),
    ai_model                VARCHAR(50),
    ai_tokens_used          INT,
    final_risk_level        VARCHAR(10),
    handle_status           VARCHAR(20) DEFAULT 'PENDING',
    handle_note             CLOB,
    handler                 VARCHAR(50),
    handle_time             DATETIME,
    create_time             DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_knowledge_entry (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_type      VARCHAR(30) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    content             CLOB NOT NULL,
    related_tables      VARCHAR(500),
    source              VARCHAR(20) DEFAULT 'MANUAL',
    embedded            TINYINT DEFAULT 0,
    status              TINYINT DEFAULT 1,
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP
);
