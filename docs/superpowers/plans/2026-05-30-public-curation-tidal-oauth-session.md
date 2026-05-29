# Public Curation TIDAL OAuth Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 공유 페이지 방문자가 My Forever Music 로그인 없이 본인 TIDAL 계정으로 public playback session을 만들 수 있게 한다.

**Architecture:** 기존 `/platforms/oauth/callback` frontend route와 TIDAL PKCE code flow를 재사용하되, backend 저장 대상은 `platform_credential`/`platform_connection`이 아니라 `public_playback_session`으로 분리한다. state는 `platform_authorization_session`에 저장하되 `user_id`에는 `public-curation:{playlistId}` synthetic owner를 넣어 일반 사용자 연결과 섞이지 않게 한다.

**Tech Stack:** Spring Boot 3.5, Java 21 records, JPA, MockMvc, React + TypeScript + Vite, sessionStorage 기반 OAuth callback 복원.

---

## 파일 구조

- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java`
  - public playback session 저장/조회 application boundary.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthService.java`
  - public share slug 기준 TIDAL OAuth start/complete/session 확인 담당.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthController.java`
  - 공개 API endpoint.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java`
  - token label/scope/toState accessor 추가.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicPlaybackSessionStore.java`
  - JPA implementation.
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthServiceTest.java`
  - 일반 platform credential을 만들지 않는 public OAuth behavior 검증.
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthControllerWebMvcTest.java`
  - endpoint contract 검증.
- Modify: `apps/web/src/services/api.ts`, `apps/web/src/types/api.ts`
  - public TIDAL OAuth start/complete/session API client.
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`
  - CTA 활성화, start response를 sessionStorage에 저장하고 TIDAL login으로 이동.
- Modify: `apps/web/src/pages/platforms/PlatformOAuthCallbackPage.tsx`
  - public curation pending state면 public complete API로 분기.
- Create: `apps/web/scripts/public-curation-tidal-oauth-harness.mjs`
  - frontend wiring regression harness.

---

### Task 1: Public Playback Session Store

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicPlaybackSessionStore.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicPlaybackSessionStoreTest.java`

- [x] **Step 1: Write the failing repository test**

```java
@DataJpaTest
@Import(JpaPublicPlaybackSessionStore.class)
class JpaPublicPlaybackSessionStoreTest {
    @Autowired JpaPublicPlaybackSessionStore store;

    @Test
    void shouldSaveAndFindActivePublicPlaybackSession() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        PublicPlaybackSessionStore.StoredSession saved = store.save(new PublicPlaybackSessionStore.SessionDraft(
            "public-session-1",
            42L,
            "TIDAL Listener",
            "access-token",
            "refresh-token",
            "user.read, collection.read",
            now.plusSeconds(3600),
            now
        ));

        Optional<PublicPlaybackSessionStore.StoredSession> found =
            store.findActiveBySessionId("public-session-1", now.plusSeconds(60));

        assertThat(saved.sessionId()).isEqualTo("public-session-1");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().playlistId()).isEqualTo(42L);
        assertThat(found.orElseThrow().tidalAccountLabel()).isEqualTo("TIDAL Listener");
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.persistence.JpaPublicPlaybackSessionStoreTest`

Expected: FAIL because `PublicPlaybackSessionStore` and `JpaPublicPlaybackSessionStore` do not exist.

- [x] **Step 3: Implement minimal store**

Create `PublicPlaybackSessionStore`:

```java
public interface PublicPlaybackSessionStore {
    StoredSession save(SessionDraft draft);
    Optional<StoredSession> findActiveBySessionId(String sessionId, Instant now);

    record SessionDraft(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String accessToken,
        String refreshToken,
        String scopeSummary,
        Instant expiresAt,
        Instant createdAt
    ) {}

    record StoredSession(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String scopeSummary,
        Instant expiresAt,
        Instant createdAt,
        Instant lastUsedAt
    ) {}
}
```

Add entity accessors for `tidalAccountLabel`, `scopeSummary`, `createdAt`, token fields, and a `toState()` method returning `StoredSession`. Implement `JpaPublicPlaybackSessionStore` with `save(...)` and `findActiveBySessionId(...)` filtering `expiresAt.isAfter(now)`.

- [x] **Step 4: Run test to verify it passes**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.persistence.JpaPublicPlaybackSessionStoreTest`

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicPlaybackSessionStore.java \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicPlaybackSessionStoreTest.java
git commit -m "feat: add public playback session store"
```

---

### Task 2: Backend Public TIDAL OAuth API

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthService.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthController.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthServiceTest.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthControllerWebMvcTest.java`

- [x] **Step 1: Write failing service test**

```java
@Test
void shouldCompletePublicTidalOAuthWithoutCreatingUserPlatformCredential() {
    var playlist = publishedPlaylist(42L, "rainy-night");
    FakePublicCurationPlaylistStore playlistStore = new FakePublicCurationPlaylistStore(playlist);
    InMemoryPlatformAuthorizationSessionStore authorizationSessionStore = new InMemoryPlatformAuthorizationSessionStore();
    InMemoryPlatformCredentialStore credentialStore = new InMemoryPlatformCredentialStore();
    FakePublicPlaybackSessionStore publicSessionStore = new FakePublicPlaybackSessionStore();
    PlatformOAuthProperties properties = new PlatformOAuthProperties();
    properties.getTidal().setEnabled(true);
    properties.getTidal().setClientId("tidal-client-id");
    properties.getTidal().setRedirectUri("https://approid.team/platforms/oauth/callback");

    PublicCurationTidalOAuthService service = new PublicCurationTidalOAuthService(
        playlistStore,
        authorizationSessionStore,
        publicSessionStore,
        new PlatformAuthorizationCodeExchangeRegistry(List.of(fakeTidalExchangeClient())),
        new PlatformAccountProfileResolverRegistry(List.of()),
        properties
    );

    var start = service.start("rainy-night");
    var complete = service.complete("rainy-night", start.authorization().state(), "tidal-code");

    assertThat(start.authorization().externalAuthorizationUrl())
        .contains("https://login.tidal.com/authorize")
        .contains("state=")
        .contains("scope=user.read%20collection.read%20playlists.read");
    assertThat(complete.session().sessionId()).startsWith("public-curation-session-");
    assertThat(publicSessionStore.saved).isNotNull();
    assertThat(credentialStore.findByUserIdAndPlatformId("public-curation:42", "tidal")).isEmpty();
}
```

- [x] **Step 2: Run service test to verify it fails**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.application.PublicCurationTidalOAuthServiceTest`

Expected: FAIL because the service does not exist.

- [x] **Step 3: Implement service**

Implement records:

```java
public record PublicTidalOAuthStartResponse(String service, String status, Instant generatedAt, Authorization authorization) {}
public record Authorization(String state, String platformId, List<String> requestedScopes, Instant expiresAt, String externalAuthorizationUrl, String redirectUri) {}
public record PublicTidalOAuthCompleteResponse(String service, String status, Instant completedAt, Session session, String returnPath) {}
public record Session(String sessionId, Long playlistId, String tidalAccountLabel, String scopeSummary, Instant expiresAt) {}
```

Rules:
- `start(slug)` must require an existing published playlist.
- state prefix: `public-curation-oauth-`.
- synthetic owner: `public-curation:{playlistId}`.
- scopes: `user.read`, `collection.read`, `playlists.read`.
- authorization URL must use PKCE S256 and the existing TIDAL redirect URI.
- `complete(slug, state, authorizationCode)` must exchange the code through the existing registry, save `public_playback_session`, mark the OAuth state completed, and return `/share/playlists/{slug}?playback=ready`.
- It must not call `PlatformCredentialStore` or `PlatformConnectionStore`.

- [x] **Step 4: Write failing controller test**

```java
@WebMvcTest(PublicCurationTidalOAuthController.class)
class PublicCurationTidalOAuthControllerWebMvcTest {
    @Autowired MockMvc mockMvc;
    @MockBean PublicCurationTidalOAuthService service;

    @Test
    void shouldStartPublicTidalOAuth() throws Exception {
        when(service.start("rainy-night")).thenReturn(sampleStart());

        mockMvc.perform(post("/api/v1/public-curations/share/rainy-night/tidal/oauth/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-tidal-oauth"))
            .andExpect(jsonPath("$.authorization.external_authorization_url").value("https://login.tidal.com/authorize?state=public-curation-oauth-test"));
    }

    @Test
    void shouldCompletePublicTidalOAuth() throws Exception {
        when(service.complete("rainy-night", "public-curation-oauth-test", "tidal-code")).thenReturn(sampleComplete());

        mockMvc.perform(post("/api/v1/public-curations/share/rainy-night/tidal/oauth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"state":"public-curation-oauth-test","authorization_code":"tidal-code"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.session_id").value("public-curation-session-test"))
            .andExpect(jsonPath("$.return_path").value("/share/playlists/rainy-night?playback=ready"));
    }
}
```

- [x] **Step 5: Implement controller**

Add:
- `POST /api/v1/public-curations/share/{slug}/tidal/oauth/start`
- `POST /api/v1/public-curations/share/{slug}/tidal/oauth/complete`
- `GET /api/v1/public-curations/share/{slug}/playback/session?session_id=...`

The GET endpoint returns `status=ready` only when `PublicPlaybackSessionStore.findActiveBySessionId(...)` finds a session for the same playlist.

- [x] **Step 6: Run backend tests**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.application.PublicCurationTidalOAuthServiceTest --tests io.myforevermusic.api.modules.publiccuration.presentation.PublicCurationTidalOAuthControllerWebMvcTest`

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add docs/superpowers/plans/2026-05-30-public-curation-tidal-oauth-session.md \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthService.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationTidalOAuthServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationTidalOAuthControllerWebMvcTest.java
git commit -m "feat: add public curation tidal oauth api"
```

---

### Task 3: Frontend Public OAuth Flow

**Files:**
- Modify: `apps/web/src/services/api.ts`
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`
- Modify: `apps/web/src/pages/platforms/PlatformOAuthCallbackPage.tsx`
- Create: `apps/web/scripts/public-curation-tidal-oauth-harness.mjs`

- [ ] **Step 1: Write failing harness**

```js
assertIncludes('src/services/api.ts', 'startPublicCurationTidalOAuth')
assertIncludes('src/services/api.ts', 'completePublicCurationTidalOAuth')
assertIncludes('src/pages/PublicCurationSharePage.tsx', 'PUBLIC_CURATION_OAUTH_STORAGE_KEY')
assertIncludes('src/pages/PublicCurationSharePage.tsx', 'startPublicCurationTidalOAuth(slug')
assertIncludes('src/pages/platforms/PlatformOAuthCallbackPage.tsx', 'completePublicCurationTidalOAuth')
assertIncludes('src/pages/platforms/PlatformOAuthCallbackPage.tsx', 'public-curation')
assertNotIncludes('src/pages/PublicCurationSharePage.tsx', 'disabled\\n')
```

- [ ] **Step 2: Run harness to verify it fails**

Run: `cd apps/web && node scripts/public-curation-tidal-oauth-harness.mjs`

Expected: FAIL because the client functions and callback branch do not exist.

- [ ] **Step 3: Add API types and client functions**

Add types:

```ts
export type PublicCurationTidalOAuthStartResponse = {
    service: string
    status: string
    generated_at: string
    authorization: {
        state: string
        platform_id: 'tidal'
        requested_scopes: string[]
        expires_at: string
        external_authorization_url: string
        redirect_uri: string
    }
}

export type PublicCurationTidalOAuthCompleteResponse = {
    service: string
    status: string
    completed_at: string
    session: {
        session_id: string
        playlist_id: number
        tidal_account_label: string | null
        scope_summary: string | null
        expires_at: string
    }
    return_path: string
}
```

Add API functions:

```ts
export const startPublicCurationTidalOAuth = (slug: string, signal?: AbortSignal) =>
    request<PublicCurationTidalOAuthStartResponse>(`/api/v1/public-curations/share/${encodeURIComponent(slug)}/tidal/oauth/start`, {
        method: 'POST',
        signal,
    })

export const completePublicCurationTidalOAuth = (slug: string, body: { state: string; authorization_code: string }) =>
    request<PublicCurationTidalOAuthCompleteResponse>(`/api/v1/public-curations/share/${encodeURIComponent(slug)}/tidal/oauth/complete`, {
        method: 'POST',
        body: JSON.stringify(body),
    })
```

- [ ] **Step 4: Wire share page CTA**

On button click:
- call `startPublicCurationTidalOAuth(slug)`
- store `{ flow: 'public-curation', slug, authorization }` in `sessionStorage` under `my-forever-music.public-curation-oauth.{state}`
- redirect to `authorization.external_authorization_url`
- show Korean error if start fails.

- [ ] **Step 5: Wire callback branch**

At the top of `PlatformOAuthCallbackPage`, load public pending state first. If present:
- provider `error` renders Korean failure text and a link back to `/share/playlists/{slug}`
- missing `code` renders Korean failure text
- valid code calls `completePublicCurationTidalOAuth(slug, { state, authorization_code: code })`
- remove sessionStorage key
- navigate to `response.return_path`

Normal platform OAuth behavior must remain unchanged when no public pending state exists.

- [ ] **Step 6: Run frontend harness and build**

Run:
- `cd apps/web && node scripts/public-curation-tidal-oauth-harness.mjs`
- `cd apps/web && npm run build`

Expected: both PASS/build exit 0.

- [ ] **Step 7: Commit**

```bash
git add apps/web/scripts/public-curation-tidal-oauth-harness.mjs \
  apps/web/src/pages/PublicCurationSharePage.tsx \
  apps/web/src/pages/platforms/PlatformOAuthCallbackPage.tsx
git add -p apps/web/src/services/api.ts
git add -p apps/web/src/types/api.ts
git commit -m "feat: add public curation tidal oauth frontend"
```

---

## Self Review

- Spec coverage: 이 plan은 공개 TIDAL OAuth session 흐름, 기존 callback 재사용, 일반 사용자 platform connection 비저장, 공개 페이지 CTA 활성화를 다룬다.
- Deliberate gap: 실제 TIDAL stream playback handoff는 다음 plan에서 처리한다. 이 plan은 session 생성과 callback 복원까지만 안정화한다.
- Placeholder scan: 금지된 미완성 표기 없음.
- Type consistency: backend response의 `external_authorization_url`, `authorization_code`, `return_path`, frontend snake_case type 이름이 서로 일치한다.
