from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class AudioFeatureInferenceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    track_scope: str = Field(min_length=1, max_length=80)
    track_id: str = Field(min_length=1, max_length=120)
    title: str = Field(min_length=1, max_length=300)
    artist_name: str = Field(min_length=1, max_length=300)
    duration_ms: int | None = Field(default=None, ge=1, le=7_200_000)
    source_url: str | None = Field(default=None, max_length=500)


class AudioFeatureInferenceEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source_name: str = Field(min_length=1, max_length=80)
    source_url: str | None = Field(default=None, max_length=500)
    evidence_kind: str = Field(min_length=1, max_length=80)
    evidence_text: str = Field(min_length=1, max_length=1000)
    confidence: float = Field(ge=0.0, le=1.0)


class AudioFeatureInferenceResponse(BaseModel):
    request_id: str
    generated_at: datetime
    service: str
    status: Literal["ok", "low_confidence"]
    model: str
    source: Literal["llm_search_inferred"]
    source_class: Literal["llm_search_inferred"]
    confidence: float = Field(ge=0.0, le=1.0)
    model_version: str
    audio_features_filled: bool
    duration_ms: int | None = Field(default=None, ge=1, le=7_200_000)
    musical_key: int = Field(ge=0, le=11)
    mode: int = Field(ge=0, le=1)
    acousticness: float = Field(ge=0.0, le=1.0)
    danceability: float = Field(ge=0.0, le=1.0)
    energy: float = Field(ge=0.0, le=1.0)
    instrumentalness: float = Field(ge=0.0, le=1.0)
    liveness: float = Field(ge=0.0, le=1.0)
    loudness: float = Field(ge=-60.0, le=5.0)
    speechiness: float = Field(ge=0.0, le=1.0)
    tempo: float = Field(ge=30.0, le=240.0)
    valence: float = Field(ge=0.0, le=1.0)
    rationale: str = Field(default="", max_length=1000)
    evidence: list[AudioFeatureInferenceEvidence] = Field(default_factory=list, max_length=8)
    warnings: list[str] = Field(default_factory=list, max_length=8)
