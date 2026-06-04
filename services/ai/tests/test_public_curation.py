from copy import deepcopy
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.schemas.public_curation import PublicCurationScoreRequest
from app.main import app
from app.services.public_curation_semantic_profile_service import (
    PublicCurationSemanticProfileService,
)

client = TestClient(app)


@pytest.fixture(autouse=True)
def deterministic_semantic_fallback(monkeypatch):
    import app.services.public_curation_semantic_profile_service as semantic_module

    monkeypatch.setattr(
        semantic_module,
        "get_settings",
        lambda: SimpleNamespace(
            public_curation_semantic_model="",
            llm_api_key="",
            llm_base_url="https://api.openai.com/v1",
        ),
    )


def test_public_curation_score_selects_tidal_ready_tracks() -> None:
    response = client.post("/v1/public-curations/score", json=sample_payload())

    assert response.status_code == 200
    payload = response.json()
    assert payload["service"] == "public-curation"
    assert payload["status"] == "ok"
    assert payload["model_version"] == "public-curation-hybrid-v2"
    assert payload["title"] == "비 오는 밤의 Public Curation"
    assert len(payload["tracks"]) == 2
    assert [track["source_track_id"] for track in payload["tracks"]] == [
        "ems-track-1",
        "pms-track-3",
    ]
    assert payload["tracks"][0]["order"] == 1
    assert payload["tracks"][0]["score_breakdown"]["tidal_readiness"] == 1.0
    assert payload["tracks"][0]["reason"]
    assert payload["tracks"][0]["image_url"] == "https://images.example/rain-street.jpg"
    assert payload["score_summary"]["candidate_count"] == 3
    assert payload["score_summary"]["tidal_ready_count"] == 2
    assert payload["semantic_profile_status"] == "semantic_fallback"


def test_public_curation_scores_resolved_tidal_candidates_with_small_penalty() -> None:
    request = sample_payload()
    native = deepcopy(request["candidate_tracks"][0])
    resolved = deepcopy(request["candidate_tracks"][0])
    native.update(
        {
            "source_id": "native-001",
            "title": "Rain Street Native",
            "isrc": "KRA000000101",
            "tidal_track_id": "90001",
            "tidal_uri": "tidal:track:90001",
            "playback_resolution_status": "native_tidal",
        }
    )
    resolved.update(
        {
            "source_id": "resolved-001",
            "title": "Rain Street Resolved",
            "isrc": "KRA000000102",
            "tidal_track_id": "90002",
            "tidal_uri": "tidal:track:90002",
            "playback_resolution_status": "resolved_to_tidal",
        }
    )
    request["candidate_tracks"] = [native, resolved]
    request["target_track_count"] = 2

    response = client.post("/v1/public-curations/score", json=request)

    assert response.status_code == 200
    tracks = response.json()["tracks"]
    breakdowns = {track["source_track_id"]: track["score_breakdown"] for track in tracks}
    assert breakdowns["native-001"]["playback_resolution_confidence"] == 1.0
    assert breakdowns["resolved-001"]["playback_resolution_confidence"] == 0.96


def test_public_curation_semantic_profile_falls_back_when_llm_is_not_configured(
    monkeypatch,
) -> None:
    import app.services.public_curation_semantic_profile_service as semantic_module

    monkeypatch.setattr(
        semantic_module,
        "get_settings",
        lambda: SimpleNamespace(
            public_curation_semantic_model="",
            llm_api_key="",
            llm_base_url="https://api.openai.com/v1",
        ),
    )

    result = PublicCurationSemanticProfileService().resolve(
        PublicCurationScoreRequest.model_validate(sample_payload())
    )

    assert result.status == "semantic_fallback"
    assert result.model is None
    assert "rainy" in result.profile.mood_tags
    assert result.warnings


def test_public_curation_semantic_profile_uses_llm_result_when_available(
    monkeypatch,
) -> None:
    import app.services.public_curation_semantic_profile_service as semantic_module

    monkeypatch.setattr(
        semantic_module,
        "get_settings",
        lambda: SimpleNamespace(
            public_curation_semantic_model="gpt-test",
            llm_api_key="test-key",
            llm_base_url="https://api.openai.com/v1",
        ),
    )
    service = PublicCurationSemanticProfileService()
    monkeypatch.setattr(
        service,
        "_call_llm",
        lambda settings, request: {
            "mood_tags": ["rainy", "night"],
            "genre_tags": ["jazz", "indie"],
            "metadata_tags": ["cafe", "window"],
            "target_audio_features": {"energy": 0.38, "valence": 0.42},
            "energy_curve": [0.3, 0.42, 0.55, 0.4],
        },
    )

    result = service.resolve(PublicCurationScoreRequest.model_validate(sample_payload()))

    assert result.status == "semantic_profile"
    assert result.model == "gpt-test"
    assert result.profile.target_audio_features["energy"] == 0.38
    assert result.warnings == []


def test_public_curation_semantic_profile_falls_back_when_llm_request_fails(
    monkeypatch,
) -> None:
    import app.services.public_curation_semantic_profile_service as semantic_module

    monkeypatch.setattr(
        semantic_module,
        "get_settings",
        lambda: SimpleNamespace(
            public_curation_semantic_model="gpt-test",
            llm_api_key="test-key",
            llm_base_url="https://api.openai.com/v1",
        ),
    )
    service = PublicCurationSemanticProfileService()
    monkeypatch.setattr(
        service,
        "_call_llm",
        lambda settings, request: (_ for _ in ()).throw(RuntimeError("offline")),
    )

    result = service.resolve(PublicCurationScoreRequest.model_validate(sample_payload()))

    assert result.status == "semantic_fallback"
    assert result.model == "gpt-test"
    assert "semantic_error=RuntimeError" in result.warnings


def test_public_curation_scores_change_when_candidate_signals_change() -> None:
    request = sample_payload()
    request["target_track_count"] = 3
    request["candidate_tracks"].append(
        tidal_candidate(
            source_id="lower-signal-track",
            title="Ordinary Street",
            artist_name="Quiet Person",
            isrc=None,
            tidal_track_id="10004",
            popularity=0.1,
            freshness=0.1,
        )
    )
    request["candidate_tracks"][-1]["audio_feature_confidence"] = 0.2
    request["candidate_tracks"][-1]["audio_feature_source"] = "llm_inference"
    request["candidate_tracks"][-1]["audience_response"] = {
        "play_started_count": 8,
        "play_completed_count": 0,
        "skip_count": 6,
    }

    response = client.post("/v1/public-curations/score", json=request)

    assert response.status_code == 200
    payload = response.json()
    first_breakdown = payload["tracks"][0]["score_breakdown"]
    last_breakdown = payload["tracks"][-1]["score_breakdown"]
    assert first_breakdown != last_breakdown
    assert first_breakdown["metadata_quality"] > last_breakdown["metadata_quality"]
    assert first_breakdown["audience_response"] > last_breakdown["audience_response"]


def test_public_curation_reranker_avoids_repeating_same_artist_when_possible() -> None:
    request = sample_payload()
    request["target_track_count"] = 3
    request["candidate_tracks"].extend(
        [
            tidal_candidate(
                source_id="same-artist-high-score",
                title="Rain Street Reprise",
                artist_name="Blue Trio",
                isrc="KRA000000010",
                tidal_track_id="10010",
                popularity=0.95,
                freshness=0.95,
            ),
            tidal_candidate(
                source_id="different-artist",
                title="Night Bus",
                artist_name="Amber Keys",
                isrc="KRA000000011",
                tidal_track_id="10011",
                popularity=0.7,
                freshness=0.7,
            ),
        ]
    )

    response = client.post("/v1/public-curations/score", json=request)

    assert response.status_code == 200
    artists = [track["artist_name"] for track in response.json()["tracks"]]
    assert len(artists) == len(set(artists))


def test_public_curation_score_deduplicates_tracks_before_selecting_top_tracks() -> None:
    request = sample_payload()
    request["target_track_count"] = 6
    request["candidate_tracks"].extend(
        [
            tidal_candidate(
                source_id="duplicate-isrc",
                title="Different Metadata",
                artist_name="Different Artist",
                isrc=" kra-000000001 ",
                tidal_track_id="20001",
                popularity=0.1,
                freshness=0.1,
            ),
            tidal_candidate(
                source_id="duplicate-tidal-id",
                title="Another Song",
                artist_name="Another Artist",
                isrc="KRA000000099",
                tidal_track_id=" 10003 ",
                popularity=0.1,
                freshness=0.1,
            ),
            tidal_candidate(
                source_id="unicode-metadata-original",
                title="별 빛",
                artist_name="가수 Étoile",
                isrc=None,
                tidal_track_id="30001",
                popularity=0.9,
                freshness=0.9,
            ),
            tidal_candidate(
                source_id="duplicate-unicode-metadata",
                title=" 별-빛 ",
                artist_name="가수 étoile",
                isrc=None,
                tidal_track_id="30002",
                popularity=0.1,
                freshness=0.1,
            ),
        ]
    )

    response = client.post("/v1/public-curations/score", json=request)

    assert response.status_code == 200
    payload = response.json()
    assert {track["source_track_id"] for track in payload["tracks"]} == {
        "unicode-metadata-original",
        "ems-track-1",
        "pms-track-3",
    }
    assert payload["score_summary"]["duplicate_candidate_count"] == 3
    assert payload["score_summary"]["unique_tidal_ready_count"] == 3


def tidal_candidate(
    *,
    source_id: str,
    title: str,
    artist_name: str,
    isrc: str | None,
    tidal_track_id: str,
    popularity: float,
    freshness: float,
) -> dict:
    return {
        "source_scope": "ems_collected_track",
        "source_id": source_id,
        "title": title,
        "artist_name": artist_name,
        "album_title": "Public Mix",
        "duration_ms": 180000,
        "isrc": isrc,
        "source_platform": "tidal",
        "tidal_track_id": tidal_track_id,
        "tidal_uri": f"tidal:track:{tidal_track_id.strip()}",
        "audio_features": {
            "energy": 0.4,
            "valence": 0.4,
            "acousticness": 0.7,
        },
        "genres": ["jazz"],
        "tags": ["rainy"],
        "popularity": popularity,
        "freshness": freshness,
    }


def sample_payload() -> dict:
    return {
        "prompt": "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
        "filters": {
            "mood_tags": ["rainy", "jazz"],
            "audio_feature_ranges": {
                "energy": {"min": 0.2, "max": 0.6},
                "valence": {"min": 0.2, "max": 0.7},
                "acousticness": {"min": 0.4, "max": 1.0},
            },
        },
        "target_track_count": 2,
        "candidate_tracks": [
            {
                "source_scope": "ems_collected_track",
                "source_id": "ems-track-1",
                "title": "Rain Street",
                "artist_name": "Blue Trio",
                "album_title": "Night Walk",
                "image_url": "https://images.example/rain-street.jpg",
                "duration_ms": 181000,
                "isrc": "KRA000000001",
                "source_platform": "tidal",
                "tidal_track_id": "10001",
                "tidal_uri": "tidal:track:10001",
                "audio_features": {
                    "energy": 0.42,
                    "valence": 0.38,
                    "acousticness": 0.72,
                    "danceability": 0.48,
                    "tempo": 92,
                },
                "genres": ["jazz", "indie"],
                "tags": ["rainy", "night"],
                "popularity": 0.62,
                "freshness": 0.72,
            },
            {
                "source_scope": "search_pool",
                "source_id": "search-track-2",
                "title": "No Stream",
                "artist_name": "Hidden Artist",
                "album_title": "Unavailable",
                "duration_ms": 200000,
                "isrc": "KRA000000002",
                "source_platform": "youtube",
                "audio_features": {
                    "energy": 0.31,
                    "valence": 0.42,
                    "acousticness": 0.82,
                },
                "genres": ["jazz"],
                "tags": ["rainy"],
                "popularity": 0.4,
                "freshness": 0.95,
            },
            {
                "source_scope": "pms_user_track",
                "source_id": "pms-track-3",
                "title": "Late Cafe",
                "artist_name": "Gray Window",
                "album_title": "Coffee Lights",
                "duration_ms": 205000,
                "isrc": "KRA000000003",
                "source_platform": "tidal",
                "tidal_track_id": "10003",
                "tidal_uri": "tidal:track:10003",
                "audio_features": {
                    "energy": 0.58,
                    "valence": 0.46,
                    "acousticness": 0.63,
                    "danceability": 0.55,
                    "tempo": 104,
                },
                "genres": ["indie"],
                "tags": ["cafe", "night"],
                "popularity": 0.54,
                "freshness": 0.68,
            },
        ],
    }
