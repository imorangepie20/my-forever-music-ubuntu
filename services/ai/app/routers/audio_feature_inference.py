from __future__ import annotations

from fastapi import APIRouter

from app.schemas.audio_feature_inference import AudioFeatureInferenceRequest, AudioFeatureInferenceResponse
from app.services.audio_feature_inference_service import AudioFeatureInferenceService

router = APIRouter(prefix="/v1/audio-features", tags=["audio-features"])

service = AudioFeatureInferenceService()


@router.post(
    "/infer",
    response_model=AudioFeatureInferenceResponse,
    summary="Infer missing audio features from web evidence with the configured model",
)
def infer_audio_features(payload: AudioFeatureInferenceRequest) -> AudioFeatureInferenceResponse:
    return service.infer(payload)
