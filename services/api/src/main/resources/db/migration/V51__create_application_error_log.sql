create table if not exists application_error_log (
    application_error_log_id bigserial primary key,
    source varchar(80) not null,
    severity varchar(20) not null,
    service varchar(80) not null default 'api',
    status_code integer,
    error_type varchar(255),
    message text not null,
    stack_trace text,
    request_method varchar(20),
    request_path varchar(500),
    user_id varchar(100),
    trace_id varchar(100),
    fingerprint varchar(128) not null,
    occurrence_count integer not null default 1,
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    resolved_at timestamptz,
    context_json jsonb
);

create index if not exists idx_application_error_log_recent
    on application_error_log (last_seen_at desc);

create index if not exists idx_application_error_log_source_recent
    on application_error_log (source, last_seen_at desc);

create index if not exists idx_application_error_log_severity_recent
    on application_error_log (severity, last_seen_at desc);
