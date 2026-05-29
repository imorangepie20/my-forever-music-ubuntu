create table public_curation_playlist (
    public_curation_playlist_id bigserial primary key,
    slug varchar(180) not null,
    title varchar(240) not null,
    subtitle varchar(500),
    description text,
    prompt text,
    filter_snapshot_json text,
    status varchar(30) not null,
    cover_style varchar(120),
    model_version varchar(160),
    track_count integer not null,
    duration_ms bigint not null,
    published_at timestamptz,
    created_by_admin_user_id varchar(100) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create unique index uk_public_curation_playlist_slug
    on public_curation_playlist (slug);

create index idx_public_curation_playlist_status_published
    on public_curation_playlist (status, published_at desc, public_curation_playlist_id desc);

create table public_curation_playlist_track (
    public_curation_playlist_track_id bigserial primary key,
    playlist_id bigint not null references public_curation_playlist (public_curation_playlist_id) on delete cascade,
    track_order integer not null,
    source_track_scope varchar(80) not null,
    source_track_id varchar(200) not null,
    title varchar(300) not null,
    artist_name varchar(300) not null,
    album_title varchar(300),
    image_url varchar(1000),
    duration_ms integer,
    isrc varchar(32),
    tidal_track_id varchar(120) not null,
    tidal_uri varchar(240) not null,
    tidal_external_url varchar(500),
    score numeric(8, 5) not null,
    score_breakdown_json text,
    reason text,
    created_at timestamptz not null
);

create unique index uk_public_curation_playlist_track_order
    on public_curation_playlist_track (playlist_id, track_order);

create index idx_public_curation_playlist_track_source
    on public_curation_playlist_track (source_track_scope, source_track_id);

create index idx_public_curation_playlist_track_tidal
    on public_curation_playlist_track (tidal_track_id);

create table public_curation_run (
    public_curation_run_id bigserial primary key,
    playlist_id bigint not null references public_curation_playlist (public_curation_playlist_id) on delete cascade,
    prompt text,
    filter_snapshot_json text,
    candidate_count integer not null,
    selected_count integer not null,
    model_version varchar(160),
    status varchar(30) not null,
    score_summary_json text,
    error_message varchar(1000),
    started_at timestamptz not null,
    completed_at timestamptz
);

create index idx_public_curation_run_playlist_started
    on public_curation_run (playlist_id, started_at desc, public_curation_run_id desc);

create index idx_public_curation_run_status_started
    on public_curation_run (status, started_at desc);

create table public_playback_session (
    session_id varchar(120) primary key,
    playlist_id bigint not null references public_curation_playlist (public_curation_playlist_id) on delete cascade,
    tidal_account_label varchar(200),
    access_token_encrypted text not null,
    refresh_token_encrypted text,
    scope_summary varchar(500),
    expires_at timestamptz not null,
    created_at timestamptz not null,
    last_used_at timestamptz
);

create index idx_public_playback_session_playlist_expires
    on public_playback_session (playlist_id, expires_at desc);

create table public_playlist_play_event (
    public_playlist_play_event_id bigserial primary key,
    playlist_id bigint not null references public_curation_playlist (public_curation_playlist_id) on delete cascade,
    public_session_id varchar(120) references public_playback_session (session_id) on delete set null,
    track_id bigint references public_curation_playlist_track (public_curation_playlist_track_id) on delete set null,
    event_type varchar(40) not null,
    position_ms integer,
    duration_ms integer,
    occurred_at timestamptz not null,
    received_at timestamptz not null
);

create index idx_public_playlist_play_event_playlist_received
    on public_playlist_play_event (playlist_id, received_at desc, public_playlist_play_event_id desc);

create index idx_public_playlist_play_event_session_received
    on public_playlist_play_event (public_session_id, received_at desc)
    where public_session_id is not null;
