from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import os


@dataclass(frozen=True)
class Settings:
    app_name: str
    app_version: str
    environment: str
    root_path: str
    model_artifact_dir: str
    llm_api_key: str
    llm_base_url: str
    ems_overview_model: str
    ems_acquisition_model: str
    audio_feature_inference_model: str
    audio_feature_inference_min_confidence: float


@lru_cache
def get_settings() -> Settings:
    return Settings(
        app_name=os.getenv("AI_APP_NAME", "My Forever Music AI"),
        app_version=os.getenv("AI_APP_VERSION", os.getenv("APP_VERSION", "0.1.0")),
        environment=os.getenv("AI_ENV", "local"),
        root_path=os.getenv("AI_ROOT_PATH", ""),
        model_artifact_dir=os.getenv("AI_MODEL_ARTIFACT_DIR", "models"),
        llm_api_key=os.getenv("AI_LLM_API_KEY", ""),
        llm_base_url=os.getenv("AI_LLM_BASE_URL", "https://api.openai.com/v1"),
        ems_overview_model=os.getenv("AI_EMS_OVERVIEW_MODEL", ""),
        ems_acquisition_model=os.getenv(
            "AI_EMS_ACQUISITION_MODEL",
            os.getenv("AI_EMS_OVERVIEW_MODEL", ""),
        ),
        audio_feature_inference_model=os.getenv("AI_AUDIO_FEATURE_INFERENCE_MODEL", "gpt-5-mini"),
        audio_feature_inference_min_confidence=float(os.getenv("AI_AUDIO_FEATURE_INFERENCE_MIN_CONFIDENCE", "0.68")),
    )
