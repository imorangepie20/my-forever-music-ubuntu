# Public Curation Model v2 구현 계획

> **실행 메모:** `executing-plans`와 `test-driven-development` 흐름으로 각 단계를 검증하며 진행한다.

**목표:** 공개 공유 플레이리스트의 후보별 점수가 실제 트랙 신호에 따라 달라지도록 scoring을 고도화하고, 운영자가 입력값을 이해할 수 있도록 어드민 폼에 설명과 예시를 제공한다.

**구조:** FastAPI는 operator prompt를 semantic profile로 한 번 해석하고 deterministic scoring 및 playlist reranking을 수행한다. Spring Boot는 PMS/EMS 후보의 audio provenance, source playlist, freshness, public playback feedback을 수집해 AI 서비스로 전달한다. 외부 공유 화면은 내부 점수를 숨기고 운영자 화면만 breakdown을 노출한다.

---

## Task 1: FastAPI semantic profile과 fallback 계약

**Files:**
- Modify: `services/ai/app/config.py`
- Modify: `services/ai/app/schemas/public_curation.py`
- Create: `services/ai/app/services/public_curation_semantic_profile_service.py`
- Modify: `services/ai/tests/test_public_curation.py`

1. LLM 성공, 설정 누락, 호출 실패 시나리오를 테스트로 먼저 추가한다.
2. 테스트 실패를 확인한다.
3. `AI_PUBLIC_CURATION_SEMANTIC_MODEL` 설정과 기존 LLM 설정 fallback을 추가한다.
4. prompt를 구조화하고 실패 시 deterministic profile을 반환하는 서비스를 구현한다.
5. 관련 FastAPI 테스트를 통과시킨다.

## Task 2: FastAPI hybrid scoring과 playlist reranker

**Files:**
- Modify: `services/ai/app/schemas/public_curation.py`
- Modify: `services/ai/app/services/public_curation_service.py`
- Modify: `services/ai/tests/test_public_curation.py`

1. 후보별 signal 차이에 따라 score breakdown이 달라지는 테스트를 먼저 추가한다.
2. artist/album 반복 억제와 energy 흐름을 반영하는 reranking 테스트를 추가한다.
3. 테스트 실패를 확인한다.
4. v2 축별 scoring과 greedy reranker를 구현한다.
5. `public-curation-hybrid-v2` 응답 계약과 semantic fallback 상태를 검증한다.

## Task 3: Spring Boot 후보 신호 수집과 AI 계약 확장

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationCandidatePoolStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStoreTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java`

1. audio provenance, EMS playlist signals, freshness, public playback feedback 직렬화 테스트를 추가한다.
2. 테스트 실패를 확인한다.
3. JDBC 조회와 Java mapper를 확장한다.
4. Spring -> FastAPI 요청 record를 확장한다.
5. public curation infrastructure 테스트를 통과시킨다.

## Task 4: 운영자 breakdown과 외부 점수 비노출

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminController.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationShareController.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminControllerWebMvcTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationShareControllerWebMvcTest.java`

1. 운영자 응답에는 breakdown과 semantic summary가 있고 외부 공유 응답에는 내부 score가 없다는 테스트를 추가한다.
2. 테스트 실패를 확인한다.
3. controller response DTO를 분리해 구현한다.
4. WebMvc 테스트를 통과시킨다.

## Task 5: 어드민 설명 UI와 외부 공유 UI 정리

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/PublicCurationAdminPage.tsx`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`
- Modify: `apps/web/scripts/public-curation-admin-page-harness.mjs`
- Modify: `apps/web/scripts/public-curation-share-page-harness.mjs`

1. 각 어드민 입력값의 설명과 예시, breakdown 렌더링, 외부 score 제거를 확인하는 harness를 추가한다.
2. harness 실패를 확인한다.
3. 항상 보이는 짧은 도움말과 예시를 폼 아래에 배치한다.
4. 운영자 카드에 breakdown을 표시하고 외부 공유 트랙 목록에서 내부 점수를 제거한다.
5. harness와 frontend build를 통과시킨다.

## Task 6: 회귀 검증

1. `cd services/ai && pytest tests/test_public_curation.py`
2. `cd services/api && ./gradlew test --tests '*PublicCuration*'`
3. `cd apps/web && node scripts/public-curation-admin-help-harness.mjs`
4. `cd apps/web && node scripts/public-curation-share-page-harness.mjs`
5. `cd apps/web && npm run build`
6. `git diff --check`
