from __future__ import annotations

from datetime import datetime, timezone
import re

from app.schemas.public_curation import (
    PublicCurationCandidateTrack,
    PublicCurationScoreRequest,
    PublicCurationScoreResponse,
    PublicCurationSelectedTrack,
)

MODEL_VERSION = "public-curation-deterministic-v1"


class PublicCurationService:
    def score(self, request: PublicCurationScoreRequest) -> PublicCurationScoreResponse:
        scored_tracks = [
            (candidate, self._score_candidate(candidate, request))
            for candidate in request.candidate_tracks
        ]
        tidal_ready_tracks = [
            (candidate, breakdown)
            for candidate, breakdown in scored_tracks
            if breakdown["tidal_readiness"] == 1.0
        ]
        ranked_tracks = sorted(
            tidal_ready_tracks,
            key=lambda item: (
                item[1]["score"],
                item[0].freshness or 0.0,
                item[0].source_id,
            ),
            reverse=True,
        )
        selected_tracks = [
            self._build_selected_track(index + 1, candidate, breakdown, request)
            for index, (candidate, breakdown) in enumerate(
                ranked_tracks[: request.target_track_count]
            )
        ]
        warnings: list[str] = []
        if len(selected_tracks) < request.target_track_count:
            warnings.append(
                "Fewer TIDAL-ready tracks were available than the requested target count."
            )

        return PublicCurationScoreResponse(
            generated_at=datetime.now(timezone.utc),
            service="public-curation",
            status="ok",
            model_version=MODEL_VERSION,
            title=self._generate_title(request.prompt),
            subtitle="모델이 고른 외부 공유용 플레이리스트",
            description=self._generate_description(request.prompt, selected_tracks),
            tracks=selected_tracks,
            score_summary={
                "candidate_count": len(request.candidate_tracks),
                "tidal_ready_count": len(tidal_ready_tracks),
                "selected_count": len(selected_tracks),
                "average_score": self._average_score(selected_tracks),
            },
            warnings=warnings,
        )

    def _score_candidate(
        self,
        candidate: PublicCurationCandidateTrack,
        request: PublicCurationScoreRequest,
    ) -> dict[str, float]:
        tidal_readiness = 1.0 if candidate.tidal_track_id and candidate.tidal_uri else 0.0
        theme_fit = self._theme_fit(candidate, request)
        audio_fit = self._audio_fit(candidate, request)
        shareability = self._shareability(candidate)
        score = (
            (theme_fit * 0.35)
            + (audio_fit * 0.25)
            + (tidal_readiness * 0.20)
            + (shareability * 0.20)
        )
        return {
            "theme_fit": round(theme_fit, 4),
            "tidal_readiness": tidal_readiness,
            "audio_fit": round(audio_fit, 4),
            "shareability": round(shareability, 4),
            "score": round(score, 4),
        }

    def _theme_fit(
        self,
        candidate: PublicCurationCandidateTrack,
        request: PublicCurationScoreRequest,
    ) -> float:
        requested_terms = [
            *request.filters.mood_tags,
            *request.filters.genre_tags,
        ]
        candidate_terms = [
            *candidate.genres,
            *candidate.tags,
            candidate.source_platform or "",
        ]
        if not requested_terms:
            return 0.5

        normalized_requested = {self._normalize_token(term) for term in requested_terms}
        normalized_candidate = {self._normalize_token(term) for term in candidate_terms}
        overlap = normalized_requested.intersection(normalized_candidate)
        return min(1.0, len(overlap) / max(1, len(normalized_requested)))

    def _audio_fit(
        self,
        candidate: PublicCurationCandidateTrack,
        request: PublicCurationScoreRequest,
    ) -> float:
        ranges = request.filters.audio_feature_ranges
        if not ranges:
            return 0.5

        scores: list[float] = []
        for feature_name, target_range in ranges.items():
            value = candidate.audio_features.get(feature_name)
            if value is None:
                scores.append(0.0)
                continue
            lower = target_range.min
            upper = target_range.max
            if lower is not None and value < lower:
                scores.append(max(0.0, 1.0 - (lower - value)))
                continue
            if upper is not None and value > upper:
                scores.append(max(0.0, 1.0 - (value - upper)))
                continue
            scores.append(1.0)
        return sum(scores) / len(scores)

    def _shareability(self, candidate: PublicCurationCandidateTrack) -> float:
        popularity = candidate.popularity if candidate.popularity is not None else 0.5
        freshness = candidate.freshness if candidate.freshness is not None else 0.5
        metadata_bonus = 0.1 if candidate.isrc else 0.0
        return min(1.0, ((popularity + freshness) / 2) + metadata_bonus)

    def _build_selected_track(
        self,
        order: int,
        candidate: PublicCurationCandidateTrack,
        breakdown: dict[str, float],
        request: PublicCurationScoreRequest,
    ) -> PublicCurationSelectedTrack:
        return PublicCurationSelectedTrack(
            order=order,
            source_scope=candidate.source_scope,
            source_track_id=candidate.source_id,
            title=candidate.title,
            artist_name=candidate.artist_name,
            album_title=candidate.album_title,
            duration_ms=candidate.duration_ms,
            isrc=candidate.isrc,
            tidal_track_id=candidate.tidal_track_id or "",
            tidal_uri=candidate.tidal_uri or "",
            tidal_external_url=candidate.tidal_external_url,
            score=breakdown["score"],
            score_breakdown={
                "theme_fit": breakdown["theme_fit"],
                "tidal_readiness": breakdown["tidal_readiness"],
                "audio_fit": breakdown["audio_fit"],
                "shareability": breakdown["shareability"],
            },
            reason=self._build_reason(candidate, breakdown, request),
        )

    def _build_reason(
        self,
        candidate: PublicCurationCandidateTrack,
        breakdown: dict[str, float],
        request: PublicCurationScoreRequest,
    ) -> str:
        if breakdown["theme_fit"] >= 0.75:
            return f"{candidate.artist_name}의 {candidate.title}은 요청한 테마와 태그가 강하게 맞는 TIDAL-ready 후보입니다."
        if breakdown["audio_fit"] >= 0.85:
            return f"{candidate.title}은 요청한 audio feature 범위와 잘 맞아 흐름을 안정적으로 이어줍니다."
        return f"{candidate.title}은 {request.prompt} 흐름에 넣기 좋은 공개 공유 후보입니다."

    def _generate_title(self, prompt: str) -> str:
        normalized_prompt = prompt.strip()
        if "에 듣기" in normalized_prompt:
            normalized_prompt = normalized_prompt.split("에 듣기", maxsplit=1)[0].strip()
        if not normalized_prompt:
            normalized_prompt = "오늘의"
        return f"{normalized_prompt}의 Public Curation"

    def _generate_description(
        self,
        prompt: str,
        selected_tracks: list[PublicCurationSelectedTrack],
    ) -> str:
        return (
            f"{prompt} 테마로 TIDAL-ready 후보 {len(selected_tracks)}곡을 선별했습니다. "
            "외부 공유 페이지에서 바로 재생할 수 있도록 구성합니다."
        )

    def _average_score(self, tracks: list[PublicCurationSelectedTrack]) -> float:
        if not tracks:
            return 0.0
        return round(sum(track.score for track in tracks) / len(tracks), 4)

    def _normalize_token(self, value: str) -> str:
        return re.sub(r"[^a-z0-9가-힣]+", "-", value.strip().lower()).strip("-")
