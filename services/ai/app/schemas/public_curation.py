from __future__ import annotations

from datetime import datetime
from typing import Any

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


class PublicCurationCandidateTrack(BaseModel):
    model_config = ConfigDict(extra="allow")

    source_scope: str
    source_id: str
    title: str
    artist_name: str
    album_title: str | None = None
    duration_ms: int | None = Field(default=None, ge=0)
    isrc: str | None = None
    source_platform: str | None = None
    tidal_track_id: str | None = None
    tidal_uri: str | None = None
    tidal_external_url: str | None = None
    audio_features: dict[str, float] = Field(default_factory=dict)
    genres: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    popularity: float | None = Field(default=None, ge=0.0, le=1.0)
    freshness: float | None = Field(default=None, ge=0.0, le=1.0)


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
    duration_ms: int | None
    isrc: str | None
    tidal_track_id: str
    tidal_uri: str
    tidal_external_url: str | None
    score: float
    score_breakdown: dict[str, float]
    reason: str


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
    warnings: list[str]
