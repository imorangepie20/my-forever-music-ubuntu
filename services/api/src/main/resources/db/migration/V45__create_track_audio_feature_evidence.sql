create table track_audio_feature_evidence (
    track_audio_feature_evidence_id bigserial primary key,
    track_scope varchar(80) not null,
    track_id varchar(200) not null,
    source_name varchar(80) not null,
    source_class varchar(50) not null,
    source_url varchar(500),
    evidence_kind varchar(80) not null,
    evidence_payload_json text not null,
    confidence numeric(6, 5) not null,
    collected_at timestamptz not null,
    expires_at timestamptz
);

create index idx_track_audio_feature_evidence_track
    on track_audio_feature_evidence (track_scope, track_id, collected_at desc);

create index idx_track_audio_feature_evidence_source_class
    on track_audio_feature_evidence (source_class, collected_at desc);
