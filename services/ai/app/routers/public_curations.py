from __future__ import annotations

from fastapi import APIRouter

from app.schemas.public_curation import (
    PublicCurationScoreRequest,
    PublicCurationScoreResponse,
)
from app.services.public_curation_service import PublicCurationService

router = APIRouter(prefix="/v1/public-curations", tags=["public-curations"])

service = PublicCurationService()


@router.post(
    "/score",
    response_model=PublicCurationScoreResponse,
    summary="Score public curation candidate tracks",
)
def score_public_curation(
    request: PublicCurationScoreRequest,
) -> PublicCurationScoreResponse:
    return service.score(request)
