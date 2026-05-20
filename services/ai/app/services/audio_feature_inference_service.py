from __future__ import annotations

from datetime import datetime, timezone
import json
from urllib import request as urllib_request
from urllib.error import HTTPError, URLError
from uuid import uuid4

from fastapi import HTTPException

from app.config import get_settings
from app.schemas.audio_feature_inference import (
    AudioFeatureInferenceEvidence,
    AudioFeatureInferenceRequest,
    AudioFeatureInferenceResponse,
)


MODEL_VERSION = "audio-feature-llm-search-v1"


class AudioFeatureInferenceService:
    def infer(self, request: AudioFeatureInferenceRequest) -> AudioFeatureInferenceResponse:
        settings = get_settings()
        request_id = f"ai-audio-feature-{uuid4().hex[:12]}"

        if not settings.llm_api_key:
            raise HTTPException(
                status_code=503,
                detail="Audio feature inference requires AI_LLM_API_KEY.",
            )

        parsed = self._call_llm(settings, request)
        confidence = float(parsed.get("confidence", 0.0))
        evidence_items = parsed.get("evidence", [])
        status = "ok" if confidence >= settings.audio_feature_inference_min_confidence and evidence_items else "low_confidence"
        return AudioFeatureInferenceResponse(
            request_id=request_id,
            generated_at=datetime.now(timezone.utc),
            service="ai",
            status=status,
            model=settings.audio_feature_inference_model,
            source="llm_search_inferred",
            source_class="llm_search_inferred",
            confidence=confidence,
            model_version=f"{MODEL_VERSION}:{settings.audio_feature_inference_model}",
            audio_features_filled=status == "ok",
            duration_ms=parsed.get("duration_ms") or request.duration_ms,
            musical_key=parsed["musical_key"],
            mode=parsed["mode"],
            acousticness=parsed["acousticness"],
            danceability=parsed["danceability"],
            energy=parsed["energy"],
            instrumentalness=parsed["instrumentalness"],
            liveness=parsed["liveness"],
            loudness=parsed["loudness"],
            speechiness=parsed["speechiness"],
            tempo=parsed["tempo"],
            valence=parsed["valence"],
            rationale=parsed.get("rationale", ""),
            evidence=[
                AudioFeatureInferenceEvidence.model_validate(item)
                for item in evidence_items
            ],
            warnings=[] if status == "ok" else [self._low_confidence_warning(confidence, evidence_items)],
        )

    def _call_llm(self, settings, request: AudioFeatureInferenceRequest) -> dict:
        payload = {
            "model": settings.audio_feature_inference_model,
            "tools": [{"type": "web_search"}],
            "reasoning": {"effort": "low"},
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "audio_feature_inference",
                    "strict": True,
                    "schema": self._response_schema(),
                }
            },
            "input": [
                {
                    "role": "system",
                    "content": (
                        "Infer provider-neutral audio feature estimates for a music track only from web search "
                        "evidence. Use evidence such as reviews, official descriptions, genre tags, BPM/key pages, "
                        "and music database pages. Return compact JSON matching the schema exactly. Do not claim "
                        "the values are measured. If evidence is weak, lower confidence. Include at least one "
                        "specific evidence item with a URL or source description."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "track_scope": request.track_scope,
                            "track_id": request.track_id,
                            "title": request.title,
                            "artist_name": request.artist_name,
                            "duration_ms": request.duration_ms,
                            "source_url": request.source_url,
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
        }

        endpoint = settings.llm_base_url.rstrip("/") + "/responses"
        http_request = urllib_request.Request(
            endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {settings.llm_api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            method="POST",
        )

        try:
            with urllib_request.urlopen(http_request, timeout=45) as response:
                body = json.loads(response.read().decode("utf-8"))
        except HTTPError as exception:
            raise HTTPException(
                status_code=502,
                detail=f"Audio feature inference LLM request failed with status {exception.code}.",
            ) from exception
        except (URLError, TimeoutError) as exception:
            raise HTTPException(
                status_code=502,
                detail="Audio feature inference LLM endpoint is unreachable.",
            ) from exception

        content = self._extract_output_text(body)
        if not content:
            raise HTTPException(status_code=502, detail="Audio feature inference LLM returned an empty message.")

        try:
            parsed = json.loads(content)
        except json.JSONDecodeError as exception:
            raise HTTPException(status_code=502, detail="Audio feature inference LLM returned invalid JSON.") from exception

        if not isinstance(parsed, dict):
            raise HTTPException(status_code=502, detail="Audio feature inference LLM returned an invalid JSON shape.")
        return parsed

    def _extract_output_text(self, body: dict) -> str | None:
        output_text = body.get("output_text")
        if isinstance(output_text, str) and output_text.strip():
            return output_text
        for item in body.get("output", []):
            if not isinstance(item, dict):
                continue
            for content in item.get("content", []):
                if not isinstance(content, dict):
                    continue
                text = content.get("text")
                if isinstance(text, str) and text.strip():
                    return text
        return None

    def _response_schema(self) -> dict:
        numeric_01 = {"type": "number", "minimum": 0, "maximum": 1}
        return {
            "type": "object",
            "additionalProperties": False,
            "required": [
                "confidence",
                "duration_ms",
                "musical_key",
                "mode",
                "acousticness",
                "danceability",
                "energy",
                "instrumentalness",
                "liveness",
                "loudness",
                "speechiness",
                "tempo",
                "valence",
                "rationale",
                "evidence",
            ],
            "properties": {
                "confidence": numeric_01,
                "duration_ms": {"type": ["integer", "null"], "minimum": 1, "maximum": 7200000},
                "musical_key": {"type": "integer", "minimum": 0, "maximum": 11},
                "mode": {"type": "integer", "minimum": 0, "maximum": 1},
                "acousticness": numeric_01,
                "danceability": numeric_01,
                "energy": numeric_01,
                "instrumentalness": numeric_01,
                "liveness": numeric_01,
                "loudness": {"type": "number", "minimum": -60, "maximum": 5},
                "speechiness": numeric_01,
                "tempo": {"type": "number", "minimum": 30, "maximum": 240},
                "valence": numeric_01,
                "rationale": {"type": "string", "maxLength": 1000},
                "evidence": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 8,
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["source_name", "source_url", "evidence_kind", "evidence_text", "confidence"],
                        "properties": {
                            "source_name": {"type": "string", "minLength": 1, "maxLength": 80},
                            "source_url": {"type": ["string", "null"], "maxLength": 500},
                            "evidence_kind": {"type": "string", "minLength": 1, "maxLength": 80},
                            "evidence_text": {"type": "string", "minLength": 1, "maxLength": 1000},
                            "confidence": numeric_01,
                        },
                    },
                },
            },
        }

    def _low_confidence_warning(self, confidence: float, evidence_items: list) -> str:
        if not evidence_items:
            return "LLM/search audio feature inference returned no evidence."
        return f"LLM/search audio feature confidence {confidence:.2f} is below the configured gate."
