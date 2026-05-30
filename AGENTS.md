# AGENTS.md

이 레포에서 작업을 시작하는 모든 세션은 먼저 아래 문서를 읽고 현재 방향을 맞춘다.

## 첫 진입 순서

1. [README.md](/Users/woosungjo/music-space/my-forever-music/README.md)
2. [docs/PROJECT_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_GUIDE.md)
3. [docs/PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md)
4. [docs/product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md)
5. [docs/architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md)
6. [docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md)
7. [docs/architecture/TECH_STACK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/TECH_STACK.md)
8. [docs/architecture/DESKTOP_APP_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/DESKTOP_APP_STRATEGY.md)
9. [docs/architecture/UBUNTU_SERVER_SETUP_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_SETUP_GUIDE.md)
10. [docs/decisions/ADR-001-backend-stack.md](/Users/woosungjo/music-space/my-forever-music/docs/decisions/ADR-001-backend-stack.md)
11. [services/api/README.md](/Users/woosungjo/music-space/my-forever-music/services/api/README.md)
12. [services/ai/README.md](/Users/woosungjo/music-space/my-forever-music/services/ai/README.md)

## 현재 확정 사항

- 메인 API는 `Node/NestJS`가 아니라 `Spring Boot 3.5.x + Java 21 + Gradle`
- 프론트는 `React + TypeScript + Vite`
- Windows 데스크탑 앱은 웹앱 이후 `Tauri 2`로 확장
- AI 서비스는 `FastAPI`
- DB는 `PostgreSQL`, 마이그레이션은 `Flyway`
- API 계약은 `OpenAPI`
- 제품 핵심 정의는 `docs/PROJECT_KEY_SERVICE.md`를 기준으로 해석한다
- 사용자 반복 사용 목적과 장기 제품 가치는 `docs/product/USER_MUSIC_HOME_VISION.md`를 함께 본다
- 사용자 플로우에는 mock data, sandbox provider, 임시 데이터 경로를 기본값으로 노출하지 않는다
- 현재 1차 구현/시험 서비스 환경은 `MacBook 로컬`이고, Ubuntu는 검증 후 이전 단계다

## 작업 원칙

- 구조나 스택을 바꾸면 관련 문서를 함께 업데이트한다
- 새 세션은 추측으로 진행하지 말고 `docs/PROJECT_GUIDE.md`를 기준으로 현재 상태를 확인한다
- 제품 목표를 해석할 때는 `docs/PROJECT_KEY_SERVICE.md`를 함께 확인하고, 현재 구현 상태와 목표 상태를 구분해서 문서화한다
- 새 기능은 가능한 한 특정 플랫폼에 종속시키지 말고 `PMS user library`와 사용자 소유 취향 모델로 흡수한다
- 아직 실제 provider가 완성되지 않은 기능은 사용자 선택지나 import 가능 상태로 노출하지 않는다
- 오류는 우회, 회피, 임시 처리, 에러 숨김으로 넘기지 않고 실패한 경계와 근본 원인을 확인한 뒤 수정한다
- 사용자에게 재연결이나 재시도를 요구하기 전에 token refresh, provider account id 복구, scope, redirect URI, SSL, 실제 요청/응답을 먼저 검증한다
- 복잡한 통합 오류는 큰 제품 흐름에서 바로 추측 수정하지 말고, 문제가 난 provider/SDK/API 경계만 남긴 최소 재현 하네스나 격리 페이지를 먼저 만든 뒤 원인을 확인한다
- 아키텍처 의사결정이 바뀌면 `docs/decisions`에 ADR을 추가한다
- 작업의 성격에서 자명하게 따라오는 요구는 사용자가 지적하기 전에 먼저 설계·반영한다. 수동적으로 지시를 기다리지 말고 적극적으로 제안·구현한다. 예: 스케줄·반복 접근 기능이면 토큰/자격증명은 하드코딩하지 말고 동적으로 받아와 캐시·갱신하도록 만들고(외부 토큰은 만료/회전을 기본 가정), 봇 차단(예: DataDome 403)이 있으면 브라우저 헤더(User-Agent, sec-ch-ua 등)로 위장해 정상 클라이언트처럼 접근한다


# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
