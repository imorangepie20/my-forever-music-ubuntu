export type WorkspacePlatformId = 'spotify' | 'apple-music' | 'tidal' | 'youtube-music' | 'last-fm'

export interface RichPlaylistArtwork {
    cover_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
}

export interface RichTrackArtwork {
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    preview_url: string | null
    duration_ms: number | null
}

export interface SystemInfoResponse {
    service: string
    status: string
    message: string
    timestamp: string
}

export interface SchedulingAdminScheduleItem {
    id: string
    domain: string
    name: string
    mode: string
    enabled: boolean
    configured: boolean
    status: 'active' | 'blocked' | 'disabled' | string
    fixed_delay_ms: number | null
    initial_delay_ms: number | null
    cadence_label: string
    purpose: string
    management_path: string
    last_status: string | null
    last_message: string | null
    last_started_at: string | null
    last_completed_at: string | null
    config_keys: string[]
    notes: string[]
}

export interface SchedulingAdminResponse {
    service: string
    status: string
    generated_at: string
    schedules: SchedulingAdminScheduleItem[]
    recommendations: string[]
}

export interface PublicCurationShareTrack {
    track_id: number
    track_order: number
    title: string
    artist_name: string
    album_title: string | null
    image_url: string | null
    duration_ms: number | null
    isrc: string | null
    tidal_track_id: string
    tidal_uri: string
    tidal_external_url: string | null
    score: number
    score_breakdown_json: string | null
    reason: string | null
}

export interface PublicCurationShareResponse {
    service: string
    status: string
    playlist: {
        playlist_id: number
        slug: string
        title: string
        subtitle: string | null
        description: string | null
        cover_style: string | null
        model_version: string | null
        track_count: number
        duration_ms: number
        published_at: string | null
        tracks: PublicCurationShareTrack[]
    }
}

export interface PublicCurationAudioFeatureRange {
    min: number | null
    max: number | null
}

export interface PublicCurationAdminRunRequest {
    admin_user_id: string
    slug: string
    prompt: string
    target_track_count: number
    candidate_limit: number
    cover_style: string
    filters: {
        mood_tags: string[]
        genre_tags: string[]
        audio_feature_ranges: Record<string, PublicCurationAudioFeatureRange>
    }
}

export interface PublicCurationAdminTrack {
    track_id: number
    track_order: number
    title: string
    artist_name: string
    album_title: string | null
    duration_ms: number | null
    tidal_track_id: string
    tidal_uri: string
    tidal_external_url: string | null
    score: number
    reason: string | null
}

export interface PublicCurationAdminRunResponse {
    service: string
    status: 'draft_created' | 'published' | string
    playlist: {
        playlist_id: number
        slug: string
        title: string
        subtitle: string | null
        status: string
        track_count: number
        duration_ms: number
        model_version: string | null
        tracks: PublicCurationAdminTrack[]
    }
}

export interface AuthRegistrationRequest {
    display_name: string
    email: string
    password: string
    preferred_platform_id: WorkspacePlatformId
    marketing_opt_in: boolean
    accepted_terms: boolean
    accepted_privacy_policy: boolean
}

export interface AuthRegistrationResponse {
    service: string
    status: string
    registered_at: string
    user: {
        user_id: string
        email: string
        display_name: string
        email_verified: boolean
    }
    onboarding: {
        stage: string
        preferred_platform_id: WorkspacePlatformId
        platform_connection_required: boolean
        next_step_path: string
        next_step_message: string
    }
}

export interface AuthLoginRequest {
    email: string
    password: string
}

export interface AuthLoginResponse {
    service: string
    status: string
    authenticated_at: string
    user: {
        user_id: string
        email: string
        display_name: string
        email_verified: boolean
    }
    onboarding: {
        stage: string
        preferred_platform_id: WorkspacePlatformId
        platform_connection_required: boolean
        next_step_path: string
        next_step_message: string
    }
}

export interface PlatformCatalogResponse {
    service: string
    status: string
    generated_at: string
    primary_audio_feature_source: string
    onboarding_flow: string[]
    platforms: Array<{
        platform_id: WorkspacePlatformId
        display_name: string
        integration_stage: string
        pms_import_supported: boolean
        ems_collection_supported: boolean
        audio_feature_strategy: string
        pms_role: string
        ems_role: string
        notes: string[]
    }>
}

export interface PlatformConnectionBootstrapResponse {
    service: string
    status: string
    generated_at: string
    user: {
        user_id: string
        display_name: string
        email: string
        preferred_platform_id: WorkspacePlatformId
        last_fm_username: string | null
        last_fm_connected_at: string | null
    }
    summary: {
        connected_platform_count: number
        preferred_platform_connected: boolean
        preferred_platform_reconnect_required: boolean
        onboarding_stage: string
        next_step_path: string
        next_step_message: string
    }
    connections: Array<{
        platform_id: WorkspacePlatformId
        display_name: string
        preferred: boolean
        connected: boolean
        connection_status: string
        connection_mode: string | null
        external_account_label: string | null
        sync_ready: boolean
        credential_status: 'ready' | 'missing' | 'reconnect_required'
        reconnect_required: boolean
        connected_at: string | null
        next_action_label: string
    }>
}

export interface PlatformConnectRequest {
    user_id: string
    platform_id: WorkspacePlatformId
    connection_mode?: string
    external_account_label?: string
}

export interface PlatformDisconnectRequest {
    user_id: string
    platform_id: WorkspacePlatformId
}

export interface PlatformConnectionCommandResponse {
    service: string
    status: string
    processed_at: string
    connection: {
        user_id: string
        platform_id: WorkspacePlatformId
        display_name: string
        connected: boolean
        connection_status: string
        connection_mode: string
        external_account_label: string | null
        scope_summary: string | null
        sync_ready: boolean
        connected_at: string | null
    }
    next_step: {
        path: string
        message: string
    }
}

export interface PlatformPlaybackCredentialsResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    platform_id: WorkspacePlatformId | string
    access_token: string
    token_type: string | null
    scope_summary: string | null
    scopes: string[]
    expires_at: string | null
    external_user_id: string | null
    external_account_label: string | null
    authorization_mode: string | null
    client_id: string | null
    country_code: string | null
}

export interface TidalPlaybackManifestDiagnosticsResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    track_id: string
    token: {
        client_claim: string | null
        provider_user_id: string | null
        country_code: string | null
        access_token_type: string | null
        token_type: string | null
        scopes: string[]
        has_legacy_streaming_scopes: boolean
        has_sdk_playback_scopes: boolean
    }
    tested_country_codes: string[]
    tested_qualities: string[]
    conclusion:
        | 'provider_returned_full_manifest'
        | 'provider_returned_preview_manifest'
        | 'provider_manifest_unresolved'
        | string
    probes: Array<{
        country_code: string
        requested_quality: string
        http_status: number
        asset_presentation: string | null
        audio_quality: string | null
        codec: string | null
        bit_rate: number | null
        sample_rate: number | null
        bit_depth: number | null
        manifest: {
            present: boolean
            mime_type: string | null
            codecs: string | null
            encryption_type: string | null
            asset_presentation: string | null
            duration_seconds: number | null
            url_count: number
        }
        full_playback_available: boolean
        provider_returned_preview: boolean
        error: string | null
    }>
}

export interface TidalPlaybackStreamResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    track_id: string
    country_code: string
    requested_quality: string
    audio_quality: string | null
    codec: string | null
    bit_rate: number | null
    sample_rate: number | null
    bit_depth: number | null
    asset_presentation: string | null
    manifest_mime_type: string | null
    manifest_codecs: string | null
    encryption_type: string | null
    duration_seconds: number | null
    stream_url: string
}

export interface TidalPlaybackTargetResolveRequest {
    user_id: string
    title: string
    artist_name: string
    source_platform?: string | null
    external_track_id?: string | null
    platform_uri?: string | null
    spotify_track_id?: string | null
    isrc?: string | null
    duration_ms?: number | null
}

export interface TidalPlaybackTargetResolveResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    source_platform: string | null
    source_track_id: string | null
    tidal_track_id: string
    tidal_uri: string | null
    title: string
    artist_name: string
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    preview_url: string | null
    isrc: string | null
    duration_ms: number | null
    match_reason: string
    match_score: number
}

export interface SpotifyPlaybackTargetResolveRequest {
    user_id: string
    title: string
    artist_name: string
    source_platform?: string | null
    external_track_id?: string | null
    platform_uri?: string | null
    tidal_track_id?: string | null
    isrc?: string | null
    duration_ms?: number | null
}

export interface SpotifyPlaybackTargetResolveResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    source_platform: string | null
    source_track_id: string | null
    spotify_track_id: string
    spotify_uri: string | null
    title: string
    artist_name: string
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    preview_url: string | null
    isrc: string | null
    duration_ms: number | null
    match_reason: string
    match_score: number
}

export interface YouTubePlaybackTargetResolveRequest {
    user_id: string
    title: string
    artist_name: string
    source_platform?: string | null
    external_track_id?: string | null
    platform_uri?: string | null
    spotify_track_id?: string | null
    tidal_track_id?: string | null
    isrc?: string | null
    duration_ms?: number | null
    excluded_video_ids?: string[] | null
}

export interface YouTubePlaybackTargetResolveResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    source_platform: string | null
    source_track_id: string | null
    youtube_video_id: string
    youtube_url: string
    title: string
    channel_title: string
    thumbnail_url: string | null
    duration_ms: number | null
    match_reason: string
    match_score: number
    candidate_count: number
}

export interface ArtistDetailResponse {
    service: string
    status: string
    generated_at: string
    artist: {
        artist_slug: string
        display_name: string
        image_url: string | null
        match_reason: string
    }
    summary: {
        pms_track_count: number
        ems_track_count: number
        total_track_count: number
        platform_candidate_count: number
    }
    platform_candidates: ArtistDetailPlatformCandidate[]
    pms_tracks: ArtistDetailTrack[]
    ems_tracks: ArtistDetailTrack[]
}

export interface ArtistDetailPlatformCandidate {
    provider: string
    external_artist_id: string | null
    name: string
    image_url: string | null
    external_url: string | null
    uri: string | null
    followers_or_subscribers: number | null
    popularity: number | null
    match_source: string
}

export interface ArtistDetailTrack {
    track_id: string
    title: string
    artist_name: string
    source_platform: string
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    preview_url: string | null
    isrc: string | null
    duration_ms: number | null
    collection_source: string
}

export interface LastFmProfileConnectRequest {
    user_id: string
    username: string
}

export interface PlatformAuthorizationStartRequest {
    user_id: string
    platform_id: WorkspacePlatformId
}

export interface PlatformAuthorizationStartResponse {
    service: string
    status: string
    generated_at: string
    user: {
        user_id: string
        display_name: string
        email: string
    }
    authorization: {
        state: string
        platform_id: WorkspacePlatformId
        platform_display_name: string
        authorization_mode: string
        authorization_channel: 'internal_approval_page' | 'external_browser_redirect'
        requested_scopes: string[]
        expires_at: string
        approval_page_path: string | null
        callback_path: string
        approval_code: string | null
        external_authorization_url: string | null
        redirect_uri: string | null
    }
}

export interface PlatformAuthorizationCompleteRequest {
    user_id: string
    platform_id: WorkspacePlatformId
    state: string
    approval_code?: string
    authorization_code?: string
}

export interface PlatformAuthorizationCompleteResponse {
    service: string
    status: string
    processed_at: string
    authorization: {
        state: string
        platform_id: WorkspacePlatformId
        platform_display_name: string
        authorization_mode: string
        requested_scopes: string[]
        completed_at: string
    }
    connection: {
        user_id: string
        platform_id: WorkspacePlatformId
        connected: boolean
        connection_status: string
        connection_mode: string
        external_account_label: string | null
        scope_summary: string | null
        sync_ready: boolean
        connected_at: string | null
    }
    next_step: {
        path: string
        message: string
    }
}

export interface TidalDeviceAuthorizationStartRequest {
    user_id: string
}

export interface TidalDeviceAuthorizationStartResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    authorization: {
        device_code: string
        user_code: string
        verification_uri: string
        verification_uri_complete: string | null
        expires_at: string
        interval_seconds: number
        requested_scopes: string[]
    }
}

export interface TidalDeviceAuthorizationPollRequest {
    user_id: string
    device_code: string
}

export interface TidalDeviceAuthorizationPollResponse {
    service: string
    status: 'authorization_pending' | 'slow_down' | 'authorization_completed' | string
    processed_at: string
    user_id: string
    requested_scopes: string[]
    connection?: {
        user_id: string
        platform_id: WorkspacePlatformId
        connected: boolean
        connection_status: string
        connection_mode: string
        external_account_label: string | null
        scope_summary: string | null
        sync_ready: boolean
        connected_at: string | null
    } | null
    message?: string | null
}

export interface LastFmSignalPreviewResponse {
    service: string
    status: string
    generated_at: string
    request: {
        username: string
        period: 'overall' | '7day' | '1month' | '3month' | '6month' | '12month'
        recent_limit: number
        top_limit: number
    }
    user: {
        username: string
        real_name: string | null
        country: string | null
        playcount: number | null
        profile_url: string | null
        avatar_url: string | null
        registered_at: string | null
    }
    summary: {
        source: string
        recent_track_count: number
        top_artist_count: number
        top_track_count: number
        now_playing: boolean
        distinct_recent_artist_count: number
        next_step_message: string
    }
    insights: Array<{
        insight_id: string
        title: string
        detail: string
    }>
    recent_tracks: Array<{
        track_name: string | null
        artist_name: string | null
        album_name: string | null
        track_url: string | null
        image_url: string | null
        now_playing: boolean
        played_at: string | null
        loved: boolean
    }>
    top_artists: Array<{
        artist_name: string | null
        rank: number | null
        playcount: number | null
        artist_url: string | null
        image_url: string | null
    }>
    top_tracks: Array<{
        track_name: string | null
        artist_name: string | null
        rank: number | null
        playcount: number | null
        track_url: string | null
        artist_url: string | null
        image_url: string | null
    }>
}

export interface LastFmScrobbleBootstrapResponse {
    service: string
    status: string
    generated_at: string
    user: {
        user_id: string
        last_fm_username: string | null
        last_fm_connected_at: string | null
    }
    summary: {
        stored_scrobble_count: number
        last_synced_at: string | null
        returned_scrobble_count: number
        next_step_message: string
    }
    recent_scrobbles: Array<{
        track_name: string
        artist_name: string
        album_name: string | null
        track_url: string | null
        image_url: string | null
        played_at: string
        loved: boolean
        synced_at: string
    }>
}

export interface LastFmScrobbleSyncRequest {
    user_id: string
    limit?: number
}

export interface LastFmScrobbleSyncResponse {
    service: string
    status: string
    processed_at: string
    sync: {
        user_id: string
        last_fm_username: string
        fetched_track_count: number
        inserted_scrobble_count: number
        duplicate_scrobble_count: number
        skipped_now_playing_count: number
        stored_scrobble_count: number
        last_synced_at: string | null
    }
    recent_scrobbles: Array<{
        track_name: string
        artist_name: string
        album_name: string | null
        track_url: string | null
        image_url: string | null
        played_at: string
        loved: boolean
        synced_at: string
    }>
    notes: string[]
}

export interface PmsWorkspaceBootstrapResponse {
    service: string
    status: string
    generated_at: string
    workspace_defaults: {
        user_id: string
        playlist_id: string
        seed_track_ids: string[]
        seed_artist_names: string[]
        seed_genres: string[]
    }
    playlists: Array<RichPlaylistArtwork & {
        playlist_id: string
        title: string
        source_platform: string
        track_count: number
        curator: string
        highlight: string
        source_collection: string
    }>
    suggested_tracks: Array<RichTrackArtwork & {
        track_id: string
        title: string
        artist_name: string
        source_platform: string
        seed: boolean
        isrc?: string | null
        spotify_track_id: string | null
        spotify_uri?: string | null
        tidal_track_id?: string | null
        tidal_uri?: string | null
        preferred_playback_platform?: string | null
        playback_target_status?: string | null
        audio_feature_track_id?: string | null
        spotify_audio_features_filled: boolean
        audio_features_filled?: boolean
        spotify_audio_feature_source: string
        audio_feature_source?: string
    }>
    suggested_artists: Array<{
        artist_name: string
        affinity_score: number
        reason: string
    }>
    suggested_genres: Array<{
        genre: string
        weight: number
        reason: string
    }>
}

export interface PmsPlaylistDetailTrack extends RichTrackArtwork {
    track_id: string
    external_track_id: string | null
    title: string
    artist_name: string
    source_platform: string
    primary_genre: string | null
    sort_order: number
    seed: boolean
    isrc?: string | null
    spotify_track_id: string | null
    spotify_uri?: string | null
    tidal_track_id?: string | null
    tidal_uri?: string | null
    preferred_playback_platform?: string | null
    playback_target_status?: string | null
    audio_feature_track_id?: string | null
    spotify_audio_features_filled: boolean
    audio_features_filled?: boolean
    spotify_audio_feature_source: string
    audio_feature_source?: string
}

export interface PmsPlaylistDetailResponse {
    service: string
    status: string
    generated_at: string
    source_collection: string
    playlist: RichPlaylistArtwork & {
        playlist_id: string
        external_playlist_id: string | null
        title: string
        source_platform: string
        track_count: number
        curator: string
        description: string
        imported_at: string | null
        last_synced_at: string | null
    }
    tracks: PmsPlaylistDetailTrack[]
}

export interface PmsPlaylistImportBootstrapResponse {
    service: string
    status: string
    generated_at: string
    user: {
        user_id: string
        display_name: string
        preferred_platform_id: WorkspacePlatformId
    }
    platform_connection: {
        platform_id: WorkspacePlatformId
        display_name: string
        pms_import_supported: boolean
        connected: boolean
        connection_mode: string | null
        external_account_label: string | null
        sync_ready: boolean
        credential_status: 'ready' | 'missing' | 'reconnect_required'
        reconnect_required: boolean
    }
    summary: {
        preferred_platform_connected: boolean
        reconnect_required: boolean
        available_playlist_count: number
        imported_playlist_count: number
        next_step_path: string
        next_step_message: string
    }
    available_playlists: Array<RichPlaylistArtwork & {
        external_playlist_id: string
        title: string
        source_platform: string
        track_count: number
        curator: string
        description: string
        already_imported: boolean
        audio_feature_policy: string
    }>
    imported_playlists: Array<RichPlaylistArtwork & {
        playlist_id: string
        external_playlist_id: string
        title: string
        source_platform: string
        track_count: number
        imported_at: string
    }>
}

export interface PmsPlaylistImportRequest {
    user_id: string
    platform_id: WorkspacePlatformId
    external_playlist_ids: string[]
}

export interface PmsPlaylistImportResponse {
    service: string
    status: string
    processed_at: string
    import_result: {
        user_id: string
        platform_id: WorkspacePlatformId
        platform_display_name: string
        imported_playlist_count: number
        imported_track_count: number
        complete_audio_feature_track_count?: number
        complete_spotify_audio_feature_track_count: number
        connection_mode: string
        library_synced_playlist_count: number
        library_synced_track_count: number
    }
    playlists: Array<{
        playlist_id: string
        external_playlist_id: string
        title: string
        source_platform: string
        track_count: number
        imported_at: string
    }>
    next_step: {
        path: string
        message: string
    }
}

export interface PmsPersonalPlaylistTrack {
    track_id: string
    external_track_id: string | null
    title: string
    artist_name: string
    source_platform: string
    isrc: string | null
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    preview_url: string | null
    spotify_track_id: string | null
    spotify_uri?: string | null
    tidal_track_id?: string | null
    tidal_uri?: string | null
    preferred_playback_platform?: string | null
    playback_target_status?: string | null
    audio_feature_track_id?: string | null
    duration_ms: number | null
    sort_order: number
    source_context: string
    added_at: string
}

export interface PmsPersonalPlaylist {
    playlist_id: string
    title: string
    description: string
    track_count: number
    created_at: string
    updated_at: string
    tracks: PmsPersonalPlaylistTrack[]
}

export interface PmsPersonalPlaylistBootstrapResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    summary: {
        playlist_count: number
        saved_track_count: number
    }
    playlists: PmsPersonalPlaylist[]
}

export interface PmsPersonalPlaylistCreateRequest {
    user_id: string
    title: string
    description?: string
}

export interface PmsPersonalPlaylistTrackSaveRequest {
    user_id: string
    target_playlist_id?: string
    target_playlist_title?: string
    track_id: string
    source_context?: string
}

export interface PmsPersonalPlaylistCommandResponse {
    service: string
    status: string
    processed_at: string
    playlist: PmsPersonalPlaylist
    next_step_message: string
}

export interface EmsWorkspaceAnalysisRequest {
    user_id?: string
    playlist_id?: string
    seed_track_ids?: string[]
    seed_artist_names?: string[]
    seed_genres?: string[]
}

export interface EmsWorkspaceAnalysisResponse {
    service: string
    status: string
    generated_at: string
    context: {
        strategy: string
        playlist_id: string | null
        track_seed_count: number
        artist_seed_count: number
        genre_seed_count: number
        matched_catalog_track_count: number
    }
    workspace_recommendation: {
        mood: 'focus' | 'calm' | 'upbeat' | 'melancholy' | 'discovery'
        energy_level: number
        familiarity_bias: number
        confidence_score: number
    }
    top_signals: Array<{
        type: 'genre' | 'artist'
        label: string
        weight: number
        reason: string
    }>
    notes: string[]
    warnings: string[]
}

export interface GmsRecommendationPreviewRequest {
    request_id?: string
    user_id?: string
    playlist_id?: string
    mode?: 'gms'
    mood?: 'focus' | 'calm' | 'upbeat' | 'melancholy' | 'discovery'
    energy_level?: number
    familiarity_bias?: number
    limit?: number
    seed_track_ids?: string[]
    seed_artist_names?: string[]
    seed_genres?: string[]
    include_explanations?: boolean
}

export interface GmsTasteModeAffinity {
    applied: boolean
    mode_id: string
    label: string
    similarity: number
    distance: number
    tokens?: string[] | null
}

export interface GmsTasteModeGate {
    status: 'not_applicable' | 'blocked' | 'eligible' | 'dry_run' | string
    reason: string
    reason_tokens?: string[] | null
    suggested_boost_weight?: number | null
    dry_run_score?: number | null
    dry_run_delta?: number | null
}

export interface GmsRecommendationPreviewResponse {
    request_id: string
    generated_at: string
    service: string
    status: string
    context: {
        strategy: string
        engine: string
        mode: string
        mood: string | null
        energy_level: number
        seed_basis: string[]
    }
    input_summary: {
        user_id: string | null
        playlist_id: string | null
        track_seed_count: number
        artist_seed_count: number
        genre_seed_count: number
        familiarity_bias: number
        limit: number
    }
    items: Array<RichTrackArtwork & {
        rank: number
        track_id: string
        title: string
        artist_name: string
        source_platform: string
        source_playlist_id: string | null
        source_playlist_title: string | null
        spotify_track_id: string | null
        audio_feature_track_id?: string | null
        score: number
        source_space: string
        energy_level: number
        reason?: string | null
        taste_mode_affinity?: GmsTasteModeAffinity | null
        taste_mode_gate?: GmsTasteModeGate | null
        axis_evidence?: GmsAxisEvidence[]
    }>
    warnings: string[]
}

export interface GmsAxisEvidence {
    axis: string
    score: number | null
    level: string
    summary: string
}

export interface GmsPlaylistPreviewResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    preferred_platform: string | null
    model_stage: string
    candidates: GmsPlaylistPreviewItem[]
}

export interface GmsPlaylistPreviewItem {
    playlist_id: number
    external_playlist_id: string
    source_platform: string
    title: string
    curator: string | null
    description: string | null
    cover_image_url: string | null
    platform_external_url: string | null
    track_count: number
    audio_feature_filled_count: number
    affinity_score: number
    confidence_score: number
    composite_score: number
    collected_at: string
    axis_evidence: GmsAxisEvidence[]
}

export interface GmsPlaylistSaveRequest {
    title?: string | null
    excluded_track_ids?: number[]
}

export interface GmsPlaylistSaveResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    ems_playlist_id: number
    personal_playlist_id: string
    personal_playlist_title: string
    personal_playlist_track_count: number
    added_track_count: number
    saved_at: string
}

export interface GmsPlaylistDismissResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    ems_playlist_id: number
    dismissed_at: string
}

export interface GmsTidalPlaylistUrlImportRequest {
    user_id: string
    playlist_url: string
}

export interface GmsTidalPlaylistUrlImportResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    ems_playlist_id: number
    external_playlist_id: string
    source_platform: string
    title: string
    track_count: number
    collection_source: string
    collected_at: string
}

export interface EmsCollectionSearchRequest {
    user_id: string
    platform_id?: string
    query: string
}

export interface EmsCollectionSearchResponse {
    service: string
    status: string
    generated_at: string
    platform_id: string
    query: string
    pool_run_id: number | null
    result_playlist_count: number
    result_track_count: number
    playlists: EmsCollectionSearchPlaylistItem[]
    tracks: EmsCollectionSearchTrackItem[]
    searched_at: string
}

export interface EmsCollectionSearchPlaylistItem {
    external_playlist_id: string
    title: string
    source_platform: string
    curator: string
    description: string
    cover_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    spotify_uri: string | null
    track_count: number
}

export interface EmsCollectionSearchTrackItem {
    external_track_id: string
    title: string
    artist_name: string
    source_platform: string
    isrc: string | null
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    spotify_uri: string | null
    preview_url: string | null
    duration_ms: number | null
}

export interface EmsCollectionSearchPlaylistTracksResponse {
    service: string
    status: string
    generated_at: string
    platform_id: string
    external_playlist_id: string
    track_count: number
    tracks: EmsCollectionSearchTrackItem[]
    searched_at: string
}

export interface EmsPoolAdminRunItem {
    run_id: number
    requested_by_user_id: string
    source_platform: string
    search_query: string
    collection_source: string
    status: string
    total_playlist_entries: number
    total_track_entries: number
    processed_playlist_entries: number
    processed_track_entries: number
    failed_entries: number
    collected_playlist_count: number
    collected_track_count: number
    progress_ratio: number
    last_error: string | null
    created_at: string
    started_at: string | null
    completed_at: string | null
    updated_at: string
}

export interface EmsPoolAdminEntryItem {
    entry_id: number
    entry_type: string
    source_platform: string
    external_id: string
    title: string
    artist_name: string | null
    status: string
    attempts: number
    last_error: string | null
    created_at: string
    updated_at: string
    processed_at: string | null
}

export interface EmsPoolAdminRunsResponse {
    service: string
    status: string
    generated_at: string
    runs: EmsPoolAdminRunItem[]
}

export interface EmsPoolAdminRunDetailResponse {
    service: string
    status: string
    generated_at: string
    run: EmsPoolAdminRunItem
    entries: EmsPoolAdminEntryItem[]
}

export interface EmsPoolAdminRunCommandResponse {
    service: string
    status: string
    generated_at: string
    run: EmsPoolAdminRunItem
}

export interface EmsPoolAdminEntryRetryResponse {
    service: string
    status: string
    generated_at: string
    entry: EmsPoolAdminEntryItem
}

export interface EmsPoolAdminRunDeleteResponse {
    service: string
    status: string
    generated_at: string
    run_id: number
}

export interface EmsCollectedPlaylistsCleanupResponse {
    service: string
    status: string
    generated_at: string
    deleted_count: number
}

export interface EmsAcquisitionSourceRequest {
    name: string
    type: string
    url: string
    weight?: number
}

export interface EmsAcquisitionRunRequest {
    user_id: string
    platforms?: string[]
    source_preset?: string
    sources?: EmsAcquisitionSourceRequest[]
    max_articles_per_source?: number
    max_signals_per_run?: number
    per_seed_limit?: number
}

export interface EmsAcquisitionRunItem {
    id: number | null
    trigger_type: string
    requested_by_user_id: string
    status: string
    source_count: number
    article_count: number
    skipped_article_count: number
    signal_count: number
    seed_count: number
    skipped_seed_count: number
    pool_run_count: number
    failed_source_count: number
    failed_seed_count: number
    message: string | null
    last_error: string | null
    started_at: string
    completed_at: string | null
    updated_at: string
}

export interface EmsAcquisitionSignalItem {
    id: number
    source_name: string
    source_url: string
    article_url: string | null
    article_title: string | null
    signal_type: string
    query: string
    confidence_score: number
    rationale: string | null
    status: string
    created_at: string
}

export interface EmsAcquisitionSeedItem {
    id: number
    signal_id: number | null
    platform_id: string
    query: string
    status: string
    pool_run_id: number | null
    result_playlist_count: number
    result_track_count: number
    last_error: string | null
    created_at: string
    updated_at: string
}

export interface EmsAcquisitionRunResponse {
    service: string
    status: string
    generated_at: string
    run: EmsAcquisitionRunItem | null
    signals: EmsAcquisitionSignalItem[]
    seeds: EmsAcquisitionSeedItem[]
}

export interface EmsAcquisitionRunsResponse {
    service: string
    status: string
    generated_at: string
    runs: EmsAcquisitionRunItem[]
}

export interface EmsAcquisitionSourcePresetItem {
    id: string
    name: string
    description: string | null
    source_count: number
    max_articles_per_source: number
    max_signals_per_run: number
    per_seed_limit: number
    sources: EmsAcquisitionSourceRequest[]
}

export interface EmsAcquisitionSourcePresetsResponse {
    service: string
    status: string
    generated_at: string
    presets: EmsAcquisitionSourcePresetItem[]
}

export interface EmsAcquisitionSourceQualityItem {
    source_name: string
    signal_count: number
    avg_confidence: number
    last_signal_at: string | null
}

export interface EmsAcquisitionSourceQualityResponse {
    service: string
    status: string
    generated_at: string
    lookback_days: number
    sources: EmsAcquisitionSourceQualityItem[]
}

export interface PlaylistQualityRecentItem {
    recommendation_id: string | null
    user_id: string | null
    created_at: string | null
    model_version: string | null
    track_count: number
    avg_affinity: number | null
    avg_novelty: number | null
    coherence: number | null
    diversity: number | null
    redundancy_penalty: number | null
    avg_confidence: number | null
}

export interface PlaylistQualityRecentResponse {
    service: string
    status: string
    generated_at: string
    playlists: PlaylistQualityRecentItem[]
}

export interface FeatureCoverageSummary {
    track_count: number
    audio_feature_filled_count: number
    audio_feature_coverage_ratio: number
    stale_audio_feature_count: number
    stale_audio_feature_ratio: number
    latest_audio_resolved_at: string | null
    isrc_count: number
    isrc_coverage_ratio: number
}

export interface FeatureCoverageAudioFeatureSourceClass {
    source_class: string
    track_count: number
    audio_feature_filled_count: number
    audio_feature_coverage_ratio: number
    stale_audio_feature_count: number
    stale_audio_feature_ratio: number
    latest_audio_resolved_at: string | null
}

export interface FeatureCoveragePmsLibrary extends FeatureCoverageSummary {
    playlist_count: number
    audio_feature_source_classes: FeatureCoverageAudioFeatureSourceClass[]
    playback_target_available_count: number
    playback_target_coverage_ratio: number
}

export interface FeatureCoverageEmsSource extends FeatureCoverageSummary {
    source_platform: string
    canonical_track_count: number
    canonical_track_coverage_ratio: number
}

export interface FeatureCoverageEmsPool extends FeatureCoverageSummary {
    audio_feature_source_classes: FeatureCoverageAudioFeatureSourceClass[]
    canonical_track_count: number
    canonical_track_coverage_ratio: number
    sources: FeatureCoverageEmsSource[]
    warnings: string[]
}

export interface FeatureCoverageLearningData {
    event_count: number
    recent_recommendation_snapshot_count: number
    recent_recommendation_snapshot_limit: number
}

export interface FeatureCoverageEmsAcquisition {
    recent_run_count: number
    article_count: number
    skipped_article_count: number
    seed_count: number
    skipped_seed_count: number
    checked_item_count: number
    skipped_item_count: number
    skipped_item_ratio: number
    warnings: string[]
}

export interface FeatureCoverageAudioFeatureCompletionStatusCount {
    status: string
    job_count: number
}

export interface FeatureCoverageAudioFeatureCompletionReasonCount {
    reason: string
    job_count: number
}

export interface FeatureCoverageAudioFeatureCompletion {
    recent_job_count: number
    status_counts: FeatureCoverageAudioFeatureCompletionStatusCount[]
    top_reasons: FeatureCoverageAudioFeatureCompletionReasonCount[]
    warnings: string[]
}

export interface FeatureCoverageDriftSignal {
    category: string
    severity: 'warn' | 'info' | string
    target_scope: string
    message: string
    actual_value: number | null
    threshold: number | null
    sample_size: number
}

export interface FeatureCoverageAdminResponse {
    service: string
    status: string
    generated_at: string
    target_user_id: string
    pms_library: FeatureCoveragePmsLibrary
    ems_pool: FeatureCoverageEmsPool
    ems_acquisition: FeatureCoverageEmsAcquisition
    audio_feature_completion: FeatureCoverageAudioFeatureCompletion
    learning_data: FeatureCoverageLearningData
    warnings: string[]
    drift_signals: FeatureCoverageDriftSignal[]
}

export interface RecommendationAuditLogItem {
    audit_log_id: number
    user_id: string
    recommendation_id: string | null
    request_id: string | null
    event_type: string
    source_space: string | null
    model_version: string | null
    dataset_version: string | null
    dataset_fingerprint: string | null
    item_count: number | null
    sasrec_applied: boolean | null
    fallback_reason: string | null
    feedback_type: string | null
    target_track_id: string | null
    target_playlist_id: string | null
    taste_mode_gate_summary: string | null
    axis_evidence_summary: string | null
    created_at: string
}

export interface RecommendationAuditLogRecentResponse {
    service: string
    status: string
    generated_at: string
    entries: RecommendationAuditLogItem[]
}

export interface RecommendationTasteModeLatestSummary {
    audit_log_id: number | null
    model_version: string | null
    created_at: string | null
    taste_mode_gate_summary: string
}

export interface RecommendationTasteModeSummary {
    entries_analyzed: number
    entries_with_summary: number
    parse_error_count: number
    boost_enabled_count: number
    evaluated_total: number
    eligible_total: number
    dry_run_total: number
    blocked_total: number
    not_applicable_total: number
    boost_applied_total: number
    rank_changed_total: number
    max_positive_delta: number | null
    max_negative_delta: number | null
    reason_counts: Record<string, number>
    latest_summary: RecommendationTasteModeLatestSummary | null
    recommendation: string
}

export interface RecommendationTasteModeSummaryResponse {
    service: string
    status: string
    generated_at: string
    summary: RecommendationTasteModeSummary
}

export interface SasrecRegistryAdminResponse {
    service: string
    status: string
    generated_at: string
    user_id: string | null
    model_version: string | null
    artifact_dir: string | null
    generated_at_ai: string | null
    vocabulary_size: number | null
    train_example_count: number | null
    dataset_version: string | null
    dataset_fingerprint: string | null
    warnings: string[]
}

export interface MetadataLookupCandidate {
    mbid: string | null
    title: string | null
    artist_name: string | null
    length_ms: number | null
    score: number | null
    isrcs: string[]
    release_titles: string[]
}

export interface TrackIdentityCandidateItem {
    id: number
    query_title: string
    query_artist: string | null
    source: string
    candidate_kind: string
    candidate_value: string
    candidate_score: number | null
    status: 'pending' | 'accepted' | 'rejected' | string
    created_by: string | null
    created_at: string
    resolved_by: string | null
    resolved_at: string | null
    notes: string | null
}

export interface MetadataLookupResponse {
    service: string
    status: string
    generated_at: string
    title: string
    artist: string | null
    total_count: number
    candidates: MetadataLookupCandidate[]
    saved_candidates: TrackIdentityCandidateItem[]
}

export interface MetadataExternalLookupCandidate {
    source: string
    candidate_kind: string
    candidate_value: string
    label: string | null
    description: string | null
    candidate_score: number | null
}

export interface MetadataExternalLookupResponse {
    service: string
    status: string
    generated_at: string
    source: string
    title: string
    artist: string | null
    total_count: number
    candidates: MetadataExternalLookupCandidate[]
    saved_candidates: TrackIdentityCandidateItem[]
}

export interface MetadataCandidateListResponse {
    service: string
    status: string
    generated_at: string
    candidates: TrackIdentityCandidateItem[]
}

export interface MetadataCandidateCommandResponse {
    service: string
    status: string
    generated_at: string
    candidate: TrackIdentityCandidateItem
}

export interface MetadataCandidateAutoAcceptResponse {
    service: string
    status: string
    generated_at: string
    threshold: number
    reviewed_count: number
    accepted_count: number
    skipped_count: number
    accepted: TrackIdentityCandidateItem[]
}

export interface MetadataCandidateAppliedItem {
    candidate: TrackIdentityCandidateItem
    updated_track_ids: number[]
    conflict_track_ids: number[]
}

export interface MetadataCandidateApplyResponse {
    service: string
    status: string
    generated_at: string
    reviewed_count: number
    isrc_considered_count: number
    applied_count: number
    no_match_count: number
    conflict_count: number
    applied: MetadataCandidateAppliedItem[]
    no_match: TrackIdentityCandidateItem[]
    conflicts: TrackIdentityCandidateItem[]
}

export interface MetadataCandidateRollbackResponse {
    service: string
    status: string
    generated_at: string
    candidate: TrackIdentityCandidateItem
    target_track_ids: number[]
    cleared_track_ids: number[]
    skipped_track_ids: number[]
}

export interface CanonicalTrackItem {
    canonical_track_id: number
    display_title: string
    display_artist_name: string | null
    release_year: string | null
    release_country: string | null
    release_label: string | null
    created_at: string
    updated_at: string
}

export interface CanonicalTrackIdentityItem {
    canonical_track_identity_id: number
    canonical_track_id: number
    identity_kind: string
    identity_value: string
    source: string
    confidence_score: number | null
    status: string
    created_from_candidate_id: number | null
    created_at: string
    updated_at: string
}

export interface CanonicalRowLinkItem {
    ems_linked_count: number
    ems_conflict_count: number
    pms_imported_linked_count: number
    pms_imported_conflict_count: number
    pms_user_linked_count: number
    pms_user_conflict_count: number
    total_conflict_count: number
}

export interface MetadataCandidateCanonicalPromotionResponse {
    service: string
    status: string
    generated_at: string
    candidate: TrackIdentityCandidateItem
    canonical_track: CanonicalTrackItem
    identity: CanonicalTrackIdentityItem
    created_canonical_track: boolean
    created_identity: boolean
    links: CanonicalRowLinkItem
}

export interface CanonicalLinkConflictRowItem {
    track_store: string
    row_id: string
    existing_canonical_track_id: number
    title: string
    artist_name: string
    source_platform: string
    external_track_id: string
    isrc: string | null
}

export interface MetadataCandidateCanonicalLinkConflictResponse {
    service: string
    status: string
    generated_at: string
    candidate: TrackIdentityCandidateItem
    target_identity: CanonicalTrackIdentityItem
    rows: CanonicalLinkConflictRowItem[]
}

export interface MetadataCandidateAuditItem {
    id: number
    candidate_id: number
    action: string
    ems_collected_track_id: number | null
    candidate_value: string | null
    previous_isrc: string | null
    new_isrc: string | null
    status: string
    message: string | null
    acted_by: string | null
    acted_at: string
}

export interface MetadataCandidateAuditResponse {
    service: string
    status: string
    generated_at: string
    candidate: TrackIdentityCandidateItem
    entries: MetadataCandidateAuditItem[]
}

export interface SasrecUserTrainLogItem {
    id: number | null
    trained_at: string | null
    event_count_at_train: number
    dataset_version: string | null
    dataset_fingerprint: string | null
    sequence_item_count_at_train: number
    recommendation_snapshot_count_at_train: number
    model_version: string | null
    qualified: boolean
    promoted: boolean
    summary: string | null
    hit_rate_at_k: number | null
    mrr_at_k: number | null
    ndcg_at_k: number | null
    baseline_hit_rate_at_k: number | null
    baseline_mrr_at_k: number | null
    baseline_ndcg_at_k: number | null
    hit_rate_delta: number | null
    mrr_delta: number | null
    ndcg_delta: number | null
}

export interface SasrecUserModelStatusResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    model_stage: 'cold-start' | 'baseline' | 'personalized' | string
    pms_track_count: number
    active_model_version: string | null
    active_model_generated_at: string | null
    latest_train_log: SasrecUserTrainLogItem | null
    total_event_count: number
    events_since_last_train: number | null
}

export interface PersonalizationProfileArtistAffinity {
    artist_name: string
    score: number
    signal_count: number
}

export interface PersonalizationProfilePlatformAffinity {
    platform: string
    score: number
    signal_count: number
}

export interface PersonalizationProfileItem {
    profile_id: number
    user_id: string
    top_artists: PersonalizationProfileArtistAffinity[]
    top_source_platforms: PersonalizationProfilePlatformAffinity[]
    event_count_at_update: number
    last_event_at: string | null
    recomputed_at: string
}

export interface PersonalizationProfileResponse {
    service: string
    status: string
    generated_at: string
    profile: PersonalizationProfileItem
}

export interface PersonalizationProfileRecomputeResponse {
    service: string
    status: string
    generated_at: string
    events_scanned: number
    signal_count: number
    event_limit: number
    profile: PersonalizationProfileItem
}

export interface SasrecAutoTrainAdminResponse {
    service: string
    status: string
    generated_at: string
    user_id: string
    qualified: boolean
    promoted: boolean
    model_version: string | null
    summary: string
    training: {
        model_version: string | null
        dataset_summary?: {
            event_count?: number
            recommendation_snapshot_count?: number
            sequence_item_count?: number
            dataset_version?: string
            dataset_fingerprint?: string
        }
        metrics?: { hit_rate_at_k?: number; mrr_at_k?: number; ndcg_at_k?: number }
        baseline_metrics?: { hit_rate_at_k?: number; mrr_at_k?: number; ndcg_at_k?: number }
        metric_delta?: { hit_rate_at_k?: number; mrr_at_k?: number; ndcg_at_k?: number }
        qualification?: { qualified?: boolean; threshold?: number; reason?: string }
        warnings?: string[]
    }
    promote_result: SasrecRegistryAdminResponse | null
}

export interface EmsCollectionPlaylistItem {
    id: number
    external_playlist_id: string
    title: string
    source_platform: string
    curator: string
    description: string
    cover_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    spotify_uri: string | null
    track_count: number
    collection_source: string
    search_query: string | null
    collected_at: string
    audio_feature_coverage: {
        track_count: number
        filled_track_count: number
        pending_track_count: number
        coverage_ratio: number
    }
}

export interface EmsCollectionPlaylistBrowseResponse {
    service: string
    status: string
    generated_at: string
    platform_id: string
    playlists: EmsCollectionPlaylistItem[]
}

export interface EmsCollectionPlaylistSectionItem {
    playlist: EmsCollectionPlaylistItem
    match_signals: string[]
}

export interface EmsCollectionPlaylistSection {
    section_id: string
    title: string
    subtitle: string
    category_type: string
    category_label: string
    display_style: 'hero' | 'mosaic' | 'rail' | 'compact' | string
    title_source: string
    playlists: EmsCollectionPlaylistSectionItem[]
}

export interface EmsCollectionPlaylistSectionsResponse {
    service: string
    status: string
    generated_at: string
    user_id: string | null
    platform_ids: string[]
    title_model: string
    personalized: boolean
    sections: EmsCollectionPlaylistSection[]
}

export interface EmsFloSpecialSection {
    source_type: string
    title: string
    playlists: EmsCollectionPlaylistItem[]
}

export interface EmsFloSpecialFailureItem {
    section_title: string | null
    external_playlist_id: string | null
    message: string | null
}

export interface EmsFloSpecialRunItem {
    trigger: string | null
    status: string
    started_at: string | null
    completed_at: string | null
    section_count: number
    collected_playlist_count: number
    collected_track_count: number
    failures: EmsFloSpecialFailureItem[]
    message: string
}

export interface EmsFloSpecialResponse {
    service: string
    status: string
    generated_at: string
    source_platform: string
    collection_source: string
    sections: EmsFloSpecialSection[]
    last_run: EmsFloSpecialRunItem | null
}

export interface EmsFloSpecialRefreshResponse extends EmsFloSpecialRunItem {
    service: string
    status: string
    generated_at: string
}

export interface EmsCollectionPlaylistDetailResponse {
    service: string
    status: string
    generated_at: string
    playlist: EmsCollectionPlaylistItem
    tracks: EmsCollectionTrackItem[]
}

export interface EmsCollectionTrackItem {
    id: number
    external_track_id: string
    title: string
    artist_name: string
    source_platform: string
    isrc: string | null
    album_title: string | null
    album_image_url: string | null
    platform_external_url: string | null
    platform_uri: string | null
    spotify_uri: string | null
    preview_url: string | null
    duration_ms: number | null
    collected_at: string
    audio_features: {
        audio_feature_track_id: string | null
        audio_feature_source: string
        audio_features_filled: boolean
        duration_ms: number | null
        musical_key: number | null
        mode: number | null
        acousticness: number | null
        danceability: number | null
        energy: number | null
        instrumentalness: number | null
        liveness: number | null
        loudness: number | null
        speechiness: number | null
        tempo: number | null
        valence: number | null
    }
}

export interface EmsCollectionTrackBrowseResponse {
    service: string
    status: string
    generated_at: string
    playlist_id: number | null
    tracks: EmsCollectionTrackItem[]
}

export interface EmsOverviewRequest {
    user_id?: string
    playlist_id?: string
}

export interface EmsOverviewResponse {
    service: string
    status: string
    generated_at: string
    user_id: string | null
    playlist_id: string | null
    pipeline_status: {
        pms_library: string
        ems_pool: string
        gms_readiness: string
    }
    taste_model_snapshot: {
        status: string
        model: string | null
        summary: string | null
        confidence: number | null
    }
    candidate_direction: {
        status: string
        summary: string | null
        mood: string | null
        energy_level: number | null
        familiarity_bias: number | null
        confidence: number | null
    }
    pms_context: {
        playlist_title: string | null
        playlist_count: number
        library_track_count: number
        seed_track_count: number
        artist_seed_count: number
        genre_seed_count: number
    }
    ems_pool: {
        playlist_count: number
        track_count: number
        audio_feature_filled_track_count: number
        audio_feature_coverage_ratio: number
        providers: Array<{
            platform_id: string
            playlist_count: number
            track_count: number
            audio_feature_filled_track_count: number
            audio_feature_coverage_ratio: number
            last_collected_at: string | null
        }>
    }
    system_attention: string[]
    evidence: string[]
    warnings: string[]
}

export type UserMusicEventType =
    | 'play_started'
    | 'play_paused'
    | 'play_resumed'
    | 'play_completed'
    | 'skip_next'
    | 'skip_previous'
    | 'replay'
    | 'track_saved'
    | 'added_to_playlist'
    | 'recommendation_liked'
    | 'recommendation_rejected'
    | 'ignored_recommendation'
    | 'stopped_midway'

export interface UserMusicEventRequest {
    user_id: string
    event_type: UserMusicEventType
    source_space?: string | null
    source_platform?: string | null
    playback_platform_id?: string | null
    item_id?: string | null
    item_kind?: 'track' | 'playlist' | string | null
    track_id?: string | null
    playlist_id?: string | null
    external_track_id?: string | null
    platform_uri?: string | null
    title?: string | null
    artist_name?: string | null
    album_title?: string | null
    isrc?: string | null
    duration_ms?: number | null
    position_ms?: number | null
    play_ratio?: number | null
    recommendation_id?: string | null
    metadata_confidence?: number | null
    occurred_at?: string
}

export interface UserMusicEventResponse {
    service: string
    status: string
    processed_at: string
    event: UserMusicEventRequest & {
        event_id: number
        event_weight: number | null
        received_at: string
    }
    next_step_message: string
}

export type GmsRecommendationFeedbackType = 'like' | 'dislike' | 'save' | 'skip'

export interface GmsRecommendationFeedbackRequest {
    user_id: string
    request_id?: string
    playlist_id?: string | null
    track_id: string
    feedback_type: GmsRecommendationFeedbackType
    score?: number
    source_space?: string
    reason?: string | null
}

export interface HeroTrackResponse {
    external_track_id: string
    source_platform: string
    spotify_track_id: string | null
    title: string
    artist_name: string
    album_title: string | null
    image_url: string | null
    preview_url: string | null
    platform_external_url: string | null
    duration_ms: number | null
    source_label: string | null
}

export interface HeroTrackListResponse {
    tracks: HeroTrackResponse[]
}

export interface PopularPlaylistResponse {
    playlist_id: number
    external_playlist_id: string
    source_platform: string
    title: string
    curator: string | null
    description: string | null
    cover_image_url: string | null
    platform_external_url: string | null
    track_count: number
}

export interface PopularPlaylistListResponse {
    playlists: PopularPlaylistResponse[]
}

export interface MagazineArticleResponse {
    source_name: string
    source_url: string | null
    article_url: string
    article_title: string
    article_title_ko: string | null
    description: string | null
    description_ko: string | null
    rationale: string | null
    rationale_ko: string | null
    image_url: string | null
    captured_at: string | null
}

export interface MagazineArticleListResponse {
    articles: MagazineArticleResponse[]
}

export interface MelonChartTrack {
    rank: number
    melon_song_id: string | null
    title: string
    artist_name: string
    album_title: string | null
    image_url: string | null
    song_external_url: string | null
    snapshot_at: string
}

export interface MelonChartListResponse {
    snapshot_at: string
    tracks: MelonChartTrack[]
}

export interface MelonResolveResponse {
    rank: number
    melon_song_id: string | null
    melon_title: string
    melon_artist_name: string
    source_platform: 'tidal' | 'spotify' | null
    spotify_track_id: string | null
    tidal_track_id: string | null
    tidal_uri: string | null
    resolved_title: string | null
    resolved_artist_name: string | null
    resolved_album_title: string | null
    image_url: string | null
    external_url: string | null
    resolved: boolean
}

export interface UserTrackLikeRequest {
    user_id: string
    source_platform: string
    external_track_id: string
    title?: string | null
    artist_name?: string | null
    album_title?: string | null
    image_url?: string | null
    spotify_track_id?: string | null
    platform_external_url?: string | null
}

export interface UserTrackLikeResponse {
    liked: boolean
    source_platform: string
    external_track_id: string
    liked_at: string | null
}

export interface GmsRecommendationFeedbackResponse {
    service: string
    status: string
    processed_at: string
    feedback: {
        feedback_id: number
        user_id: string
        request_id: string | null
        playlist_id: string | null
        track_id: string
        feedback_type: GmsRecommendationFeedbackType
        score: number | null
        source_space: string | null
        reason: string | null
        created_at: string
    }
    next_step_message: string
}
