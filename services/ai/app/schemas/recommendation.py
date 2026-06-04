from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

RecommendationMode = Literal["pms", "ems", "gms", "discovery"]
RecommendationMood = Literal["focus", "calm", "upbeat", "melancholy", "discovery"]
RecommendationStrategy = Literal[
    "pms-seed-match",
    "ems-mood-match",
    "gms-hybrid-blend",
    "discovery-fallback",
]


class RecommendationPreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str | None = Field(default=None, description="Caller-provided trace id.")
    user_id: str | None = Field(default=None, description="Optional user id for personalization.")
    playlist_id: str | None = Field(default=None, description="Optional playlist id for PMS context.")
    mode: RecommendationMode = Field(default="gms", description="Recommendation space.")
    mood: RecommendationMood | None = Field(default=None, description="Target listening mood.")
    energy_level: int | None = Field(default=None, ge=1, le=5, description="Target energy level.")
    familiarity_bias: int = Field(
        default=3,
        ge=1,
        le=5,
        description="Higher values favor familiar seeds over discovery.",
    )
    limit: int = Field(default=10, ge=1, le=50, description="Maximum number of items.")
    seed_track_ids: list[str] = Field(default_factory=list, description="Seed track identifiers.")
    seed_artist_names: list[str] = Field(default_factory=list, description="Seed artist names.")
    seed_genres: list[str] = Field(default_factory=list, description="Seed genres.")
    include_explanations: bool = Field(
        default=True,
        description="Include human-readable explanation strings in the response.",
    )


class RecommendationContext(BaseModel):
    strategy: RecommendationStrategy
    engine: str
    mode: RecommendationMode
    mood: RecommendationMood | None
    energy_level: int
    seed_basis: list[str]


class RecommendationInputSummary(BaseModel):
    user_id: str | None
    playlist_id: str | None
    track_seed_count: int
    artist_seed_count: int
    genre_seed_count: int
    familiarity_bias: int
    limit: int


class RecommendationItem(BaseModel):
    rank: int
    track_id: str
    title: str
    artist_name: str
    score: float
    source_space: RecommendationMode
    energy_level: int
    reason: str | None = None


class RecommendationPreviewResponse(BaseModel):
    request_id: str
    generated_at: datetime
    service: str
    status: str
    context: RecommendationContext
    input_summary: RecommendationInputSummary
    items: list[RecommendationItem]
    warnings: list[str]
