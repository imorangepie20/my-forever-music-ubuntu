from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class PublicCurationAudioFeatureRange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    min: float | None = Field(default=None, ge=0.0)
    max: float | None = Field(default=None)


class PublicCurationFilters(BaseModel):
    model_config = ConfigDict(extra="allow")

    mood_tags: list[str] = Field(default_factory=list)
    genre_tags: list[str] = Field(default_factory=list)
    audio_feature_ranges: dict[str, PublicCurationAudioFeatureRange] = Field(default_factory=dict)


class PublicCurationSourcePlaylistSignals(BaseModel):
    model_config = ConfigDict(extra="allow")

    playlist_count: int = Field(default=0, ge=0)
    max_followers_count: int | None = Field(default=None, ge=0)
    titles: list[str] = Field(default_factory=list)
    descriptions: list[str] = Field(default_factory=list)
    curators: list[str] = Field(default_factory=list)
    collection_sources: list[str] = Field(default_factory=list)
    search_queries: list[str] = Field(default_factory=list)


class PublicCurationAudienceResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    play_started_count: int = Field(default=0, ge=0)
    play_completed_count: int = Field(default=0, ge=0)
    skip_count: int = Field(default=0, ge=0)


class PublicCurationCandidateTrack(BaseModel):
    model_config = ConfigDict(extra="allow")

    source_scope: str
    source_id: str
    title: str
    artist_name: str
    album_title: str | None = None
    image_url: str | None = None
    duration_ms: int | None = Field(default=None, ge=0)
    isrc: str | None = None
    source_platform: str | None = None
    tidal_track_id: str | None = None
    tidal_uri: str | None = None
    tidal_external_url: str | None = None
    audio_features: dict[str, float] = Field(default_factory=dict)
    audio_feature_source: str | None = None
    audio_feature_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    audio_features_filled: bool = False
    genres: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    metadata_tags: list[str] = Field(default_factory=list)
    source_playlist_signals: PublicCurationSourcePlaylistSignals = Field(
        default_factory=PublicCurationSourcePlaylistSignals
    )
    audience_response: PublicCurationAudienceResponse = Field(
        default_factory=PublicCurationAudienceResponse
    )
    popularity: float | None = Field(default=None, ge=0.0, le=1.0)
    freshness: float | None = Field(default=None, ge=0.0, le=1.0)
    playback_resolution_status: str | None = None


class PublicCurationScoreRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    prompt: str = Field(min_length=1)
    filters: PublicCurationFilters = Field(default_factory=PublicCurationFilters)
    target_track_count: int = Field(default=30, ge=1, le=100)
    candidate_tracks: list[PublicCurationCandidateTrack] = Field(default_factory=list)


class PublicCurationSelectedTrack(BaseModel):
    order: int
    source_scope: str
    source_track_id: str
    title: str
    artist_name: str
    album_title: str | None
    image_url: str | None
    duration_ms: int | None
    isrc: str | None
    tidal_track_id: str
    tidal_uri: str
    tidal_external_url: str | None
    score: float
    score_breakdown: dict[str, float]
    reason: str


class PublicCurationSemanticProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mood_tags: list[str] = Field(default_factory=list)
    genre_tags: list[str] = Field(default_factory=list)
    metadata_tags: list[str] = Field(default_factory=list)
    target_audio_features: dict[str, float] = Field(default_factory=dict)
    energy_curve: list[Annotated[float, Field(ge=0.0, le=1.0)]] = Field(default_factory=list)


class PublicCurationSemanticProfileResult(BaseModel):
    status: str
    model: str | None = None
    profile: PublicCurationSemanticProfile
    warnings: list[str] = Field(default_factory=list)


class PublicCurationScoreResponse(BaseModel):
    generated_at: datetime
    service: str
    status: str
    model_version: str
    title: str
    subtitle: str
    description: str
    tracks: list[PublicCurationSelectedTrack]
    score_summary: dict[str, Any]
    semantic_profile_status: str
    semantic_profile_model: str | None
    semantic_profile: PublicCurationSemanticProfile
    warnings: list[str]
