from __future__ import annotations

from fastapi import FastAPI

from app.config import get_settings
from app.routers.audio_feature_inference import router as audio_feature_inference_router
from app.routers.ems_acquisition import router as ems_acquisition_router
from app.routers.ems_overview import router as ems_overview_router
from app.routers.recommendation_datasets import router as recommendation_dataset_router
from app.routers.recommendations import router as recommendation_router
from app.routers.health import router as system_router

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="Recommendation and AI support service for my-forever-music.",
    docs_url="/docs",
    redoc_url=None,
    openapi_url="/openapi.json",
    root_path=settings.root_path,
)

app.include_router(system_router)
app.include_router(recommendation_router)
app.include_router(recommendation_dataset_router)
app.include_router(audio_feature_inference_router)
app.include_router(ems_acquisition_router)
app.include_router(ems_overview_router)
