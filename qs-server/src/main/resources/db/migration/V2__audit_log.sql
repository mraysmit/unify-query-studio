-- Audit log (technical design, security model): one row per run attempt.
CREATE TABLE audit_event (
    id            uuid PRIMARY KEY,
    at            timestamp(6) with time zone NOT NULL,
    user_name     varchar(255) NOT NULL,
    action        varchar(32)  NOT NULL,
    connection_id varchar(255) NOT NULL,
    run_id        varchar(36),
    sql_sha256    varchar(64),
    target_sql    text,
    row_count     integer,
    duration_ms   bigint,
    outcome       varchar(32)  NOT NULL,
    message       text
);

CREATE INDEX audit_event_at ON audit_event (at);
CREATE INDEX audit_event_user ON audit_event (user_name, at);
