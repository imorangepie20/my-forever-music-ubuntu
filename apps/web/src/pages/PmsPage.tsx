import { startTransition, useEffect, useMemo, useState } from 'react'
import { LibraryBig, Plus, RefreshCw, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import PlaylistFeatureCard from '@/components/music/PlaylistFeatureCard'
import TrackFeatureCard from '@/components/music/TrackFeatureCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { buildArtistDetailPath } from '@/lib/artistLinks'
import {
    buildPmsPlaylistDetailPath,
    toPmsPlaylistPlaybackItem,
    toPmsTrackPlaybackItem,
} from '@/lib/pmsPlayback'
import {
    ApiError,
    createPmsPersonalPlaylist,
    fetchPmsPersonalPlaylists,
    fetchPmsPlaylistDetail,
    fetchPmsPlaylistImportBootstrap,
    fetchPmsWorkspaceBootstrap,
    importPmsPlaylists,
} from '@/services/api'
import type {
    PmsPersonalPlaylistBootstrapResponse,
    PmsPlaylistImportBootstrapResponse,
    PmsWorkspaceBootstrapResponse,
} from '@/types/api'

type PmsShelfPlaylist = PmsWorkspaceBootstrapResponse['playlists'][number]
type PmsImportedPlaylist = PmsPlaylistImportBootstrapResponse['imported_playlists'][number]

const isGmsApprovedPlaylist = (playlistId: string) => playlistId.startsWith('gms-ems-')

const playlistSourceLabel = (sourceCollection: string) => {
    switch (sourceCollection) {
        case 'pms-gms-approved-playlist':
            return 'GMS approved'
        case 'pms-user-library':
        case 'pms-imported-playlist':
            return 'Platform import'
        default:
            return undefined
    }
}

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }

    window.open(url, '_blank', 'noopener,noreferrer')
}

const PmsPage = () => {
    const { session, updateSession } = useAuthSession()
    const { playItem, playQueue } = usePlayback()
    const { workspace, updateWorkspace } = useRecommendationWorkspace()
    const [bootstrap, setBootstrap] = useState<PmsWorkspaceBootstrapResponse | null>(null)
    const [importBootstrap, setImportBootstrap] = useState<PmsPlaylistImportBootstrapResponse | null>(null)
    const [personalBootstrap, setPersonalBootstrap] = useState<PmsPersonalPlaylistBootstrapResponse | null>(null)
    const [selectedExternalPlaylistIds, setSelectedExternalPlaylistIds] = useState<string[]>([])
    const [personalPlaylistTitle, setPersonalPlaylistTitle] = useState('My Forever Finds')
    const [personalPlaylistDescription, setPersonalPlaylistDescription] = useState('Songs saved inside my PMS library.')
    const [isLoading, setIsLoading] = useState(true)
    const [isImporting, setIsImporting] = useState(false)
    const [isCreatingPersonalPlaylist, setIsCreatingPersonalPlaylist] = useState(false)
    const [preparingPlaylistId, setPreparingPlaylistId] = useState<string | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [importMessage, setImportMessage] = useState<string | null>(null)
    const [personalPlaylistMessage, setPersonalPlaylistMessage] = useState<string | null>(null)

    const activeUserId = session?.userId

    useEffect(() => {
        const controller = new AbortController()

        setIsLoading(true)
        setError(null)

        const load = async () => {
            try {
                const [workspaceResponse, importResponse, personalResponse] = await Promise.all([
                    fetchPmsWorkspaceBootstrap(activeUserId, workspace.playlistId || undefined, controller.signal),
                    activeUserId
                        ? fetchPmsPlaylistImportBootstrap(activeUserId, controller.signal)
                        : Promise.resolve(null),
                    activeUserId
                        ? fetchPmsPersonalPlaylists(activeUserId, controller.signal)
                        : Promise.resolve(null),
                ])

                startTransition(() => {
                    setBootstrap(workspaceResponse)
                    setImportBootstrap(importResponse)
                    setPersonalBootstrap(personalResponse)
                    setSelectedExternalPlaylistIds((current) => {
                        const nextAvailable = importResponse?.available_playlists
                            .filter((playlist) => !playlist.already_imported)
                            .map((playlist) => playlist.external_playlist_id) ?? []

                        if (current.length > 0) {
                            return current.filter((playlistId) => nextAvailable.includes(playlistId))
                        }

                        return nextAvailable.slice(0, 1)
                    })
                    setError(null)
                })

                const defaultPlaylistId = workspaceResponse.workspace_defaults.playlist_id
                if (defaultPlaylistId && defaultPlaylistId !== workspace.playlistId) {
                    updateWorkspace({ playlistId: defaultPlaylistId })
                }
            } catch (requestError: unknown) {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }

                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load PMS media shelves from the Spring Boot API.'

                startTransition(() => {
                    setError(message)
                })
            } finally {
                setIsLoading(false)
            }
        }

        void load()

        return () => controller.abort()
    }, [activeUserId, session?.preferredPlatformId, workspace.playlistId])

    const selectedPlaylistId = workspace.playlistId || bootstrap?.workspace_defaults.playlist_id || ''

    const activePlaylist = useMemo(
        () =>
            bootstrap?.playlists.find((playlist) => playlist.playlist_id === selectedPlaylistId) ??
            bootstrap?.playlists[0] ??
            null,
        [bootstrap, selectedPlaylistId],
    )

    const importablePlaylists = useMemo(
        () => importBootstrap?.available_playlists.filter((playlist) => !playlist.already_imported) ?? [],
        [importBootstrap],
    )

    const importedPlaylists = importBootstrap?.imported_playlists ?? []
    const personalPlaylists = personalBootstrap?.playlists ?? []
    const visiblePersonalPlaylists = useMemo(
        () => personalPlaylists.filter((playlist) => !isGmsApprovedPlaylist(playlist.playlist_id)),
        [personalPlaylists],
    )
    const gmsApprovedPlaylistCount = useMemo(
        () =>
            bootstrap?.playlists.filter((playlist) => playlist.source_collection === 'pms-gms-approved-playlist')
                .length ?? 0,
        [bootstrap],
    )
    const reconnectRequired = importBootstrap?.platform_connection.reconnect_required ?? false
    const pmsImportSupported = importBootstrap?.platform_connection.pms_import_supported ?? true

    const togglePlaylistSelection = (externalPlaylistId: string) => {
        setSelectedExternalPlaylistIds((current) =>
            current.includes(externalPlaylistId)
                ? current.filter((playlistId) => playlistId !== externalPlaylistId)
                : [...current, externalPlaylistId],
        )
    }

    const reloadPmsData = async (playlistId?: string) => {
        const [workspaceResponse, importResponse, personalResponse] = await Promise.all([
            fetchPmsWorkspaceBootstrap(activeUserId, playlistId ?? workspace.playlistId ?? undefined),
            activeUserId ? fetchPmsPlaylistImportBootstrap(activeUserId) : Promise.resolve(null),
            activeUserId ? fetchPmsPersonalPlaylists(activeUserId) : Promise.resolve(null),
        ])

        setBootstrap(workspaceResponse)
        setImportBootstrap(importResponse)
        setPersonalBootstrap(personalResponse)
        setSelectedExternalPlaylistIds(
            importResponse?.available_playlists
                .filter((playlist) => !playlist.already_imported)
                .map((playlist) => playlist.external_playlist_id)
                .slice(0, 1) ?? [],
        )
        const defaultPlaylistId = workspaceResponse.workspace_defaults.playlist_id
        if (defaultPlaylistId && defaultPlaylistId !== workspace.playlistId) {
            updateWorkspace({ playlistId: defaultPlaylistId })
        }
    }

    const handleCreatePersonalPlaylist = async () => {
        if (!session) {
            setError('Create an account before making a personal PMS playlist.')
            return
        }

        setIsCreatingPersonalPlaylist(true)
        setError(null)
        setPersonalPlaylistMessage(null)

        try {
            const response = await createPmsPersonalPlaylist({
                user_id: session.userId,
                title: personalPlaylistTitle,
                description: personalPlaylistDescription,
            })
            const personalResponse = await fetchPmsPersonalPlaylists(session.userId)
            setPersonalBootstrap(personalResponse)
            setPersonalPlaylistMessage(response.next_step_message)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to create the personal PMS playlist.'
            setError(message)
        } finally {
            setIsCreatingPersonalPlaylist(false)
        }
    }

    const handleImportPlaylists = async () => {
        if (!session || !importBootstrap) {
            setError('Create an account and connect a preferred platform before importing playlists.')
            return
        }

        if (selectedExternalPlaylistIds.length === 0) {
            setError('Choose at least one connected platform playlist to import into PMS.')
            return
        }

        setIsImporting(true)
        setError(null)
        setImportMessage(null)

        try {
            const response = await importPmsPlaylists({
                user_id: session.userId,
                platform_id: importBootstrap.platform_connection.platform_id,
                external_playlist_ids: selectedExternalPlaylistIds,
            })

            await reloadPmsData()
            updateSession({
                onboardingStage: 'pms-imported',
                nextStepPath: response.next_step.path,
                nextStepMessage: response.next_step.message,
            })
            setImportMessage(response.next_step.message)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to import the selected platform playlists into PMS.'
            setError(message)
            if (requestError instanceof ApiError && requestError.code === 'platform_reconnect_required') {
                setImportMessage(null)
            }
        } finally {
            setIsImporting(false)
        }
    }

    const handlePlayPmsPlaylist = async (playlist: PmsShelfPlaylist | PmsImportedPlaylist) => {
        const fallbackPlaylistItem = toPmsPlaylistPlaybackItem({
            ...playlist,
            curator: 'curator' in playlist ? playlist.curator : 'pms library',
            description:
                'highlight' in playlist
                    ? playlist.highlight
                    : `Imported ${new Date(playlist.imported_at).toLocaleString()}`,
        })

        if (!session?.userId) {
            setError('Sign in before starting playlist playback.')
            return
        }

        setError(null)
        setPreparingPlaylistId(playlist.playlist_id)

        try {
            const detail = await fetchPmsPlaylistDetail(session.userId, playlist.playlist_id)
            const playbackItems = detail.tracks
                .map((track) => toPmsTrackPlaybackItem(track, detail.playlist.title))

            if (playbackItems.length > 0) {
                await playQueue(playbackItems, 0)
                return
            }

            await playItem(fallbackPlaylistItem)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to load the PMS playlist tracks for playback.'
            setError(message)
            await playItem(fallbackPlaylistItem)
        } finally {
            setPreparingPlaylistId(null)
        }
    }

    return (
        <div className="space-y-6">
            <section className="grid gap-6 xl:grid-cols-[1.25fr_0.75fr]">
                <HudCard
                    title="Selected PMS Playlist"
                    subtitle="The active playlist drives playback, library context, and later EMS model analysis"
                    action={
                        isLoading ? (
                            <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                                <RefreshCw size={14} className="animate-spin" />
                                Loading media
                            </span>
                        ) : null
                    }
                >
                    {activePlaylist ? (
                        <PlaylistFeatureCard
                            title={activePlaylist.title}
                            sourcePlatform={activePlaylist.source_platform}
                            curator={activePlaylist.curator}
                            trackCount={activePlaylist.track_count}
                            description={activePlaylist.highlight}
                            imageUrl={activePlaylist.cover_image_url}
                            isActive
                            actionLabel="Current Playlist"
                            detailPath={buildPmsPlaylistDetailPath(activePlaylist.playlist_id)}
                            onPlay={() => void handlePlayPmsPlaylist(activePlaylist)}
                            onOpenExternal={() => openExternal(activePlaylist.platform_external_url)}
                        />
                    ) : (
                        <div className="rounded-[24px] border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                            Choose or import a playlist to start the PMS media workspace.
                        </div>
                    )}
                </HudCard>

                <HudCard title="Library Model Loop" subtitle="PMS stores approved music and feeds the user model automatically">
                    <div className="grid gap-4 sm:grid-cols-3">
                        <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Active Tracks</p>
                            <p className="mt-2 text-3xl font-semibold text-hud-text-primary">
                                {activePlaylist?.track_count ?? 0}
                            </p>
                        </div>
                        <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Main Library</p>
                            <p className="mt-2 text-3xl font-semibold text-hud-text-primary">
                                {bootstrap?.playlists.length ?? 0}
                            </p>
                        </div>
                        <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">GMS Approved</p>
                            <p className="mt-2 text-3xl font-semibold text-hud-text-primary">
                                {gmsApprovedPlaylistCount}
                            </p>
                        </div>
                    </div>

                    <div className="mt-5 rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-5 text-sm leading-6 text-hud-text-secondary">
                        GMS saves and feedback return here as PMS library events. EMS/GMS can use the playlist,
                        track metadata, audio features, and approval history without asking the listener to edit model
                        internals.
                    </div>

                    <div className="mt-6 flex flex-wrap gap-3">
                        <Link to="/ems">
                            <Button type="button" variant="primary" glow>
                                Continue to EMS
                            </Button>
                        </Link>
                    </div>
                </HudCard>
            </section>

            <HudCard title="Main PMS Library" subtitle="Platform imports and GMS-approved playlists live together here">
                {bootstrap?.playlists.length ? (
                    <div className="grid gap-5 lg:grid-cols-2">
                        {bootstrap.playlists.map((playlist) => (
                            <PlaylistFeatureCard
                                key={playlist.playlist_id}
                                title={playlist.title}
                                sourcePlatform={playlist.source_platform}
                                sourceLabel={playlistSourceLabel(playlist.source_collection)}
                                curator={playlist.curator}
                                trackCount={playlist.track_count}
                                description={playlist.highlight}
                                imageUrl={playlist.cover_image_url}
                                isActive={playlist.playlist_id === selectedPlaylistId}
                                selectButtonLabel={`Use playlist ${playlist.title}`}
                                detailPath={buildPmsPlaylistDetailPath(playlist.playlist_id)}
                                isPlayLoading={preparingPlaylistId === playlist.playlist_id}
                                onSelect={() => updateWorkspace({ playlistId: playlist.playlist_id })}
                                onPlay={() => void handlePlayPmsPlaylist(playlist)}
                                onOpenExternal={() => openExternal(playlist.platform_external_url)}
                            />
                        ))}
                    </div>
                ) : (
                    <div className="rounded-[24px] border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                        Playlist cards will appear here once PMS bootstrap data is available.
                    </div>
                )}
            </HudCard>

            <HudCard title="Track Shelf" subtitle="Album art, metadata, and playable tracks from the selected PMS context">
                {bootstrap?.suggested_tracks.length ? (
                    <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-4">
                        {bootstrap.suggested_tracks.map((track) => {
                            const audioFeaturesFilled =
                                track.audio_features_filled ?? track.spotify_audio_features_filled
                            const audioFeatureSource =
                                track.audio_feature_source ?? track.spotify_audio_feature_source
                            const spotifyTrackId =
                                track.spotify_track_id ?? track.audio_feature_track_id
                            const playbackPlatformId =
                                track.preferred_playback_platform ??
                                (track.tidal_track_id ? 'tidal' : track.spotify_track_id ? 'spotify' : track.source_platform)

                            return (
                                <TrackFeatureCard
                                key={track.track_id}
                                title={track.title}
                                artistName={track.artist_name}
                                sourcePlatform={track.source_platform}
                                albumTitle={track.album_title}
                                imageUrl={track.album_image_url}
                                durationMs={track.duration_ms}
                                artistDetailPath={buildArtistDetailPath(track.artist_name)}
                                badges={[
                                    track.seed ? 'library anchor' : 'library track',
                                    audioFeaturesFilled ? 'audio enriched' : 'audio pending',
                                ]}
                                reason={`Audio feature source: ${audioFeatureSource}. EMS and GMS consume this context automatically.`}
                                onPlay={() =>
                                    playItem({
                                        id: `track:${track.track_id}`,
                                        kind: 'track',
                                        title: track.title,
                                        subtitle: `${track.artist_name} · ${track.source_platform}`,
                                        sourcePlatform: track.source_platform,
                                        imageUrl: track.album_image_url,
                                        albumTitle: track.album_title,
                                        externalUrl: track.platform_external_url,
                                        platformUri: track.platform_uri,
                                        previewUrl: track.preview_url,
                                        playbackPlatformId,
                                        spotifyTrackId,
                                        tidalTrackId: track.tidal_track_id,
                                        durationMs: track.duration_ms,
                                        supportingText: activePlaylist?.title ?? null,
                                    })
                                }
                                onOpenExternal={() => openExternal(track.platform_external_url)}
                            />
                            )
                        })}
                    </div>
                ) : (
                    <div className="rounded-[24px] border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                        Relevant tracks for the selected PMS playlist will appear here.
                    </div>
                )}
            </HudCard>

            <HudCard
                title="Personal Playlists"
                subtitle="Member-owned PMS playlists that can collect GMS saves and library tracks"
                action={
                    isCreatingPersonalPlaylist ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            Creating
                        </span>
                    ) : null
                }
            >
                <div className="grid gap-6 xl:grid-cols-[0.8fr_1.2fr]">
                    <div className="space-y-4">
                        <div>
                            <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Playlist Title</label>
                            <input
                                value={personalPlaylistTitle}
                                onChange={(event) => setPersonalPlaylistTitle(event.target.value)}
                                className="w-full rounded-2xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                            />
                        </div>

                        <div>
                            <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Description</label>
                            <textarea
                                value={personalPlaylistDescription}
                                onChange={(event) => setPersonalPlaylistDescription(event.target.value)}
                                rows={3}
                                className="w-full rounded-2xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                            />
                        </div>

                        <Button
                            type="button"
                            variant="primary"
                            glow
                            disabled={!session || isCreatingPersonalPlaylist}
                            onClick={handleCreatePersonalPlaylist}
                        >
                            <Plus size={18} />
                            Create Playlist
                        </Button>

                        {personalPlaylistMessage && (
                            <div className="rounded-[24px] border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {personalPlaylistMessage}
                            </div>
                        )}
                    </div>

                    <div>
                        {visiblePersonalPlaylists.length > 0 ? (
                            <div className="grid gap-5 lg:grid-cols-2">
                                {visiblePersonalPlaylists.map((playlist) => (
                                    <PlaylistFeatureCard
                                        key={playlist.playlist_id}
                                        title={playlist.title}
                                        sourcePlatform="pms"
                                        curator="personal playlist"
                                        trackCount={playlist.track_count}
                                        description={playlist.description}
                                        imageUrl={playlist.tracks[0]?.album_image_url ?? null}
                                        actionLabel={`${playlist.track_count} saved`}
                                        detailPath={buildPmsPlaylistDetailPath(playlist.playlist_id)}
                                        onPlay={() =>
                                            playItem({
                                                id: `personal-playlist:${playlist.playlist_id}`,
                                                kind: 'playlist',
                                                title: playlist.title,
                                                subtitle: `PMS personal · ${playlist.track_count} tracks`,
                                                sourcePlatform: 'pms',
                                                imageUrl: playlist.tracks[0]?.album_image_url ?? null,
                                                supportingText: playlist.description,
                                            })
                                        }
                                    />
                                ))}
                            </div>
                        ) : (
                            <div className="rounded-[24px] border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                                Personal playlists will appear here after you create one or save a GMS recommendation.
                            </div>
                        )}
                    </div>
                </div>
            </HudCard>

            <HudCard
                title="Platform Import Queue"
                subtitle="Connected platform playlists that can be pulled into the PMS library"
                action={
                    isImporting ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            Importing
                        </span>
                    ) : null
                }
            >
                <div className="space-y-5">
                    {!session ? (
                        <div className="rounded-[24px] border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                            Create an account and connect a streaming platform first. PMS import attaches these
                            playlists to a specific member profile.
                        </div>
                    ) : (
                        <>
                            <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-5">
                                <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Preferred Platform</p>
                                <h3 className="mt-3 text-xl font-semibold text-hud-text-primary">
                                    {importBootstrap?.platform_connection.display_name ?? session.preferredPlatformId}
                                </h3>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    {importBootstrap?.summary.next_step_message ?? 'Connect a platform to import PMS playlists.'}
                                </p>
                            </div>

                            {importMessage && (
                                <div className="rounded-[24px] border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                    {importMessage}
                                </div>
                            )}

                            {reconnectRequired && (
                                <div className="rounded-[24px] border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                    Reconnect the preferred platform first so PMS can keep importing playable library
                                    content.
                                </div>
                            )}

                            {!pmsImportSupported && (
                                <div className="rounded-[24px] border border-hud-accent-info/40 bg-hud-accent-info/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                    This preferred platform is useful for long-term analysis signals, but PMS playlist
                                    import is not ready yet.
                                </div>
                            )}

                            {importablePlaylists.length > 0 && (
                                <div className="grid gap-5 lg:grid-cols-2">
                                    {importablePlaylists.map((playlist) => {
                                        const selected = selectedExternalPlaylistIds.includes(playlist.external_playlist_id)
                                        return (
                                            <PlaylistFeatureCard
                                                key={playlist.external_playlist_id}
                                                title={playlist.title}
                                                sourcePlatform={playlist.source_platform}
                                                curator={playlist.curator}
                                                trackCount={playlist.track_count}
                                                description={playlist.description}
                                                imageUrl={playlist.cover_image_url}
                                                isActive={selected}
                                                actionLabel={selected ? 'Queued for Import' : 'Queue for Import'}
                                                onSelect={() => togglePlaylistSelection(playlist.external_playlist_id)}
                                                onPlay={() =>
                                                    playItem({
                                                        id: `import-playlist:${playlist.external_playlist_id}`,
                                                        kind: 'playlist',
                                                        title: playlist.title,
                                                        subtitle: `${playlist.curator} · ${playlist.source_platform}`,
                                                        sourcePlatform: playlist.source_platform,
                                                        imageUrl: playlist.cover_image_url,
                                                        externalUrl: playlist.platform_external_url,
                                                        platformUri: playlist.platform_uri,
                                                        supportingText: playlist.description,
                                                    })
                                                }
                                                onOpenExternal={() => openExternal(playlist.platform_external_url)}
                                            />
                                        )
                                    })}
                                </div>
                            )}

                            {importedPlaylists.length > 0 && (
                                <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-5 text-sm leading-6 text-hud-text-secondary">
                                    {importedPlaylists.length} playlists already live in the main PMS library.
                                </div>
                            )}

                            <div className="flex flex-wrap gap-3">
                                <Button
                                    type="button"
                                    variant="primary"
                                    glow
                                    disabled={isImporting || selectedExternalPlaylistIds.length === 0 || reconnectRequired}
                                    onClick={handleImportPlaylists}
                                >
                                    <LibraryBig size={18} />
                                    Import Selected Playlists
                                </Button>
                                <Link to="/platforms">
                                    <Button type="button" variant="outline">
                                        Manage Platform Connections
                                    </Button>
                                </Link>
                                <Link to="/ems">
                                    <Button type="button" variant="ghost">
                                        <Sparkles size={18} />
                                        Continue to EMS
                                    </Button>
                                </Link>
                            </div>
                        </>
                    )}

                    {error && (
                        <div className="rounded-[24px] border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {error}
                        </div>
                    )}
                </div>
            </HudCard>
        </div>
    )
}

export default PmsPage
