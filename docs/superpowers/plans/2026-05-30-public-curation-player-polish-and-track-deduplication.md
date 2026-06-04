# Public Curation Player Polish and Track Deduplication Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** 공개 공유 playlist의 player를 queue 기반 Play All 경험으로 다듬고, EQ preload 지연을 줄이며, AI scorer와 Spring 저장 경계에서 동일 곡 중복 선곡을 막는다.

**Architecture:** React 공유 페이지는 playlist index와 분리된 playback queue를 유지하고 public analysis audio를 최대 3곡 LRU cache로 preload한다. FastAPI scorer는 점수순 후보에서 identity key가 겹치는 곡을 제거한 뒤 top N을 고른다. Spring API는 scorer 응답을 저장하기 전에 같은 identity key 규칙으로 중복을 거부한다.

**Tech Stack:** React, TypeScript, Vite, Spring Boot 3.5, Java 21, FastAPI, pytest

---

### Task 1: AI scorer에서 동일 곡 제거

**Files:**
- Modify: `services/ai/tests/test_public_curation.py`
- Modify: `services/ai/app/services/public_curation_service.py`

**Steps:**
1. 동일 ISRC, 동일 TIDAL track id, 정규화된 artist + title이 각각 겹치는 후보를 추가하고 한 곡만 선곡되는 pytest를 작성한다.
2. pytest를 실행해 RED를 확인한다.
3. Unicode letter/digit만 남기는 정규화 함수와 identity key 집합을 추가한다.
4. 점수순 후보를 순회하면서 이미 선택한 key와 교집합이 있는 후보를 제외한다.
5. `duplicate_candidate_count`, `unique_tidal_ready_count`를 score summary에 추가한다.
6. pytest를 재실행해 GREEN을 확인한다.

**Verify:**
```bash
cd services/ai
pytest tests/test_public_curation.py -q
```

### Task 2: Spring 저장 경계에 중복 방어선 추가

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java`

**Steps:**
1. AI scorer가 중복 트랙을 반환하면 draft를 저장하지 않고 `502 BAD_GATEWAY`로 실패하는 테스트를 작성한다.
2. 관련 Gradle 테스트를 실행해 RED를 확인한다.
3. scorer track 응답을 `TrackDraft`로 변환하기 전에 AI scorer와 같은 identity key 규칙을 적용한다.
4. key 충돌 시 명시적인 `ResponseStatusException`을 발생시킨다.
5. 관련 Gradle 테스트를 재실행해 GREEN을 확인한다.

**Verify:**
```bash
cd services/api
./gradlew test --tests 'io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationServiceTest'
```

### Task 3: 공개 player 회귀 harness 확장

**Files:**
- Modify: `apps/web/scripts/public-curation-share-page-harness.mjs`

**Steps:**
1. 외부 공유 카드에 반복적인 추천 이유가 보이지 않는지 검사한다.
2. shuffle, repeat, queue position 기반 재생, public EQ preload cache 경로를 검사한다.
3. harness를 실행해 RED를 확인한다.

**Verify:**
```bash
cd apps/web
node scripts/public-curation-share-page-harness.mjs
```

### Task 4: public analysis audio preload cache 구현

**Files:**
- Modify: `apps/web/src/hooks/useTidalAudioAnalyser.ts`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`

**Steps:**
1. public analysis audio fetch와 decode 결과를 Promise 단위로 공유하는 최대 3곡 LRU cache를 추가한다.
2. public analyzer가 playback 후 새 fetch를 시작하는 대신 같은 cache Promise를 사용하도록 연결한다.
3. public session 준비 시 첫 곡, 재생 중에는 다음 queue 곡을 비동기로 preload한다.

### Task 5: 공개 player queue, shuffle, repeat와 카드 단순화 구현

**Files:**
- Modify: `apps/web/src/components/public-curation/PublicMixSpectrumPlayer.tsx`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`

**Steps:**
1. 원본 playlist 순서와 분리된 playback queue를 추가한다.
2. shuffle 활성화 시 현재 곡과 이미 지난 prefix는 유지하고 남은 queue만 섞는다.
3. repeat mode를 `off -> all -> one -> off` 순서로 순환시킨다.
4. 종료 이벤트와 이전/다음 버튼이 queue position을 기준으로 동작하도록 바꾼다.
5. player에 shuffle과 repeat icon button을 추가한다.
6. 외부 공유 track card에서 반복적인 추천 이유 표시만 제거한다.

**Verify:**
```bash
cd apps/web
node scripts/public-curation-share-page-harness.mjs
npm run build
```

### Task 6: 관련 회귀 검증

**Steps:**
1. FastAPI scorer 테스트를 실행한다.
2. Spring 공개 큐레이션 관련 focused test를 실행한다.
3. frontend public curation harness와 build를 실행한다.
4. whitespace 오류를 확인한다.

**Verify:**
```bash
cd services/ai
pytest tests/test_public_curation.py -q

cd ../api
./gradlew test \
  --tests 'io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationServiceTest' \
  --tests 'io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaybackStreamServiceTest' \
  --tests 'io.myforevermusic.api.modules.publiccuration.presentation.PublicCurationPlaybackStreamControllerWebMvcTest'

cd ../../apps/web
node scripts/public-curation-share-page-harness.mjs
node scripts/public-curation-playback-stream-harness.mjs
node scripts/public-curation-tidal-oauth-harness.mjs
npm run build

cd ../..
git diff --check
```
