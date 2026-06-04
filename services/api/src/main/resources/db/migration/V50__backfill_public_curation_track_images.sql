update public_curation_playlist_track public_track
set image_url = pms_track.album_image_url
from pms_user_track pms_track
where nullif(trim(public_track.image_url), '') is null
  and public_track.source_track_scope = 'pms_user_track'
  and public_track.source_track_id = cast(pms_track.track_id as varchar)
  and nullif(trim(pms_track.album_image_url), '') is not null;

update public_curation_playlist_track public_track
set image_url = ems_track.album_image_url
from ems_collected_track ems_track
where nullif(trim(public_track.image_url), '') is null
  and public_track.source_track_scope = 'ems_collected_track'
  and public_track.source_track_id = cast(ems_track.ems_collected_track_id as varchar)
  and nullif(trim(ems_track.album_image_url), '') is not null;
