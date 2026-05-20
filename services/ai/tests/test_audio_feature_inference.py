from fastapi import HTTPException
import pytest

from app.config import get_settings
from app.schemas.audio_feature_inference import AudioFeatureInferenceRequest
from app.services.audio_feature_inference_service import AudioFeatureInferenceService


def test_audio_feature_inference_requires_llm_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_LLM_API_KEY", raising=False)
    get_settings.cache_clear()

    with pytest.raises(HTTPException) as exception:
        AudioFeatureInferenceService().infer(sample_request())

    assert exception.value.status_code == 503
    assert "AI_LLM_API_KEY" in exception.value.detail
    get_settings.cache_clear()


def test_audio_feature_inference_accepts_schema_compliant_llm_response(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_LLM_API_KEY", "test-key")
    monkeypatch.setenv("AI_AUDIO_FEATURE_INFERENCE_MODEL", "test-audio-model")
    get_settings.cache_clear()

    monkeypatch.setattr(
        AudioFeatureInferenceService,
        "_call_llm",
        lambda self, settings, request: {
            "confidence": 0.82,
            "duration_ms": 180000,
            "musical_key": 5,
            "mode": 1,
            "acousticness": 0.18,
            "danceability": 0.74,
            "energy": 0.79,
            "instrumentalness": 0.03,
            "liveness": 0.12,
            "loudness": -6.8,
            "speechiness": 0.05,
            "tempo": 121.0,
            "valence": 0.62,
            "rationale": "Evidence describes an uptempo electronic pop track with dance-focused production.",
            "evidence": [
                {
                    "source_name": "openai_web_search",
                    "source_url": "https://example.test/review",
                    "evidence_kind": "web_search_result",
                    "evidence_text": "Review describes the track as upbeat electronic pop.",
                    "confidence": 0.78,
                }
            ],
        },
    )

    response = AudioFeatureInferenceService().infer(sample_request())

    assert response.status == "ok"
    assert response.model == "test-audio-model"
    assert response.source == "llm_search_inferred"
    assert response.source_class == "llm_search_inferred"
    assert response.audio_features_filled is True
    assert response.danceability == 0.74
    assert response.evidence[0].source_url == "https://example.test/review"
    get_settings.cache_clear()


def sample_request() -> AudioFeatureInferenceRequest:
    return AudioFeatureInferenceRequest(
        track_scope="pms_user_track",
        track_id="track-001",
        title="Signal Track",
        artist_name="Signal Artist",
        duration_ms=180000,
        source_url="https://open.spotify.com/track/spotify-track-001",
    )
