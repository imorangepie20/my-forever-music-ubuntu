# AI Audio Feature Inference API

작성일: `2026-05-20`

`services/ai`가 OpenAI Responses API의 web search와 structured output을 사용해 비어 있는 audio feature estimate를 생성하는 내부 API입니다. 외부 사용자에게 직접 노출하지 않고 `services/api`의 audio feature completion worker가 호출합니다.

## Endpoint

```http
POST /v1/audio-features/infer
```

Request:

```json
{
  "track_scope": "pms_user_track",
  "track_id": "track-001",
  "title": "Signal Track",
  "artist_name": "Signal Artist",
  "duration_ms": 180000,
  "source_url": "https://open.spotify.com/track/spotify-track-001"
}
```

Response:

```json
{
  "request_id": "ai-audio-feature-abc123",
  "generated_at": "2026-05-20T00:00:00Z",
  "service": "ai",
  "status": "ok",
  "model": "gpt-5-mini",
  "source": "llm_search_inferred",
  "source_class": "llm_search_inferred",
  "confidence": 0.82,
  "model_version": "audio-feature-llm-search-v1:gpt-5-mini",
  "audio_features_filled": true,
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
      "confidence": 0.78
    }
  ],
  "warnings": []
}
```

## Rules

- `AI_LLM_API_KEY`가 없으면 `503`을 반환합니다.
- 기본 모델은 `AI_AUDIO_FEATURE_INFERENCE_MODEL=gpt-5-mini`입니다.
- `confidence < AI_AUDIO_FEATURE_INFERENCE_MIN_CONFIDENCE` 이거나 evidence가 비어 있으면 `status=low_confidence`로 반환하고 Spring worker는 저장하지 않습니다.
- 응답은 JSON schema validation을 통과해야 합니다.
- 값은 측정값이 아니므로 source/source_class는 항상 `llm_search_inferred`입니다.
- Spring worker는 response evidence와 result payload를 `track_audio_feature_evidence`에 저장합니다.
