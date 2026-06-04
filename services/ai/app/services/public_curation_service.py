from __future__ import annotations

from datetime import datetime, timezone
import re

from app.schemas.public_curation import (
    PublicCurationCandidateTrack,
    PublicCurationScoreRequest,
    PublicCurationScoreResponse,
    PublicCurationSemanticProfile,
    PublicCurationSelectedTrack,
)
from app.services.public_curation_semantic_profile_service import (
    PublicCurationSemanticProfileService,
)

MODEL_VERSION = "public-curation-hybrid-v2"


class PublicCurationService:
    def __init__(
        self,
        semantic_profile_service: PublicCurationSemanticProfileService | None = None,
    ) -> None:
        self.semantic_profile_service = (
            semantic_profile_service or PublicCurationSemanticProfileService()
        )

    def score(self, request: PublicCurationScoreRequest) -> PublicCurationScoreResponse:
        semantic_result = self.semantic_profile_service.resolve(request)
        scored_tracks = [
            (
                candidate,
                self._score_candidate(candidate, request, semantic_result.profile),
            )
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
        unique_ranked_tracks: list[
            tuple[PublicCurationCandidateTrack, dict[str, float]]
        ] = []
        seen_identity_keys: set[str] = set()
        duplicate_candidate_count = 0
        for candidate, breakdown in ranked_tracks:
            identity_keys = self._identity_keys(candidate)
            if identity_keys.intersection(seen_identity_keys):
                duplicate_candidate_count += 1
                continue
            seen_identity_keys.update(identity_keys)
            unique_ranked_tracks.append((candidate, breakdown))

        reranked_tracks = self._rerank_tracks(
            unique_ranked_tracks,
            semantic_result.profile,
            request.target_track_count,
        )
        selected_tracks = [
            self._build_selected_track(index + 1, candidate, breakdown, request)
            for index, (candidate, breakdown) in enumerate(reranked_tracks)
        ]
        warnings = list(semantic_result.warnings)
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
                "duplicate_candidate_count": duplicate_candidate_count,
                "unique_tidal_ready_count": len(unique_ranked_tracks),
                "selected_count": len(selected_tracks),
                "average_score": self._average_score(selected_tracks),
                "semantic_profile_status": semantic_result.status,
            },
            semantic_profile_status=semantic_result.status,
            semantic_profile_model=semantic_result.model,
            semantic_profile=semantic_result.profile,
            warnings=warnings,
        )

    def _score_candidate(
        self,
        candidate: PublicCurationCandidateTrack,
        request: PublicCurationScoreRequest,
        semantic_profile: PublicCurationSemanticProfile,
    ) -> dict[str, float]:
        tidal_readiness = 1.0 if candidate.tidal_track_id and candidate.tidal_uri else 0.0
        semantic_theme_fit = self._semantic_theme_fit(candidate, semantic_profile)
        audio_fit = self._audio_fit(candidate, request, semantic_profile)
        metadata_quality = self._metadata_quality(candidate)
        source_quality = self._source_quality(candidate)
        freshness = candidate.freshness if candidate.freshness is not None else 0.4
        audience_response = self._audience_response(candidate)
        discovery_value = self._discovery_value(candidate)
        playback_resolution_confidence = self._playback_resolution_confidence(candidate)
        score = (
            (semantic_theme_fit * 0.26)
            + (audio_fit * 0.24)
            + (metadata_quality * 0.10)
            + (source_quality * 0.12)
            + (freshness * 0.10)
            + (audience_response * 0.08)
            + (discovery_value * 0.10)
        ) * playback_resolution_confidence
        return {
            "tidal_readiness": tidal_readiness,
            "semantic_theme_fit": round(semantic_theme_fit, 4),
            "audio_fit": round(audio_fit, 4),
            "metadata_quality": round(metadata_quality, 4),
            "source_quality": round(source_quality, 4),
            "freshness": round(freshness, 4),
            "audience_response": round(audience_response, 4),
            "discovery_value": round(discovery_value, 4),
            "playback_resolution_confidence": round(playback_resolution_confidence, 4),
            "score": round(score, 4),
        }

    def _playback_resolution_confidence(self, candidate: PublicCurationCandidateTrack) -> float:
        if candidate.playback_resolution_status == "resolved_to_tidal":
            return 0.96
        if candidate.playback_resolution_status == "native_tidal":
            return 1.0
        return 0.92 if candidate.tidal_track_id and candidate.tidal_uri else 0.0

    def _semantic_theme_fit(
        self,
        candidate: PublicCurationCandidateTrack,
        semantic_profile: PublicCurationSemanticProfile,
    ) -> float:
        requested_terms = [
            *semantic_profile.mood_tags,
            *semantic_profile.genre_tags,
            *semantic_profile.metadata_tags,
        ]
        candidate_terms = [
            *candidate.genres,
            *candidate.tags,
            *candidate.metadata_tags,
            candidate.title,
            candidate.artist_name,
            candidate.album_title or "",
            candidate.source_platform or "",
        ]
        if not requested_terms:
            return 0.5

        normalized_requested = {self._normalize_token(term) for term in requested_terms}
        normalized_candidate = {self._normalize_token(term) for term in candidate_terms}
        overlap = normalized_requested.intersection(normalized_candidate)
        fuzzy_overlap = {
            requested
            for requested in normalized_requested
            if any(
                requested in candidate_term or candidate_term in requested
                for candidate_term in normalized_candidate
                if candidate_term
            )
        }
        return min(1.0, len(overlap.union(fuzzy_overlap)) / max(1, len(normalized_requested)))

    def _audio_fit(
        self,
        candidate: PublicCurationCandidateTrack,
        request: PublicCurationScoreRequest,
        semantic_profile: PublicCurationSemanticProfile,
    ) -> float:
        ranges = request.filters.audio_feature_ranges
        targets = semantic_profile.target_audio_features
        if not ranges and not targets:
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
        for feature_name, target in targets.items():
            if feature_name in ranges:
                continue
            value = candidate.audio_features.get(feature_name)
            scores.append(0.0 if value is None else max(0.0, 1.0 - abs(value - target)))
        confidence = (
            candidate.audio_feature_confidence
            if candidate.audio_feature_confidence is not None
            else 0.5
        )
        return (sum(scores) / len(scores)) * (0.6 + (confidence * 0.4))

    def _metadata_quality(self, candidate: PublicCurationCandidateTrack) -> float:
        values = [
            candidate.title,
            candidate.artist_name,
            candidate.album_title,
            candidate.image_url,
            candidate.isrc,
            candidate.tidal_track_id,
            candidate.tidal_uri,
        ]
        completeness = sum(bool(value) for value in values) / len(values)
        confidence = candidate.audio_feature_confidence or 0.0
        audio_bonus = 0.08 if candidate.audio_features_filled else confidence * 0.08
        return min(1.0, completeness + audio_bonus)

    def _source_quality(self, candidate: PublicCurationCandidateTrack) -> float:
        if candidate.source_scope == "pms_user_track":
            return 0.82
        signals = candidate.source_playlist_signals
        playlist_bonus = min(0.28, signals.playlist_count * 0.07)
        follower_bonus = min(0.18, (signals.max_followers_count or 0) / 10000)
        evidence_bonus = 0.08 if signals.search_queries or signals.collection_sources else 0.0
        return min(1.0, 0.42 + playlist_bonus + follower_bonus + evidence_bonus)

    def _audience_response(self, candidate: PublicCurationCandidateTrack) -> float:
        response = candidate.audience_response
        if response.play_started_count == 0:
            return 0.5
        completion_ratio = response.play_completed_count / response.play_started_count
        skip_ratio = response.skip_count / response.play_started_count
        return min(1.0, max(0.0, 0.35 + (completion_ratio * 0.75) - (skip_ratio * 0.5)))

    def _discovery_value(self, candidate: PublicCurationCandidateTrack) -> float:
        popularity = candidate.popularity if candidate.popularity is not None else 0.5
        editorial_evidence = min(0.25, candidate.source_playlist_signals.playlist_count * 0.05)
        return min(1.0, 0.45 + ((1.0 - popularity) * 0.3) + editorial_evidence)

    def _rerank_tracks(
        self,
        ranked_tracks: list[tuple[PublicCurationCandidateTrack, dict[str, float]]],
        semantic_profile: PublicCurationSemanticProfile,
        target_track_count: int,
    ) -> list[tuple[PublicCurationCandidateTrack, dict[str, float]]]:
        remaining = list(ranked_tracks)
        selected: list[tuple[PublicCurationCandidateTrack, dict[str, float]]] = []
        while remaining and len(selected) < target_track_count:
            next_index, next_item, adjustment = max(
                (
                    (
                        index,
                        item,
                        self._sequence_adjustment(
                            item[0],
                            selected,
                            semantic_profile,
                        ),
                    )
                    for index, item in enumerate(remaining)
                ),
                key=lambda item: (item[1][1]["score"] + item[2], item[1][0].source_id),
            )
            candidate, breakdown = remaining.pop(next_index)
            selected_breakdown = dict(breakdown)
            selected_breakdown["sequence_adjustment"] = round(adjustment, 4)
            selected_breakdown["score"] = round(
                min(1.0, max(0.0, breakdown["score"] + adjustment)),
                4,
            )
            selected.append((candidate, selected_breakdown))
        return selected

    def _sequence_adjustment(
        self,
        candidate: PublicCurationCandidateTrack,
        selected: list[tuple[PublicCurationCandidateTrack, dict[str, float]]],
        semantic_profile: PublicCurationSemanticProfile,
    ) -> float:
        if not selected:
            return 0.0
        selected_candidates = [item[0] for item in selected]
        adjustment = 0.0
        if any(item.artist_name.casefold() == candidate.artist_name.casefold() for item in selected_candidates):
            adjustment -= 0.4
        if candidate.album_title and any(
            item.album_title and item.album_title.casefold() == candidate.album_title.casefold()
            for item in selected_candidates
        ):
            adjustment -= 0.12
        previous = selected_candidates[-1]
        previous_energy = previous.audio_features.get("energy")
        energy = candidate.audio_features.get("energy")
        if previous_energy is not None and energy is not None:
            adjustment += max(0.0, 0.06 - (abs(previous_energy - energy) * 0.1))
        if semantic_profile.energy_curve and energy is not None:
            target = semantic_profile.energy_curve[len(selected) % len(semantic_profile.energy_curve)]
            adjustment += max(0.0, 0.08 - (abs(target - energy) * 0.12))
        selected_genres = {genre.casefold() for item in selected_candidates for genre in item.genres}
        if any(genre.casefold() not in selected_genres for genre in candidate.genres):
            adjustment += 0.04
        return adjustment

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
            image_url=candidate.image_url,
            duration_ms=candidate.duration_ms,
            isrc=candidate.isrc,
            tidal_track_id=candidate.tidal_track_id or "",
            tidal_uri=candidate.tidal_uri or "",
            tidal_external_url=candidate.tidal_external_url,
            score=breakdown["score"],
            score_breakdown=breakdown,
            reason=self._build_reason(candidate, breakdown, request),
        )

    def _build_reason(
        self,
        candidate: PublicCurationCandidateTrack,
        breakdown: dict[str, float],
        request: PublicCurationScoreRequest,
    ) -> str:
        if breakdown["semantic_theme_fit"] >= 0.75:
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

    def _identity_keys(self, candidate: PublicCurationCandidateTrack) -> set[str]:
        keys: set[str] = set()
        isrc = self._normalize_identity_part(candidate.isrc)
        tidal_track_id = self._normalize_identity_part(candidate.tidal_track_id)
        artist_name = self._normalize_identity_part(candidate.artist_name)
        title = self._normalize_identity_part(candidate.title)
        if isrc:
            keys.add(f"isrc:{isrc}")
        if tidal_track_id:
            keys.add(f"tidal:{tidal_track_id}")
        if artist_name or title:
            keys.add(f"metadata:{artist_name}|{title}")
        return keys

    def _normalize_identity_part(self, value: str | None) -> str:
        return "".join(
            character
            for character in (value or "").strip().casefold()
            if character.isalnum()
        )
