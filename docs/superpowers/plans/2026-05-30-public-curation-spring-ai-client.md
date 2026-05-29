# Public Curation Spring AI Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Spring Boot API call the FastAPI Public Curation scoring endpoint through a focused client contract.

**Architecture:** Extend shared `AiServiceProperties` with `publicCurationScorePath`, then add `AiPublicCurationScoringClient` under the `publiccuration` module. This slice does not implement candidate extraction or admin APIs; it only proves the Java client can serialize the request, call the endpoint, and deserialize selected tracks.

**Tech Stack:** Spring Boot 3.5, Java 21 records, Jackson snake_case records, Java `HttpClient`, JUnit 5, in-process `HttpServer`.

---

### Task 1: Spring AI Scoring Client

**Files:**
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/infrastructure/ai/AiServiceProperties.java`
- Modify: `services/api/src/main/resources/application.yml`

- [x] **Step 1: Write the failing test**

```java
@Test
void shouldPostCandidateTracksToPublicCurationScoreEndpoint() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/public-curations/score", exchange -> {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = """
            {
              "service": "public-curation",
              "status": "ok",
              "model_version": "public-curation-deterministic-v1",
              "title": "비 오는 밤의 Public Curation",
              "subtitle": "모델이 고른 외부 공유용 플레이리스트",
              "description": "TIDAL-ready 후보 1곡을 선별했습니다.",
              "tracks": [{
                "order": 1,
                "source_scope": "ems_collected_track",
                "source_track_id": "ems-track-1",
                "title": "Rain Street",
                "artist_name": "Blue Trio",
                "album_title": "Night Walk",
                "duration_ms": 181000,
                "isrc": "KRA000000001",
                "tidal_track_id": "10001",
                "tidal_uri": "tidal:track:10001",
                "tidal_external_url": null,
                "score": 0.94,
                "score_breakdown": {"tidal_readiness": 1.0},
                "reason": "TIDAL-ready 후보입니다."
              }],
              "score_summary": {"candidate_count": 1, "tidal_ready_count": 1},
              "warnings": [],
              "generated_at": "2026-05-30T00:00:00Z"
            }
            """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    });
    server.start();

    try {
        AiPublicCurationScoringClient client = new AiPublicCurationScoringClient(
            new AiServiceProperties(
                "http://127.0.0.1:%d".formatted(server.getAddress().getPort()),
                "/v1/recommendations/preview",
                "/v1/ems/overview",
                "/v1/ems/acquisition/signals",
                "/v1/audio-features/infer",
                "/v1/recommendations/datasets/sasrec/train",
                "/v1/recommendations/datasets/sasrec/rank",
                "/v1/recommendations/datasets/sasrec/models/latest",
                "/v1/public-curations/score",
                ""
            ),
            new ObjectMapper()
        );

        AiPublicCurationScoringClient.AiPublicCurationScoreResponse response =
            client.score(AiPublicCurationScoringClient.AiPublicCurationScoreRequest.sampleForTest());

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.tracks()).hasSize(1);
        assertThat(requestBody.get()).contains("\"target_track_count\":1");
        assertThat(requestBody.get()).contains("\"candidate_tracks\"");
    } finally {
        server.stop(0);
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClientTest`

Expected: FAIL because `AiPublicCurationScoringClient` and `publicCurationScorePath` do not exist.

- [x] **Step 3: Implement the client**

Add snake_case request/response records and `score(...)` method that POSTs to `baseUrl + publicCurationScorePath`.

- [x] **Step 4: Run test to verify it passes**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClientTest`

Expected: PASS.
