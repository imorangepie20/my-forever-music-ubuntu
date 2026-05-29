from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_public_curation_score_selects_tidal_ready_tracks() -> None:
    response = client.post("/v1/public-curations/score", json=sample_payload())

    assert response.status_code == 200
    payload = response.json()
    assert payload["service"] == "public-curation"
    assert payload["status"] == "ok"
    assert payload["model_version"] == "public-curation-deterministic-v1"
    assert payload["title"] == "비 오는 밤의 Public Curation"
    assert len(payload["tracks"]) == 2
    assert [track["source_track_id"] for track in payload["tracks"]] == [
        "ems-track-1",
        "pms-track-3",
    ]
    assert payload["tracks"][0]["order"] == 1
    assert payload["tracks"][0]["score_breakdown"]["tidal_readiness"] == 1.0
    assert payload["tracks"][0]["reason"]
    assert payload["score_summary"]["candidate_count"] == 3
    assert payload["score_summary"]["tidal_ready_count"] == 2


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
