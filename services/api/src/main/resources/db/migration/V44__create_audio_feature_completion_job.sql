create table audio_feature_completion_job (
    audio_feature_completion_job_id bigserial primary key,
    track_scope varchar(80) not null,
    track_id varchar(200) not null,
    user_id varchar(100),
    priority integer not null,
    status varchar(30) not null,
    requested_reason varchar(80) not null,
    attempt_count integer not null default 0,
    next_retry_at timestamptz,
    locked_at timestamptz,
    locked_by varchar(120),
    last_error varchar(1000),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create unique index uk_audio_feature_completion_identity
    on audio_feature_completion_job (track_scope, track_id, requested_reason);

create index idx_audio_feature_completion_status_priority
    on audio_feature_completion_job (status, priority desc, created_at asc, audio_feature_completion_job_id asc);

create index idx_audio_feature_completion_user_status
    on audio_feature_completion_job (user_id, status, created_at desc)
    where user_id is not null;
