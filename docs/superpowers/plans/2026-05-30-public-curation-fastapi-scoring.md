# Public Curation FastAPI Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic FastAPI endpoint that scores Public Curation candidate tracks and returns a draft title, copy, selected tracks, reasons, and score summary.

**Architecture:** Add a focused `public_curation` schema/service/router set in `services/ai`. The first scoring model is deterministic and local: it filters for TIDAL-ready tracks, scores prompt/audio/TIDAL/shareability signals, selects the requested count, and returns stable output that Spring Boot can persist.

**Tech Stack:** FastAPI, Pydantic, pytest, FastAPI TestClient.

---

### Task 1: Public Curation Scoring Endpoint

**Files:**
- Create: `services/ai/tests/test_public_curation.py`
- Create: `services/ai/app/schemas/public_curation.py`
- Create: `services/ai/app/services/public_curation_service.py`
- Create: `services/ai/app/routers/public_curations.py`
- Modify: `services/ai/app/main.py`

- [ ] **Step 1: Write the failing API test**

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/ai && python -m pytest tests/test_public_curation.py -q`

Expected: FAIL because `/v1/public-curations/score` is not registered.

- [ ] **Step 3: Add schema, service, router, and main registration**

Create request/response Pydantic models, deterministic scoring service, router prefix `/v1/public-curations`, and include the router in `app/main.py`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/ai && python -m pytest tests/test_public_curation.py -q`

Expected: PASS.

### Task 2: Commit FastAPI Scoring Slice

**Files:**
- Stage only `docs/superpowers/plans/2026-05-30-public-curation-fastapi-scoring.md` and Public Curation files under `services/ai`.

- [ ] **Step 1: Run focused tests**

Run: `cd services/ai && python -m pytest tests/test_public_curation.py -q`

Expected: PASS.

- [ ] **Step 2: Run diff check**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-05-30-public-curation-fastapi-scoring.md \
  services/ai/app/main.py \
  services/ai/app/routers/public_curations.py \
  services/ai/app/schemas/public_curation.py \
  services/ai/app/services/public_curation_service.py \
  services/ai/tests/test_public_curation.py
git commit -m "feat: add public curation scoring endpoint"
```
