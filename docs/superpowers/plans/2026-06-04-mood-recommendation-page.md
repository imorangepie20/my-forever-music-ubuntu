# Mood Recommendation Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/mood-recommendation`에 기분과 예시 곡을 입력해 GMS 추천을 받을 수 있는 사용자용 페이지를 추가한다.

**Architecture:** 기존 route/sidebar는 유지하고, 새 `MoodRecommendationPage`가 `previewGmsRecommendations`를 호출한다. 예시 곡은 텍스트 seed로 보존하고 `곡명 - 아티스트`에서 추출한 아티스트를 GMS seed artist로 전달한다.

**Tech Stack:** React, TypeScript, Vite, 기존 Spring Boot GMS preview API.

---

### Task 1: 회귀 체크

**Files:**
- Modify: `apps/web/scripts/sitewide-korean-product-language-harness.mjs`

- [x] **Step 1: Write failing check**

`src/pages/MoodRecommendationPage.tsx`가 존재하고 `지금 내 기분은`, `예시 곡`, `추천받기`, `previewGmsRecommendations`를 포함해야 한다.

- [x] **Step 2: Verify red**

Run: `npm run test:product-language`

Expected: FAIL because `src/pages/MoodRecommendationPage.tsx` does not exist.

### Task 2: 사용자 페이지 구현

**Files:**
- Create: `apps/web/src/pages/MoodRecommendationPage.tsx`

- [x] **Step 1: Create page**

폼 상태, seed parsing, GMS preview 호출, 추천 결과 카드, 전체 재생 버튼을 구현한다. 사용자 페이지 추천 개수는 AI preview 구버전 schema와도 호환되도록 10/15/20곡만 제공한다.

- [x] **Step 2: Verify green**

Run: `npm run test:product-language`

Expected: PASS.

### Task 3: 최종 검증

**Files:**
- Verify only.

- [x] **Step 1: Run frontend checks**

Run: `npm run lint`

Expected: PASS.

Run: `npm run build`

Expected: PASS.
