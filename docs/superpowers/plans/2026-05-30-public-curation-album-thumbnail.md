# Public Curation Album Thumbnail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 큐레이션 생성 시 album image URL을 저장하고 track card와 큰 player에 동일한 thumbnail fallback 규칙으로 표시한다.

**Architecture:** PMS/EMS 후보 SQL부터 FastAPI scorer, Spring 저장 DTO까지 `image_url`을 metadata로 전달한다. React에는 원격 이미지 실패를 자체 처리하는 `PublicTrackThumbnail` 컴포넌트를 추가하고 card와 player에서 함께 사용한다.

**Tech Stack:** Spring Boot 3.5, Java 21, FastAPI, Pydantic, React, TypeScript, Vite

---

### Task 1: Candidate pool에서 album image 조회

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStoreTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationCandidatePoolStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStore.java`

- [ ] SQL과 mapper가 `album_image_url as image_url`을 포함해야 한다는 failing test를 작성한다.
- [ ] focused Gradle test로 RED를 확인한다.
- [ ] PMS와 EMS SELECT, `CandidateTrack.imageUrl` mapper를 추가한다.
- [ ] focused Gradle test로 GREEN을 확인한다.

### Task 2: Spring과 FastAPI scorer가 image URL을 보존

**Files:**
- Modify: `services/ai/tests/test_public_curation.py`
- Modify: `services/ai/app/schemas/public_curation.py`
- Modify: `services/ai/app/services/public_curation_service.py`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminControllerWebMvcTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminController.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java`

- [ ] AI selected track, Spring AI request/response, generation draft에 image URL이 남아야 한다는 failing assertion을 추가한다.
- [ ] FastAPI scorer 직접 호출과 focused Gradle test로 RED를 확인한다.
- [ ] request/response DTO와 변환 로직에 `imageUrl` / `image_url`을 추가한다.
- [ ] `PublicCurationGenerationService.toTrackDraft()`가 AI 응답의 `imageUrl`을 저장하도록 연결한다.
- [ ] FastAPI scorer 직접 호출과 focused Gradle test로 GREEN을 확인한다.

### Task 3: 공유 UI thumbnail 공용 컴포넌트

**Files:**
- Create: `apps/web/src/components/public-curation/PublicTrackThumbnail.tsx`
- Modify: `apps/web/scripts/public-curation-share-page-harness.mjs`
- Modify: `apps/web/src/components/public-curation/PublicMixSpectrumPlayer.tsx`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`

- [ ] harness에 공용 thumbnail, `onError`, 첫 글자 대문자 fallback, player header 사용 검사를 추가한다.
- [ ] harness RED를 확인한다.
- [ ] `PublicTrackThumbnail`을 구현한다. `imageUrl`이 없거나 로딩 실패하면 `title.trim().charAt(0).toLocaleUpperCase() || fallbackLabel`을 표시한다.
- [ ] track card 좌측과 player 제목 좌측에서 공용 컴포넌트를 사용한다.
- [ ] harness와 `npm run build` GREEN을 확인한다.

### Task 4: 관련 회귀 검증

- [ ] FastAPI scorer 직접 호출로 `image_url` 보존을 확인한다.
- [ ] Spring 공개 큐레이션 focused test를 실행한다.
- [ ] frontend public curation harness 3종과 production build를 실행한다.
- [ ] `git diff --check`를 실행한다.
