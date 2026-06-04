from __future__ import annotations

import json
import re
from urllib import request as urllib_request
from urllib.error import HTTPError, URLError

from app.config import get_settings
from app.schemas.public_curation import (
    PublicCurationScoreRequest,
    PublicCurationSemanticProfile,
    PublicCurationSemanticProfileResult,
)


class PublicCurationSemanticProfileService:
    def resolve(
        self,
        request: PublicCurationScoreRequest,
    ) -> PublicCurationSemanticProfileResult:
        settings = get_settings()
        fallback_profile = self._fallback_profile(request)
        if not settings.public_curation_semantic_model or not settings.llm_api_key:
            return PublicCurationSemanticProfileResult(
                status="semantic_fallback",
                profile=fallback_profile,
                warnings=[
                    "Public curation semantic model is not configured; deterministic prompt signals were used."
                ],
            )

        try:
            profile = PublicCurationSemanticProfile.model_validate(
                self._call_llm(settings, request)
            )
        except Exception as exception:
            return PublicCurationSemanticProfileResult(
                status="semantic_fallback",
                model=settings.public_curation_semantic_model,
                profile=fallback_profile,
                warnings=[
                    "Public curation semantic interpretation failed; deterministic prompt signals were used.",
                    f"semantic_error={type(exception).__name__}",
                ],
            )

        return PublicCurationSemanticProfileResult(
            status="semantic_profile",
            model=settings.public_curation_semantic_model,
            profile=profile,
        )

    def _call_llm(self, settings, request: PublicCurationScoreRequest) -> dict:
        payload = {
            "model": settings.public_curation_semantic_model,
            "temperature": 0.1,
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "public_curation_semantic_profile",
                    "strict": True,
                    "schema": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": [
                            "mood_tags",
                            "genre_tags",
                            "metadata_tags",
                            "target_audio_features",
                            "energy_curve",
                        ],
                        "properties": {
                            "mood_tags": {
                                "type": "array",
                                "items": {"type": "string"},
                            },
                            "genre_tags": {
                                "type": "array",
                                "items": {"type": "string"},
                            },
                            "metadata_tags": {
                                "type": "array",
                                "items": {"type": "string"},
                            },
                            "target_audio_features": {
                                "type": "object",
                                "additionalProperties": {"type": "number"},
                            },
                            "energy_curve": {
                                "type": "array",
                                "items": {"type": "number"},
                            },
                        },
                    },
                },
            },
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Interpret one operator prompt for a public music playlist. "
                        "Do not invent tracks. Return compact JSON matching the schema. "
                        "Keep audio feature targets and energy_curve values between 0 and 1."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "prompt": request.prompt,
                            "filters": request.filters.model_dump(),
                            "target_track_count": request.target_track_count,
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
        }
        endpoint = settings.llm_base_url.rstrip("/") + "/chat/completions"
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
            with urllib_request.urlopen(http_request, timeout=20) as response:
                body = json.loads(response.read().decode("utf-8"))
        except HTTPError as exception:
            raise RuntimeError(
                f"Semantic profile LLM request failed with status {exception.code}."
            ) from exception
        except (URLError, TimeoutError) as exception:
            raise RuntimeError("Semantic profile LLM endpoint is unreachable.") from exception

        content = body.get("choices", [{}])[0].get("message", {}).get("content")
        if not content:
            raise RuntimeError("Semantic profile LLM returned an empty message.")
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            raise RuntimeError("Semantic profile LLM returned an invalid JSON shape.")
        return parsed

    def _fallback_profile(
        self,
        request: PublicCurationScoreRequest,
    ) -> PublicCurationSemanticProfile:
        target_audio_features = {
            name: self._range_midpoint(target_range.min, target_range.max)
            for name, target_range in request.filters.audio_feature_ranges.items()
        }
        energy_target = target_audio_features.get("energy", 0.5)
        prompt_terms = [
            term
            for term in re.split(r"[^a-zA-Z0-9가-힣]+", request.prompt.strip().lower())
            if len(term) >= 2
        ]
        return PublicCurationSemanticProfile(
            mood_tags=self._unique(request.filters.mood_tags),
            genre_tags=self._unique(request.filters.genre_tags),
            metadata_tags=self._unique(prompt_terms),
            target_audio_features=target_audio_features,
            energy_curve=[
                max(0.0, round(energy_target - 0.1, 4)),
                round(energy_target, 4),
                min(1.0, round(energy_target + 0.1, 4)),
                round(energy_target, 4),
            ],
        )

    def _range_midpoint(self, lower: float | None, upper: float | None) -> float:
        if lower is not None and upper is not None:
            return round((lower + upper) / 2, 4)
        if lower is not None:
            return round(min(1.0, lower + 0.15), 4)
        if upper is not None:
            return round(max(0.0, upper - 0.15), 4)
        return 0.5

    def _unique(self, values: list[str]) -> list[str]:
        return list(dict.fromkeys(value.strip().lower() for value in values if value.strip()))
