# EMS TIDAL Home 전체 목록 수집 및 페이징 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TIDAL 공개 home source별 playlist metadata 전체를 EMS DB에 저장하고, track 수집은 background batch로 보강하며, EMS 화면은 DB 저장본을 source별 12개씩 독립 페이징한다.

**Architecture:** `TidalWebApiClient`가 공개 home endpoint를 offset 기반으로 끝까지 순회한다. `EmsCollectionService`는 TIDAL home discovery에서는 playlist metadata와 source membership만 저장하고, 새 `EmsTidalHomeTrackBackfillScheduler`가 link 없는 playlist track을 작은 batch로 보강한다. React의 `TidalHomePageSections`는 범용 목록 API 대신 source별 DB paging API를 호출한다.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JPA, PostgreSQL, React, TypeScript, Vite, Node regression harness

---

## 작업 전 주의

현재 workspace에는 다른 기능의 미커밋 변경이 있다. 기존 파일을 커밋할 때는 `git add -p`로 이번 작업 hunk만 stage하고 `git diff --cached --stat`, `git diff --cached --check`를 확인한다. 다른 작업의 변경을 되돌리지 않는다.

## 파일 구조

### backend

- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClient.java`
  - TIDAL 공개 home playlist page 조회와 전체 offset 순회를 담당한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java`
  - playlist-source membership 저장, source별 DB paging, track link 없는 TIDAL home playlist 조회를 담당한다.
- Create: `services/api/src/main/resources/db/migration/V53__create_ems_collected_playlist_source.sql`
  - playlist와 TIDAL home source의 다대다 membership을 저장한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java`
  - metadata-only discovery, DB paging, playlist 단위 track 보강을 담당한다.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillProperties.java`
  - track 보강 scheduler 설정을 보관한다.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillScheduler.java`
  - track link 없는 playlist를 작은 batch로 보강한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionController.java`
  - source별 DB paging endpoint를 노출한다.
- Modify: `services/api/src/main/resources/application.yml`
  - track 보강 scheduler 기본 설정을 추가한다.

### frontend

- Modify: `apps/web/src/types/api.ts`
  - TIDAL home DB paging 응답 타입을 추가한다.
- Modify: `apps/web/src/services/api.ts`
  - source별 DB paging API client를 추가한다.
- Modify: `apps/web/src/components/home/TidalHomePageSections.tsx`
  - source별 독립 page state와 pagination controls를 구현한다.
- Modify: `apps/web/scripts/product-flow-regression-harness.mjs`
  - browser가 provider live 결과나 범용 TIDAL 목록을 직접 잘라 쓰지 않는지 확인한다.
- Modify: `docs/PROJECT_GUIDE.md`
  - TIDAL home 전체 metadata sync와 background track 보강 원칙을 기록한다.

### tests

- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClientTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsCollectionServiceTest.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillSchedulerTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionControllerWebMvcTest.java`

---

### Task 1: TIDAL 공개 home playlist 전체 offset 순회

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClientTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClient.java`

- [ ] **Step 1: 여러 page와 혼합 item을 재현하는 실패 테스트 작성**

`TidalWebApiClientTest`에 다음 테스트를 추가한다. 첫 page에는 50개 원본 item 중 playlist 49개와 `MIX` 1개를 넣고, 두 번째 page에는 playlist 1개를 넣는다. 두 번째 요청의 `offset=50`을 검증한다.

```java
@Test
void shouldFetchAllPublicHomePagePlaylistsUsingRawItemOffset() throws IOException {
    AtomicInteger requestCount = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v2/home/pages/POPULAR_PLAYLISTS/view-all", exchange -> {
        String query = exchange.getRequestURI().getQuery();
        int call = requestCount.getAndIncrement();
        String body;
        if (call == 0 && query.contains("limit=50") && query.contains("offset=0")) {
            body = publicHomeItems(49, true, 0);
        } else if (call == 1 && query.contains("limit=50") && query.contains("offset=50")) {
            body = publicHomeItems(1, false, 49);
        } else {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    });
    server.start();

    try {
        TidalWebApiClient client = tidalPublicClient(server);
        List<TidalWebApiClient.TidalPlaylistSummary> playlists =
            client.getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS");

        assertThat(playlists).hasSize(50);
        assertThat(requestCount).hasValue(2);
    } finally {
        server.stop(0);
    }
}
```

테스트 helper는 같은 test class에 추가한다.

```java
private String publicHomeItems(int playlistCount, boolean includeMix, int startIndex) {
    String playlists = IntStream.range(0, playlistCount)
        .mapToObj(index -> """
            {
              "type": "PLAYLIST",
              "data": {
                "uuid": "pl-%d",
                "title": "Playlist %d",
                "numberOfTracks": 10
              }
            }
            """.formatted(startIndex + index, startIndex + index))
        .collect(Collectors.joining(","));
    String mix = includeMix
        ? (playlists.isBlank() ? "" : ",") + """
            { "type": "MIX", "data": { "id": "mix-1" } }
            """
        : "";
    return """
        { "items": [ %s%s ] }
        """.formatted(playlists, mix);
}

private TidalWebApiClient tidalPublicClient(HttpServer server) {
    PlatformOAuthProperties properties = new PlatformOAuthProperties();
    properties.getTidal().setCountryCode("KR");
    properties.getTidal().setWebBaseUri("http://127.0.0.1:%d".formatted(server.getAddress().getPort()));
    return new TidalWebApiClient(properties, new ObjectMapper(), HttpClient.newHttpClient(), properties.getTidal().getApiBaseUri());
}
```

`AtomicInteger`, `Collectors`, `IntStream` import도 추가한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
cd services/api
./gradlew test --tests '*TidalWebApiClientTest.shouldFetchAllPublicHomePagePlaylistsUsingRawItemOffset'
```

Expected: `getAllPublicHomePagePlaylists`가 없어 compile FAIL.

- [ ] **Step 3: page 조회와 전체 순회 구현**

`TidalWebApiClient`에 다음 상수와 메서드를 추가한다.

```java
private static final int PUBLIC_HOME_PLAYLIST_PAGE_SIZE = 50;
private static final int PUBLIC_HOME_PLAYLIST_MAX_PAGES = 20;

public List<TidalPlaylistSummary> getAllPublicHomePagePlaylists(String sourceId) {
    ArrayList<TidalPlaylistSummary> playlists = new ArrayList<>();
    int offset = 0;
    for (int page = 0; page < PUBLIC_HOME_PLAYLIST_MAX_PAGES; page++) {
        TidalPublicHomePlaylistPage result =
            getPublicHomePagePlaylistPage(sourceId, PUBLIC_HOME_PLAYLIST_PAGE_SIZE, offset);
        playlists.addAll(result.playlists());
        if (result.rawItemCount() < PUBLIC_HOME_PLAYLIST_PAGE_SIZE) {
            return playlists;
        }
        offset += result.rawItemCount();
    }
    throw new IllegalStateException("TIDAL public home-page playlist pagination exceeded the safety limit.");
}

private TidalPublicHomePlaylistPage getPublicHomePagePlaylistPage(String sourceId, int limit, int offset) {
    String cleanSource = sourceId == null ? "" : sourceId.trim();
    if (cleanSource.isBlank()) {
        throw new IllegalArgumentException("TIDAL page source id is required.");
    }
    int clampedLimit = Math.min(Math.max(limit, 1), PUBLIC_HOME_PLAYLIST_PAGE_SIZE);
    int clampedOffset = Math.max(offset, 0);
    String countryCode = platformOAuthProperties.getTidal().getCountryCode();
    try {
        HttpRequest request = publicWebRequest(
            "%s/v2/home/pages/%s/view-all?countryCode=%s&locale=en_US&deviceType=BROWSER&platform=WEB&limit=%d&offset=%d".formatted(
                webBaseUri(),
                URLEncoder.encode(cleanSource, StandardCharsets.UTF_8),
                countryCode,
                clampedLimit,
                clampedOffset
            )
        );
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("TIDAL public home-page request failed (%s): %s"
                .formatted(response.statusCode(), response.body()));
        }
        JsonNode items = objectMapper.readTree(response.body()).path("items");
        ArrayList<TidalPlaylistSummary> playlists = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (!"PLAYLIST".equals(text(item, "type"))) {
                    continue;
                }
                TidalPlaylistSummary playlist = toLegacyPlaylistSummary(item.path("data"));
                if (playlist != null && playlist.playlistId() != null && !playlist.playlistId().isBlank()) {
                    playlists.add(playlist);
                }
            }
        }
        return new TidalPublicHomePlaylistPage(playlists, items.isArray() ? items.size() : 0);
    } catch (IOException exception) {
        throw new IllegalStateException("TIDAL public home-page response could not be parsed.", exception);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("TIDAL public home-page request was interrupted.", exception);
    }
}

private record TidalPublicHomePlaylistPage(List<TidalPlaylistSummary> playlists, int rawItemCount) {}
```

기존 `getPublicHomePagePlaylists(String sourceId, int limit)`는 첫 page 호환 wrapper로 유지한다.

```java
public List<TidalPlaylistSummary> getPublicHomePagePlaylists(String sourceId, int limit) {
    return getPublicHomePagePlaylistPage(sourceId, limit, 0).playlists();
}
```

- [ ] **Step 4: TIDAL client 테스트 실행**

Run:

```bash
cd services/api
./gradlew test --tests '*TidalWebApiClientTest'
```

Expected: PASS.

- [ ] **Step 5: 이번 hunk만 커밋**

```bash
git add -p services/api/src/main/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClient.java
git add -p services/api/src/test/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClientTest.java
git diff --cached --check
git commit -m "feat: fetch all TIDAL home playlists"
```

---

### Task 2: TIDAL home discovery를 metadata-only 저장으로 분리

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsCollectionServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java`
- Create: `services/api/src/main/resources/db/migration/V53__create_ems_collected_playlist_source.sql`

- [ ] **Step 1: metadata-only discovery 실패 테스트 작성**

`EmsCollectionServiceTest`에 다음 의도를 검증하는 테스트를 추가한다.

```java
@Test
void shouldStoreAllTidalHomePlaylistMetadataWithoutFetchingTracksInline() {
    TidalPlaylistSummary playlist = tidalPlaylist("tidal-home-001", "TIDAL Home 001", 50);
    when(tidalWebApiClient.getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS"))
        .thenReturn(List.of(playlist));
    when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", "tidal-home-001"))
        .thenReturn(Optional.empty());
    when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class)))
        .thenAnswer(invocation -> {
            EmsCollectedPlaylistEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 101L);
            return saved;
        });

    EmsCollectionSearchResult result =
        service().collectPublicPlaylistPool(null, "tidal", "POPULAR_PLAYLISTS", 5);

    assertThat(result.collectedPlaylistCount()).isEqualTo(1);
    assertThat(result.collectedTrackCount()).isZero();
    verify(tidalWebApiClient).getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS");
    verify(tidalWebApiClient, never()).getPublicPlaylistTracks(any());
    verify(playlistRepository).deleteTidalHomeSources("tidal", "public_pool", "POPULAR_PLAYLISTS");
    verify(playlistRepository).upsertPlaylistSource(
        any(), eq("tidal"), eq("public_pool"), eq("POPULAR_PLAYLISTS"), any()
    );
}
```

같은 test class에 helper를 추가한다.

```java
private TidalPlaylistSummary tidalPlaylist(String id, String title, int trackCount) {
    return new TidalPlaylistSummary(
        id,
        title,
        "",
        trackCount,
        null,
        null,
        "https://tidal.com/browse/playlist/" + id,
        id
    );
}
```

- [ ] **Step 2: 동일 playlist의 복수 source membership 실패 테스트 작성**

같은 playlist가 두 source에 들어올 때 두 source mapping을 각각 기록하는지 검증한다.

```java
@Test
void shouldKeepMembershipWhenOneTidalPlaylistAppearsInMultipleHomeSources() {
    TidalPlaylistSummary playlist = tidalPlaylist("shared-playlist", "Shared Playlist", 30);
    when(tidalWebApiClient.getAllPublicHomePagePlaylists("THE_HITS")).thenReturn(List.of(playlist));
    when(tidalWebApiClient.getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS")).thenReturn(List.of(playlist));
    EmsCollectedPlaylistEntity stored = collectedPlaylist("shared-playlist", "tidal", "Shared Playlist", 30);
    ReflectionTestUtils.setField(stored, "id", 202L);
    when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", "shared-playlist"))
        .thenReturn(Optional.of(stored));

    EmsCollectionService service = service();
    service.collectPublicPlaylistPool(null, "tidal", "THE_HITS", 5);
    service.collectPublicPlaylistPool(null, "tidal", "POPULAR_PLAYLISTS", 5);

    verify(playlistRepository).upsertPlaylistSource(202L, "tidal", "public_pool", "THE_HITS", any());
    verify(playlistRepository).upsertPlaylistSource(202L, "tidal", "public_pool", "POPULAR_PLAYLISTS", any());
}
```

- [ ] **Step 3: 테스트가 기존 inline track 조회와 membership 부재 때문에 실패하는지 확인**

Run:

```bash
cd services/api
./gradlew test \
  --tests '*EmsCollectionServiceTest.shouldStoreAllTidalHomePlaylistMetadataWithoutFetchingTracksInline' \
  --tests '*EmsCollectionServiceTest.shouldKeepMembershipWhenOneTidalPlaylistAppearsInMultipleHomeSources'
```

Expected: FAIL. 기존 구현은 제한된 목록 API와 inline track 조회를 사용한다.

- [ ] **Step 4: playlist-source membership migration과 repository command 추가**

`V53__create_ems_collected_playlist_source.sql`:

```sql
create table if not exists ems_collected_playlist_source (
    ems_collected_playlist_source_id bigserial primary key,
    ems_collected_playlist_id bigint not null references ems_collected_playlist(ems_collected_playlist_id) on delete cascade,
    source_platform varchar(50) not null,
    collection_source varchar(50) not null,
    source_id varchar(200) not null,
    collected_at timestamptz not null,
    unique (ems_collected_playlist_id, source_platform, collection_source, source_id)
);

create index if not exists idx_ems_collected_playlist_source_browse
    on ems_collected_playlist_source (source_platform, collection_source, source_id, collected_at desc);

insert into ems_collected_playlist_source (
    ems_collected_playlist_id, source_platform, collection_source, source_id, collected_at
)
select ems_collected_playlist_id, source_platform, collection_source, search_query, collected_at
from ems_collected_playlist
where source_platform = 'tidal'
  and collection_source = 'public_pool'
  and search_query is not null
on conflict (ems_collected_playlist_id, source_platform, collection_source, source_id)
do update set collected_at = excluded.collected_at;
```

`EmsCollectedPlaylistRepository`:

```java
@Modifying
@Query(value = """
    delete from ems_collected_playlist_source
    where source_platform = :platformId
      and collection_source = :collectionSource
      and source_id = :sourceId
    """, nativeQuery = true)
int deleteTidalHomeSources(
    @Param("platformId") String platformId,
    @Param("collectionSource") String collectionSource,
    @Param("sourceId") String sourceId
);

@Modifying
@Query(value = """
    insert into ems_collected_playlist_source (
        ems_collected_playlist_id, source_platform, collection_source, source_id, collected_at
    ) values (
        :playlistId, :platformId, :collectionSource, :sourceId, :collectedAt
    )
    on conflict (ems_collected_playlist_id, source_platform, collection_source, source_id)
    do update set collected_at = excluded.collected_at
    """, nativeQuery = true)
void upsertPlaylistSource(
    @Param("playlistId") Long playlistId,
    @Param("platformId") String platformId,
    @Param("collectionSource") String collectionSource,
    @Param("sourceId") String sourceId,
    @Param("collectedAt") Instant collectedAt
);
```

- [ ] **Step 5: TIDAL home branch를 metadata-only로 변경**

`collectFromProvider`의 TIDAL branch를 다음 구조로 좁힌다.

```java
List<TidalPlaylistSummary> playlistResults = homePageSource
    ? tidalWebApiClient.getAllPublicHomePagePlaylists(query)
    : tidalWebApiClient.searchPlaylists(credential, query, limit);

if (homePageSource) {
    playlistRepository.deleteTidalHomeSources("tidal", collectionSource, query);
}

for (TidalPlaylistSummary playlist : playlistResults) {
    EmsCollectedPlaylistEntity playlistEntity =
        upsertPlaylistFromTidal(playlist, collectionSource, query, now);
    collectedPlaylistCount++;

    if (homePageSource) {
        playlistRepository.upsertPlaylistSource(playlistEntity.getId(), "tidal", collectionSource, query, now);
        continue;
    }

    try {
        collectedTrackCount += collectTidalPlaylistTracks(
            playlistEntity, playlist, credential, collectionSource, now, false
        );
    } catch (Exception e) {
        log.warn("EMS collection: could not fetch tracks for TIDAL playlist {}: {}", playlist.playlistId(), e.getMessage());
    }
}
```

중복을 피하도록 playlist 단위 track 저장 helper를 추가한다.

```java
private int collectTidalPlaylistTracks(
    EmsCollectedPlaylistEntity playlistEntity,
    TidalPlaylistSummary playlist,
    PlatformAccountCredential credential,
    String collectionSource,
    Instant now,
    boolean publicPlaylist
) {
    List<TidalPlaylistTrack> playlistTracks = publicPlaylist
        ? tidalWebApiClient.getPublicPlaylistTracks(playlist.playlistId())
        : tidalWebApiClient.getPlaylistTracks(credential, playlist.playlistId());
    Map<String, ReccoBeatsAudioFeaturesSnapshot> features = resolveTidalAudioFeatures(playlistTracks);
    Instant resolvedAt = Instant.now();
    for (int i = 0; i < playlistTracks.size(); i++) {
        TidalPlaylistTrack track = playlistTracks.get(i);
        EmsCollectedTrackEntity trackEntity = upsertTrackFromTidal(
            track,
            collectionSource,
            now,
            resolveTidalTrackAudioFeatures(track, features.get(track.tidalTrackId()), resolvedAt)
        );
        linkPlaylistTrack(playlistEntity, trackEntity, i);
    }
    return playlistTracks.size();
}
```

- [ ] **Step 6: EMS collection service 테스트 실행**

Run:

```bash
cd services/api
./gradlew test --tests '*EmsCollectionServiceTest'
```

Expected: PASS.

- [ ] **Step 7: 이번 hunk만 커밋**

```bash
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java
git add -p services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsCollectionServiceTest.java
git add services/api/src/main/resources/db/migration/V53__create_ems_collected_playlist_source.sql
git diff --cached --check
git commit -m "feat: separate TIDAL home metadata sync"
```

---

### Task 3: TIDAL home track background batch

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillProperties.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillScheduler.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillSchedulerTest.java`
- Modify: `services/api/src/main/resources/application.yml`

- [ ] **Step 1: scheduler 성공 및 부분 실패 테스트 작성**

새 `EmsTidalHomeTrackBackfillSchedulerTest`에 다음 두 동작을 검증한다.

```java
@Test
void shouldBackfillOnlyConfiguredBatchSize() {
    when(collectionService.getPendingTidalHomeTrackBackfillPlaylists(3))
        .thenReturn(List.of(playlist(1L), playlist(2L)));
    when(collectionService.backfillTidalHomePlaylistTracks(1L)).thenReturn(result(1L, 20));
    when(collectionService.backfillTidalHomePlaylistTracks(2L)).thenReturn(result(2L, 30));

    EmsTidalHomeTrackBackfillRun run = scheduler().runNow();

    assertThat(run.status()).isEqualTo("completed");
    assertThat(run.processedPlaylistCount()).isEqualTo(2);
    assertThat(run.linkedTrackCount()).isEqualTo(50);
}

@Test
void shouldContinueAndRecordErrorWhenOnePlaylistFails() {
    when(collectionService.getPendingTidalHomeTrackBackfillPlaylists(3))
        .thenReturn(List.of(playlist(1L), playlist(2L)));
    when(collectionService.backfillTidalHomePlaylistTracks(1L))
        .thenThrow(new IllegalStateException("TIDAL track endpoint rejected token"));
    when(collectionService.backfillTidalHomePlaylistTracks(2L)).thenReturn(result(2L, 30));

    EmsTidalHomeTrackBackfillRun run = scheduler(errorLogService).runNow();

    assertThat(run.status()).isEqualTo("completed_with_failures");
    assertThat(run.processedPlaylistCount()).isEqualTo(1);
    assertThat(run.failedPlaylistCount()).isEqualTo(1);
    verify(errorLogService).recordSchedulerFailure(
        eq("ems-tidal-home-track-backfill"),
        contains("playlist_id=1"),
        any(IllegalStateException.class),
        contains("\"playlistId\":1")
    );
}
```

같은 test class에 mock과 helper를 추가한다.

```java
private final EmsCollectionService collectionService = mock(EmsCollectionService.class);
private final ApplicationErrorLogService errorLogService = mock(ApplicationErrorLogService.class);

private EmsTidalHomeTrackBackfillScheduler scheduler() {
    return scheduler(null);
}

private EmsTidalHomeTrackBackfillScheduler scheduler(ApplicationErrorLogService errorLog) {
    EmsTidalHomeTrackBackfillProperties properties = new EmsTidalHomeTrackBackfillProperties();
    properties.setBatchSize(3);
    return new EmsTidalHomeTrackBackfillScheduler(
        collectionService,
        properties,
        Optional.ofNullable(errorLog)
    );
}

private EmsCollectedPlaylistEntity playlist(Long id) {
    EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
        "playlist-" + id, "Playlist " + id, "tidal", "", "", null, null, null,
        30, "public_pool", "POPULAR_PLAYLISTS", Instant.parse("2026-06-01T00:00:00Z")
    );
    ReflectionTestUtils.setField(playlist, "id", id);
    return playlist;
}

private EmsTidalHomeTrackBackfillResult result(Long playlistId, int linkedTracks) {
    return new EmsTidalHomeTrackBackfillResult(
        playlistId, linkedTracks, Instant.parse("2026-06-01T00:00:00Z")
    );
}
```

- [ ] **Step 2: 새 scheduler가 없어 compile FAIL인지 확인**

Run:

```bash
cd services/api
./gradlew test --tests '*EmsTidalHomeTrackBackfillSchedulerTest'
```

Expected: compile FAIL.

- [ ] **Step 3: link 없는 TIDAL home playlist repository query 추가**

`EmsCollectedPlaylistRepository`에 다음 query를 추가한다.

```java
@Query("""
    select distinct playlist.*
    from ems_collected_playlist playlist
    join ems_collected_playlist_source source
      on source.ems_collected_playlist_id = playlist.ems_collected_playlist_id
    where source.source_platform = 'tidal'
      and source.collection_source = 'public_pool'
      and source.source_id in (:sourceIds)
      and not exists (
          select 1
          from ems_collected_playlist_track link
          where link.ems_collected_playlist_id = playlist.ems_collected_playlist_id
      )
    order by playlist.collected_at asc, playlist.ems_collected_playlist_id asc
    """, nativeQuery = true)
List<EmsCollectedPlaylistEntity> findPendingTidalHomeTrackBackfill(
    @Param("sourceIds") List<String> sourceIds,
    Pageable pageable
);
```

- [ ] **Step 4: service에 playlist 단위 backfill API 추가**

`EmsCollectionService`의 TIDAL source 목록을 package에서 재사용할 수 있도록 공개하고 다음 메서드를 추가한다.

```java
public static final List<String> TIDAL_HOME_PAGE_SOURCE_IDS = List.of(
    "THE_HITS", "POPULAR_MIXES", "POPULAR_PLAYLISTS", "FROM_OUR_EDITORS"
);

public List<EmsCollectedPlaylistEntity> getPendingTidalHomeTrackBackfillPlaylists(int limit) {
    int clamped = Math.min(Math.max(limit, 1), 10);
    return playlistRepository.findPendingTidalHomeTrackBackfill(
        TIDAL_HOME_PAGE_SOURCE_IDS,
        PageRequest.of(0, clamped)
    );
}

@Transactional
public EmsTidalHomeTrackBackfillResult backfillTidalHomePlaylistTracks(Long playlistId) {
    EmsCollectedPlaylistEntity playlist = getCollectedPlaylist(playlistId);
    if (!"tidal".equals(playlist.getSourcePlatform())
        || !"public_pool".equals(playlist.getCollectionSource())
        || !isTidalHomePageSource(playlist.getSearchQuery())) {
        throw new IllegalArgumentException("Playlist is not a TIDAL home public-pool playlist: " + playlistId);
    }
    TidalPlaylistSummary summary = new TidalPlaylistSummary(
        playlist.getExternalPlaylistId(),
        playlist.getTitle(),
        playlist.getDescription(),
        playlist.getTrackCount(),
        playlist.getCoverImageUrl(),
        null,
        playlist.getPlatformExternalUrl(),
        playlist.getExternalPlaylistId()
    );
    int linkedTracks = collectTidalPlaylistTracks(playlist, summary, null, "public_pool", Instant.now(), true);
    return new EmsTidalHomeTrackBackfillResult(playlistId, linkedTracks, Instant.now());
}

public record EmsTidalHomeTrackBackfillResult(
    Long playlistId,
    int linkedTrackCount,
    Instant completedAt
) {}
```

- [ ] **Step 5: properties와 scheduler 구현**

`EmsTidalHomeTrackBackfillProperties`:

```java
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ems.tidal-home-track-backfill")
public class EmsTidalHomeTrackBackfillProperties {
    private boolean enabled = true;
    private int batchSize = 3;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
```

`EmsTidalHomeTrackBackfillScheduler`:

```java
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeTrackBackfillResult;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsTidalHomeTrackBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmsTidalHomeTrackBackfillScheduler.class);

    private final EmsCollectionService collectionService;
    private final EmsTidalHomeTrackBackfillProperties properties;
    private final Optional<ApplicationErrorLogService> errorLogService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<EmsTidalHomeTrackBackfillRun> lastRun = new AtomicReference<>();

    public EmsTidalHomeTrackBackfillScheduler(
        EmsCollectionService collectionService,
        EmsTidalHomeTrackBackfillProperties properties,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.collectionService = collectionService;
        this.properties = properties;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        initialDelayString = "${app.ems.tidal-home-track-backfill.initial-delay-ms:180000}",
        fixedDelayString = "${app.ems.tidal-home-track-backfill.refresh-interval-ms:60000}"
    )
    public void backfillScheduled() {
        backfill("scheduled");
    }

    public EmsTidalHomeTrackBackfillRun runNow() {
        return backfill("manual");
    }

    public EmsTidalHomeTrackBackfillRun lastRun() {
        return lastRun.get();
    }

    private EmsTidalHomeTrackBackfillRun backfill(String trigger) {
        Instant startedAt = Instant.now();
        if (!properties.isEnabled()) {
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger, "skipped", startedAt, Instant.now(), 0, 0, 0, 0,
                "EMS TIDAL home track backfill is disabled."
            ));
        }
        if (!running.compareAndSet(false, true)) {
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger, "skipped", startedAt, Instant.now(), 0, 0, 0, 0,
                "EMS TIDAL home track backfill skipped because a previous run is still active."
            ));
        }
        try {
            List<EmsCollectedPlaylistEntity> playlists =
                collectionService.getPendingTidalHomeTrackBackfillPlaylists(properties.getBatchSize());
            int processed = 0;
            int failed = 0;
            int linkedTracks = 0;
            for (EmsCollectedPlaylistEntity playlist : playlists) {
                try {
                    EmsTidalHomeTrackBackfillResult result =
                        collectionService.backfillTidalHomePlaylistTracks(playlist.getId());
                    processed++;
                    linkedTracks += result.linkedTrackCount();
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn(
                        "EMS TIDAL home track backfill failed playlist_id={}: {}",
                        playlist.getId(),
                        exception.getMessage()
                    );
                    errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                        "ems-tidal-home-track-backfill",
                        "EMS TIDAL home track backfill failed playlist_id=%d: %s"
                            .formatted(playlist.getId(), exception.getMessage()),
                        exception,
                        "{\"playlistId\":%d}".formatted(playlist.getId())
                    ));
                }
            }
            String status = failed == 0 ? "completed" : "completed_with_failures";
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger, status, startedAt, Instant.now(), playlists.size(), processed, failed,
                linkedTracks, "EMS TIDAL home track backfill %s.".formatted(status)
            ));
        } finally {
            running.set(false);
        }
    }

    private EmsTidalHomeTrackBackfillRun remember(EmsTidalHomeTrackBackfillRun run) {
        lastRun.set(run);
        return run;
    }

    public record EmsTidalHomeTrackBackfillRun(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        int candidatePlaylistCount,
        int processedPlaylistCount,
        int failedPlaylistCount,
        int linkedTrackCount,
        String message
    ) {}
}
```

`application.yml`에 다음 설정을 추가한다.

```yaml
    tidal-home-track-backfill:
      enabled: ${EMS_TIDAL_HOME_TRACK_BACKFILL_ENABLED:true}
      refresh-interval-ms: ${EMS_TIDAL_HOME_TRACK_BACKFILL_REFRESH_INTERVAL_MS:60000}
      initial-delay-ms: ${EMS_TIDAL_HOME_TRACK_BACKFILL_INITIAL_DELAY_MS:180000}
      batch-size: ${EMS_TIDAL_HOME_TRACK_BACKFILL_BATCH_SIZE:3}
```

- [ ] **Step 6: scheduler 테스트 실행**

Run:

```bash
cd services/api
./gradlew test --tests '*EmsTidalHomeTrackBackfillSchedulerTest' --tests '*EmsCollectionServiceTest'
```

Expected: PASS.

- [ ] **Step 7: 이번 hunk만 커밋**

```bash
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java
git add services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillProperties.java
git add services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillScheduler.java
git add services/api/src/test/java/io/myforevermusic/api/modules/ems/application/EmsTidalHomeTrackBackfillSchedulerTest.java
git add -p services/api/src/main/resources/application.yml
git diff --cached --check
git commit -m "feat: backfill TIDAL home playlist tracks in batches"
```

---

### Task 4: source별 DB paging API

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionController.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionControllerWebMvcTest.java`

- [ ] **Step 1: paging API controller 실패 테스트 작성**

`EmsCollectionControllerWebMvcTest`에 page 응답과 validation을 검증하는 테스트를 추가한다.

```java
@Test
void shouldReturnPagedStoredTidalHomePlaylists() throws Exception {
    EmsCollectedPlaylistEntity playlist = tidalHomePlaylist(101L, "playlist-101");
    when(emsCollectionService.getTidalHomePlaylists("POPULAR_PLAYLISTS", 1, 12))
        .thenReturn(new PageImpl<>(List.of(playlist), PageRequest.of(1, 12), 25));
    when(emsCollectionService.getAudioFeatureCoverage(101L))
        .thenReturn(new EmsAudioFeatureCoverage(0, 0, 0, 0.0));

    mockMvc.perform(get("/api/v1/ems/collection/tidal-home/playlists")
            .param("source_id", "POPULAR_PLAYLISTS")
            .param("page", "1")
            .param("size", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source_id").value("POPULAR_PLAYLISTS"))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(12))
        .andExpect(jsonPath("$.total_elements").value(25))
        .andExpect(jsonPath("$.total_pages").value(3))
        .andExpect(jsonPath("$.playlists[0].id").value(101));
}

@Test
void shouldRejectOversizedTidalHomePage() throws Exception {
    mockMvc.perform(get("/api/v1/ems/collection/tidal-home/playlists")
            .param("source_id", "POPULAR_PLAYLISTS")
            .param("page", "0")
            .param("size", "13"))
        .andExpect(status().isBadRequest());
}

@Test
void shouldRejectUnsupportedTidalHomeSource() throws Exception {
    mockMvc.perform(get("/api/v1/ems/collection/tidal-home/playlists")
            .param("source_id", "NOT_A_HOME_SOURCE"))
        .andExpect(status().isBadRequest());
}

@Test
void shouldRejectNegativeTidalHomePage() throws Exception {
    mockMvc.perform(get("/api/v1/ems/collection/tidal-home/playlists")
            .param("source_id", "POPULAR_PLAYLISTS")
            .param("page", "-1"))
        .andExpect(status().isBadRequest());
}
```

같은 test class에 helper를 추가한다.

```java
private EmsCollectedPlaylistEntity tidalHomePlaylist(Long id, String externalPlaylistId) {
    EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
        externalPlaylistId,
        "TIDAL Playlist " + id,
        "tidal",
        "",
        "",
        null,
        "https://tidal.com/browse/playlist/" + externalPlaylistId,
        null,
        50,
        "public_pool",
        "POPULAR_PLAYLISTS",
        Instant.parse("2026-06-01T00:00:00Z")
    );
    ReflectionTestUtils.setField(playlist, "id", id);
    return playlist;
}
```

- [ ] **Step 2: endpoint가 없어 FAIL인지 확인**

Run:

```bash
cd services/api
./gradlew test \
  --tests '*EmsCollectionControllerWebMvcTest.shouldReturnPagedStoredTidalHomePlaylists' \
  --tests '*EmsCollectionControllerWebMvcTest.shouldRejectOversizedTidalHomePage' \
  --tests '*EmsCollectionControllerWebMvcTest.shouldRejectUnsupportedTidalHomeSource' \
  --tests '*EmsCollectionControllerWebMvcTest.shouldRejectNegativeTidalHomePage'
```

Expected: FAIL with 404.

- [ ] **Step 3: repository와 service DB paging 추가**

`EmsCollectedPlaylistRepository`:

```java
@Query("""
    select playlist.*
    from ems_collected_playlist playlist
    join ems_collected_playlist_source source
      on source.ems_collected_playlist_id = playlist.ems_collected_playlist_id
    where source.source_platform = 'tidal'
      and source.collection_source = 'public_pool'
      and source.source_id = :sourceId
    order by source.collected_at desc, playlist.ems_collected_playlist_id desc
    """,
    countQuery = """
        select count(*)
        from ems_collected_playlist_source source
        where source.source_platform = 'tidal'
          and source.collection_source = 'public_pool'
          and source.source_id = :sourceId
        """,
    nativeQuery = true)
Page<EmsCollectedPlaylistEntity> findTidalHomeBySourceId(
    @Param("sourceId") String sourceId,
    Pageable pageable
);
```

`EmsCollectionService`:

```java
public Page<EmsCollectedPlaylistEntity> getTidalHomePlaylists(String sourceId, int page, int size) {
    if (!TIDAL_HOME_PAGE_SOURCE_IDS.contains(sourceId)) {
        throw new IllegalArgumentException("Unsupported TIDAL home source: " + sourceId);
    }
    if (page < 0 || size < 1 || size > 12) {
        throw new IllegalArgumentException("TIDAL home page must be >= 0 and size must be between 1 and 12.");
    }
    return playlistRepository.findTidalHomeBySourceId(sourceId, PageRequest.of(page, size));
}
```

- [ ] **Step 4: controller endpoint와 response record 추가**

`EmsCollectionController`:

```java
@GetMapping("/tidal-home/playlists")
public EmsTidalHomePlaylistPageResponse browseTidalHomePlaylists(
    @RequestParam("source_id") String sourceId,
    @RequestParam(value = "page", defaultValue = "0") int page,
    @RequestParam(value = "size", defaultValue = "12") int size
) {
    Page<EmsCollectedPlaylistEntity> result = emsCollectionService.getTidalHomePlaylists(sourceId, page, size);
    return new EmsTidalHomePlaylistPageResponse(
        "api", "ok", Instant.now(), sourceId,
        result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
        result.getContent().stream().map(this::toPlaylistItem).toList()
    );
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EmsTidalHomePlaylistPageResponse(
    String service,
    String status,
    Instant generatedAt,
    String sourceId,
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<EmsCollectionPlaylistItem> playlists
) {}
```

`IllegalArgumentException`이 400으로 매핑되는 기존 exception handler를 유지한다.

- [ ] **Step 5: controller 테스트 실행**

Run:

```bash
cd services/api
./gradlew test --tests '*EmsCollectionControllerWebMvcTest'
```

Expected: PASS.

- [ ] **Step 6: 이번 hunk만 커밋**

```bash
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedPlaylistRepository.java
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/application/EmsCollectionService.java
git add -p services/api/src/main/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionController.java
git add -p services/api/src/test/java/io/myforevermusic/api/modules/ems/presentation/EmsCollectionControllerWebMvcTest.java
git diff --cached --check
git commit -m "feat: expose paged TIDAL home EMS playlists"
```

---

### Task 5: EMS 화면 source별 독립 pagination

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/services/api.ts`
- Modify: `apps/web/src/components/home/TidalHomePageSections.tsx`
- Modify: `apps/web/scripts/product-flow-regression-harness.mjs`
- Modify: `docs/PROJECT_GUIDE.md`

- [ ] **Step 1: product-flow regression 실패 조건 추가**

`product-flow-regression-harness.mjs`의 `userFacingFlowFiles`에 컴포넌트를 추가한다.

```js
tidalHomeSections: read('src/components/home/TidalHomePageSections.tsx'),
```

검사를 추가한다.

```js
check(
    'EMS TIDAL home sections use DB paging instead of client-side truncation',
    /fetchEmsTidalHomePlaylists/.test(userFacingFlowFiles.tidalHomeSections) &&
        /total_pages/.test(userFacingFlowFiles.tidalHomeSections) &&
        !/fetchEmsCollectedPlaylists\('tidal'/.test(userFacingFlowFiles.tidalHomeSections) &&
        !/PER_SOURCE_LIMIT/.test(userFacingFlowFiles.tidalHomeSections),
    'TIDAL home sections should request source-specific DB pages and render independent paging controls.',
)
```

- [ ] **Step 2: regression harness가 FAIL인지 확인**

Run:

```bash
cd apps/web
npm run test:product-flow
```

Expected: `FAIL EMS TIDAL home sections use DB paging instead of client-side truncation`.

- [ ] **Step 3: API type과 client 추가**

`apps/web/src/types/api.ts`:

```ts
export interface EmsTidalHomePlaylistPageResponse {
    service: string
    status: string
    generated_at: string
    source_id: string
    page: number
    size: number
    total_elements: number
    total_pages: number
    playlists: EmsCollectionPlaylistItem[]
}
```

`apps/web/src/services/api.ts`의 `@/types/api` import 목록에
`EmsTidalHomePlaylistPageResponse`를 추가하고 다음 client를 추가한다.

```ts
export const fetchEmsTidalHomePlaylists = (
    sourceId: string,
    page: number = 0,
    size: number = 12,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams()
    params.set('source_id', sourceId)
    params.set('page', String(Math.max(0, page)))
    params.set('size', String(Math.min(12, Math.max(1, size))))
    return requestJson<EmsTidalHomePlaylistPageResponse>(
        `/api/v1/ems/collection/tidal-home/playlists?${params.toString()}`,
        { signal },
    )
}
```

- [ ] **Step 4: source별 state와 controls 구현**

`TidalHomePageSections.tsx`에서 범용 `fetchEmsCollectedPlaylists('tidal', ..., 200, false)`와
client-side grouping을 제거한다. 파일을 다음 구조로 교체한다.

```tsx
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight, ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { fetchEmsTidalHomePlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

const TIDAL_HOME_SOURCES = [
    { id: 'POPULAR_PLAYLISTS', title: 'Popular Playlists' },
    { id: 'THE_HITS', title: 'The Hits' },
    { id: 'POPULAR_MIXES', title: 'Popular Mixes' },
    { id: 'FROM_OUR_EDITORS', title: 'From our editors' },
]
const PAGE_SIZE = 12

type SourceState = {
    status: 'loading' | 'ready' | 'empty' | 'error'
    totalPages: number
    playlists: EmsCollectionPlaylistItem[]
}

const TidalPlaylistCard = ({ playlist }: { playlist: EmsCollectionPlaylistItem }) => (
    <Link
        to={`/playlists/ems/${playlist.id}`}
        className="group flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
    >
        <div className="relative aspect-square overflow-hidden">
            <MusicArtwork imageUrl={playlist.cover_image_url} seed={`tidal-${playlist.external_playlist_id}`} label={playlist.title} />
        </div>
        <div className="space-y-1 p-3">
            <p className="truncate text-sm font-semibold text-hud-text-primary">{playlist.title}</p>
            <p className="truncate text-xs text-hud-text-secondary">{playlist.curator || 'TIDAL'}</p>
            <p className="flex items-center gap-1 text-[11px] text-hud-text-muted">
                <ListMusic size={12} />
                {playlist.track_count} tracks
            </p>
        </div>
    </Link>
)

const PageButton = ({
    label,
    disabled,
    onClick,
    children,
}: {
    label: string
    disabled: boolean
    onClick: () => void
    children: ReactNode
}) => (
    <button
        type="button"
        title={label}
        aria-label={label}
        disabled={disabled}
        onClick={onClick}
        className="grid h-8 w-8 place-items-center rounded-lg border border-hud-border-secondary text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary disabled:cursor-not-allowed disabled:opacity-35"
    >
        {children}
    </button>
)

const TidalHomePageSection = ({ source }: { source: { id: string; title: string } }) => {
    const [page, setPage] = useState(0)
    const [state, setState] = useState<SourceState>({
        status: 'loading',
        totalPages: 0,
        playlists: [],
    })

    useEffect(() => {
        const controller = new AbortController()
        setState((current) => ({ ...current, status: 'loading' }))
        fetchEmsTidalHomePlaylists(source.id, page, PAGE_SIZE, controller.signal)
            .then((response) => {
                if (controller.signal.aborted) return
                setState({
                    status: response.playlists.length > 0 ? 'ready' : 'empty',
                    totalPages: response.total_pages,
                    playlists: response.playlists,
                })
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    setState({ status: 'error', totalPages: 0, playlists: [] })
                }
            })
        return () => controller.abort()
    }, [page, source.id])

    if (state.status === 'empty') return null

    return (
        <section className="space-y-4">
            <header className="flex min-h-8 items-center justify-between gap-3">
                <div className="flex items-baseline gap-2">
                    <h2 className="text-lg font-semibold text-hud-text-primary">{source.title}</h2>
                    <span className="text-xs text-hud-text-muted">on TIDAL</span>
                </div>
                {state.totalPages > 1 && (
                    <div className="flex items-center gap-2">
                        <PageButton label="이전 페이지" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>
                            <ChevronLeft size={16} />
                        </PageButton>
                        <span className="min-w-12 text-center text-xs text-hud-text-muted">{page + 1} / {state.totalPages}</span>
                        <PageButton
                            label="다음 페이지"
                            disabled={page + 1 >= state.totalPages}
                            onClick={() => setPage((value) => value + 1)}
                        >
                            <ChevronRight size={16} />
                        </PageButton>
                    </div>
                )}
            </header>
            {state.status === 'error' ? (
                <p className="text-sm text-hud-text-secondary">저장된 TIDAL 플레이리스트 목록을 불러오지 못했습니다.</p>
            ) : (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                    {state.status === 'loading'
                        ? Array.from({ length: 6 }).map((_, index) => (
                            <div key={index} className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60" />
                        ))
                        : state.playlists.map((playlist) => <TidalPlaylistCard key={playlist.id} playlist={playlist} />)}
                </div>
            )}
        </section>
    )
}

const TidalHomePageSections = () => (
    <div className="space-y-8">
        {TIDAL_HOME_SOURCES.map((source) => <TidalHomePageSection key={source.id} source={source} />)}
    </div>
)

export default TidalHomePageSections
```

- [ ] **Step 5: PROJECT_GUIDE 갱신**

`docs/PROJECT_GUIDE.md` EMS 운영 설명에 다음 원칙을 기록한다.

```markdown
- TIDAL 공개 home playlist는 source별 전체 metadata를 EMS DB에 먼저 동기화하고, track은 작은 background batch로 보강한다. EMS 화면은 provider live 응답이 아니라 DB 저장본만 source별 12개씩 페이징한다.
```

- [ ] **Step 6: web 검증**

Run:

```bash
cd apps/web
npm run test:product-flow
npm run lint
npm run build
```

Expected: 모두 PASS. Vite의 기존 large chunk warning은 허용한다.

- [ ] **Step 7: 이번 hunk만 커밋**

```bash
git add -p apps/web/src/types/api.ts
git add -p apps/web/src/services/api.ts
git add -p apps/web/src/components/home/TidalHomePageSections.tsx
git add -p apps/web/scripts/product-flow-regression-harness.mjs
git add -p docs/PROJECT_GUIDE.md
git diff --cached --check
git commit -m "feat: paginate EMS TIDAL home sections"
```

---

### Task 6: 전체 검증과 운영 확인

**Files:**
- Verify only

- [ ] **Step 1: backend 전체 테스트**

Run:

```bash
cd services/api
./gradlew test
```

Expected: PASS.

- [ ] **Step 2: frontend 회귀, lint, build**

Run:

```bash
cd apps/web
npm run test:regression
npm run lint
npm run build
```

Expected: 모두 PASS. Vite의 기존 large chunk warning은 허용한다.

- [ ] **Step 3: 서버 재시작 후 metadata sync 실행**

서버 반영 후 운영자 요청으로 discovery를 한 번 실행한다.

```bash
curl -fsS -X POST 'http://127.0.0.1:8081/api/v1/ems/collection/discovery/run' \
  -H 'Content-Type: application/json' \
  --data '{"platforms":["tidal"],"seed_queries":["tidal:POPULAR_PLAYLISTS","tidal:THE_HITS","tidal:POPULAR_MIXES","tidal:FROM_OUR_EDITORS"]}'
```

Expected: `status`가 `completed`, `collected_playlist_count`가 0보다 크다.

- [ ] **Step 4: DB 저장본 확인**

Run:

```bash
docker exec my-forever-music-postgres psql -U postgres -d my_forever_music -c \
  "select source_id, count(*) from ems_collected_playlist_source where source_platform = 'tidal' and collection_source = 'public_pool' group by source_id order by source_id;"
```

Expected: `POPULAR_PLAYLISTS`, `THE_HITS`, `FROM_OUR_EDITORS` 저장 개수가 provider 목록에 맞게 증가한다. 현재 provider에서 비어 있는 `POPULAR_MIXES`는 없어도 정상이다.

- [ ] **Step 5: paging API 확인**

Run:

```bash
curl -fsS 'http://127.0.0.1:8081/api/v1/ems/collection/tidal-home/playlists?source_id=POPULAR_PLAYLISTS&page=0&size=12'
```

Expected: `playlists` 최대 12개, `total_elements` 12 초과, `total_pages` 1 초과.

- [ ] **Step 6: browser 확인**

EMS 페이지 `/ems`에서 다음을 확인한다.

- TIDAL source section이 DB 저장본으로 표시된다.
- `Popular Playlists`에서 이전/다음 controls가 보인다.
- 다음 page 이동 시 해당 section만 목록이 바뀐다.
- 다른 source의 현재 page는 유지된다.
- track background batch가 진행 중이어도 playlist 카드와 pagination은 즉시 사용할 수 있다.

- [ ] **Step 7: 남은 변경 확인**

Run:

```bash
git status --short
git log --oneline -8
```

Expected: 이번 작업 커밋이 보이고, 기존 dirty work는 보존되어 있다.
