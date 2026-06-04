create table if not exists ems_collected_playlist_source (
    ems_collected_playlist_source_id bigserial primary key,
    ems_collected_playlist_id bigint not null references ems_collected_playlist(ems_collected_playlist_id) on delete cascade,
    source_platform varchar(50) not null,
    collection_source varchar(50) not null,
    source_id varchar(200) not null,
    collected_at timestamptz not null,
    unique (ems_collected_playlist_id, source_platform, collection_source, source_id)
);

create index if not exists idx_ems_collected_playlist_source_browse
    on ems_collected_playlist_source (source_platform, collection_source, source_id, collected_at desc);

insert into ems_collected_playlist_source (
    ems_collected_playlist_id,
    source_platform,
    collection_source,
    source_id,
    collected_at
)
select ems_collected_playlist_id, source_platform, collection_source, search_query, collected_at
from ems_collected_playlist
where source_platform = 'tidal'
  and collection_source = 'public_pool'
  and search_query is not null
on conflict (ems_collected_playlist_id, source_platform, collection_source, source_id)
do update set collected_at = excluded.collected_at;
