# Personalized Recommendation Model Plan

작성일: `2026-05-11`

이 문서는 `my-forever-music`의 개인화 음악 추천 모델 개발을 시작하기 위한 기획 문서입니다.

목표는 특정 스트리밍 플랫폼에 종속되지 않는 사용자 취향 모델을 만들고, `PMS -> EMS -> GMS -> PMS` 환류 루프를 실제 학습 데이터로 닫는 것입니다.

## 0. 전체 아키텍처

추천 시스템은 아래 7개 구간으로 나눕니다.

```text
1. Data Sources
   -> 2. Collection and Normalization
   -> 3. Metadata Feature Store
   -> 4. Personalized Model
   -> 5. Playlist Scoring Engine
   -> 6. Feedback Loop
   -> 7. Governance and Quality Management
```

데이터 흐름은 `1 -> 6`으로 진행되고, 피드백은 다시 feature store와 개인화 모델로 돌아옵니다. `7. Governance and Quality Management`는 모든 구간을 가로지르는 품질 기준입니다.

| Stage | 입력 | 출력 | 현재 제품 매핑 |
|---|---|---|---|
| `1. Data Sources` | 플랫폼 API, MusicBrainz, IFPI, Wikidata, Discogs, Last.fm, ListenBrainz, ReccoBeats | 원본 playlist, track, metadata, behavior signal | platform, PMS import, EMS 수집 |
| `2. Collection and Normalization` | 원본 metadata | 정규화 entity, identifier mapping, 중복 제거 결과 | provider client, enrichment pipeline |
| `3. Metadata Feature Store` | 정규화 metadata, audio feature, behavior patch | 모델 입력용 snapshot | PostgreSQL 기반 PMS/EMS/GMS feature snapshot |
| `4. Personalized Model` | metadata token, behavior token, user sequence | item/user embedding, affinity score | FastAPI AI service |
| `5. Playlist Scoring Engine` | candidate tracks, model score, quality features | playlist score, ranked recommendations | GMS recommendation preview |
| `6. Feedback Loop` | like, skip, completion, add-to-playlist, replay, save | user event, model update trigger | player/PMS/GMS event logging |
| `7. Governance and Quality Management` | source confidence, identity confidence, policy | confidence score, monitoring, audit trail | quality dashboard, backfill status |

### 0-1. Data Sources

데이터 소스는 세 그룹으로 분리합니다.

- 구독 음악 플랫폼 API: 사용자 playlist, follow/save, playback target, provider track metadata
- 공개 metadata/행동 데이터: MusicBrainz, IFPI ISRC, Wikidata, Discogs, Last.fm, ListenBrainz
- 선택적 보조 source: ReccoBeats audio features, provider-specific audio feature APIs

Spotify audio features는 사용할 수 있으면 참고할 수 있지만, canonical model의 필수 source로 두지 않습니다.

### 0-2. Collection and Normalization

수집 계층은 세 가지 일을 합니다.

- entity resolution: artist, recording, release, release-group, provider track을 같은 내부 entity로 묶음
- identifier mapping: MBID, ISRC, Wikidata QID, Discogs id, provider id 연결
- duplicate cleanup: 동일 entity 병합, 버전/발매 단위 정리, 최신성 기준 적용

정규화의 산출물은 `artist -> recording -> release -> release-group` 중심의 공통 schema입니다.

### 0-3. Metadata Feature Store

feature store에는 아래 feature를 저장합니다.

- artist identity: MBID, QID, alias
- recording identity: MBID, ISRC, provider ids
- release metadata: release date, country, label, format
- tag and genre: Last.fm tag, Discogs style, provider genre
- popularity and listening signal: Last.fm/ListenBrainz, 내부 playback frequency
- user behavior patch: play, skip, save, add-to-playlist, search, replay

온라인 serving과 오프라인 학습이 같은 snapshot을 보도록 versioned snapshot을 둡니다.

### 0-4. Personalized Model

모델 입력은 두 종류 token으로 구성합니다.

- metadata token: artist, genre, release year, source, confidence, audio feature bucket
- behavior token: play, skip, save, add, replay, like, reject, completion

학습 목표는 next-item prediction, masked-item prediction, ranking prediction입니다. 사용자별 개인화는 user embedding과 ranking head를 먼저 갱신하고, sequence encoder는 batch로 재학습합니다.

### 0-5. Playlist Scoring Engine

모델 score만으로 playlist를 통과시키지 않습니다. playlist scoring engine은 아래 축을 함께 계산합니다.

- affinity
- novelty
- coherence
- diversity
- redundancy penalty
- confidence

이 점수는 추천 순위와 설명의 기준이 됩니다.

### 0-6. Feedback Loop

사용자 행동은 모두 학습 이벤트가 됩니다.

- like
- skip
- completion
- add to playlist
- replay
- save playlist

이벤트는 바로 feature store에 적재하고, user embedding/reranker에는 빠르게 반영합니다. 전체 모델 update는 batch로 관리합니다.

### 0-7. Governance and Quality Management

품질 관리는 별도 부가 기능이 아니라 추천 시스템의 안전장치입니다.

- source confidence: 권위성, 최신성, 일관성 기준으로 source별 가중치 관리
- ISRC missing flow: MusicBrainz -> Discogs/IFPI -> 기타 권위 source 순서로 검증
- data quality monitoring: 누락률, 중복률, matching failure rate, anomaly detection
- policy and safety: 저작권/라이선스 준수, 개인정보 최소화, 안전 source만 사용
- optional source policy: Spotify audio features 같은 선택적 source는 없으면 시스템이 중단되지 않아야 함

## 1. 목적

개인화 추천 모델은 사용자가 구독 중인 플랫폼에서 가져온 플레이리스트, 이후 사이트 안에서 남긴 행동 로그, EMS 공개 playlist pool, GMS 평가 결과를 함께 사용해 사용자에게 맞는 트랙과 플레이리스트를 지속적으로 추천합니다.

핵심 원칙은 아래와 같습니다.

- Spotify, TIDAL 같은 플랫폼은 원본 metadata와 playback target의 출처다.
- 사용자 취향의 장기 기준은 `PMS user library`와 내부 행동 로그다.
- 모델의 중심 입력은 오디오 파형이 아니라 사용자 행동 시퀀스와 정규화된 track metadata다.
- 오디오 특성은 유용한 보조 feature지만, 추천 모델의 canonical source를 특정 플랫폼 오디오 분석 API에 묶지 않는다.
- 검색이나 외부 metadata 보강은 confidence가 충분할 때만 canonical data에 반영한다.

## 2. 현재 전제

현재 구현 흐름은 모델 개발에 필요한 첫 데이터 기반을 일부 갖추고 있습니다.

- `PMS`는 사용자가 가져온 playlist와 track metadata를 사용자 소유 library로 저장하는 기준 공간이다.
- `EMS`는 외부 공개 playlist pool과 track metadata를 DB에 저장한다.
- `EMS`와 `PMS` track은 provider-neutral audio feature snapshot을 가질 수 있다.
- `ReccoBeats` 기반 audio feature 보강은 Spotify track id 또는 TIDAL ISRC match로 동작한다.
- `Last.fm` scrobble/profile은 장기 취향 signal로 사용할 수 있다.
- 공통 플레이어는 재생, 스킵, 반복, 저장 같은 행동 이벤트의 출처가 될 수 있다.

모델 개발의 다음 초점은 `좋은 추천 알고리즘`보다 먼저 `학습 가능한 이벤트와 feature store`를 안정화하는 것입니다.

## 3. 모델 기본 방향

### 3-1. Sequential Recommendation 우선

초기 모델은 오디오 생성/분석 모델이 아니라 사용자 이력 기반 sequential recommendation 구조로 둡니다.

후보 모델:

- `SASRec`: self-attention 기반 sequence model. sparse한 사용자 이력에서도 비교적 단순하게 시작할 수 있어 1차 구현 후보로 적합합니다.
- `BERT4Rec`: masked item prediction 기반 양방향 sequence model. 더 많은 interaction이 쌓이면 동적 취향과 문맥을 포착하는 데 유리합니다.

실행 순서는 `baseline ranker -> SASRec MVP -> BERT4Rec 검토`로 잡습니다. 300곡 수준의 개인 playlist만으로 바로 큰 Transformer를 미세조정하면 과적합 위험이 크므로, 먼저 event schema와 offline evaluation을 고정합니다.

### 3-2. 두 층 구조

모델은 두 층으로 운영합니다.

1. 공통 추천 모델
   - 여러 사용자와 EMS/PMS catalog의 interaction을 기반으로 학습합니다.
   - item embedding, metadata embedding, sequence encoder를 포함합니다.

2. 사용자 전용 개인화 층
   - 특정 사용자의 playlist, 저장, 재생 완료, 반복 재생, 스킵, 추천 평가를 반영합니다.
   - 초기에는 user embedding과 ranking head를 빠르게 갱신합니다.
   - 전체 sequence encoder 재학습은 주기적인 batch로 제한합니다.

## 4. 데이터 설계

### 4-1. 핵심 엔티티

| Entity | 역할 |
|---|---|
| `user` | 장기 계정과 preferred playback platform |
| `track` | PMS/EMS/GMS에서 공유되는 canonical music item |
| `playlist` | PMS 사용자 playlist, imported playlist, EMS public playlist |
| `track_identity` | ISRC, MusicBrainz recording id, Wikidata id, Discogs id, provider track id |
| `track_metadata_snapshot` | title, artist, release, genre/tag, country, label, release year |
| `track_audio_feature_snapshot` | ReccoBeats 등 외부 공급원 기반 provider-neutral audio features |
| `user_music_event` | 재생, 스킵, 저장, playlist 추가, 평가 같은 행동 로그 |
| `recommendation_snapshot` | 특정 시점 추천 결과와 점수, 이후 반응 추적 |

### 4-2. Event Token 설계

Transformer 입력은 단순 track id 나열이 아니라 행동과 문맥을 포함한 token sequence로 구성합니다.

기본 event fields:

- `user_id`
- `event_id`
- `event_type`
- `track_id`
- `playlist_id`
- `source_space`: `pms`, `ems`, `gms`, `player`, `platform`
- `source_platform`: `spotify`, `tidal`, `lastfm`, etc.
- `occurred_at`
- `position_ms`
- `duration_ms`
- `play_ratio`
- `recommendation_id`
- `metadata_confidence`

초기 event type:

| Event | 신호 해석 |
|---|---|
| `playlist_imported` | 약한 긍정. 사용자가 소유한 취향 데이터 |
| `track_saved` | 강한 긍정 |
| `added_to_playlist` | 강한 긍정 |
| `play_completed` | 긍정 |
| `repeat_played` | 강한 긍정 |
| `skipped_early` | 약한 부정 |
| `stopped_midway` | 약한 부정 |
| `recommendation_liked` | 강한 긍정 |
| `recommendation_rejected` | 강한 부정 |
| `ignored_recommendation` | 약한 부정 또는 미반응 |

초기에는 event weight를 명시적으로 둡니다. 이후 모델 학습이 안정화되면 weight는 ranking loss와 feedback labels로 흡수합니다.

## 5. Metadata Source 전략

### 5-1. 기준 metadata 축

| Source | 역할 |
|---|---|
| `IFPI ISRC` | recording 식별의 공식 기준축. 확실할 때만 저장 |
| `MusicBrainz` | artist, recording, release, release-group 정규화 |
| `Wikidata` | 외부 식별자 연결 허브 |

### 5-2. 보조 metadata 축

| Source | 역할 |
|---|---|
| `Discogs` | 발매본, 레이블, 국가, 포맷, 스타일 보강 |
| `Last.fm` | community tag, top artist/track, discovery signal |
| `ListenBrainz` | listening feedback, recommendation 보조 signal |
| `ReccoBeats` | provider-neutral audio feature lookup |
| `Provider API` | playlist, track, playback target, 원본 metadata |

### 5-3. ISRC 누락 처리

ISRC가 없는 track은 아래 순서로 보강합니다.

1. IFPI ISRC Search 기준으로 title, artist, duration 후보를 확인합니다.
2. MusicBrainz recording/release 후보로 버전과 발매 문맥을 좁힙니다.
3. 필요하면 Wikidata와 Discogs로 외부 identifier와 release context를 대조합니다.
4. confidence가 충분할 때만 ISRC를 채웁니다.
5. 애매하면 비워두고 `metadata_confidence=low`, `identity_status=unresolved`로 남깁니다.

잘못된 ISRC는 추천과 cross-platform matching 전체를 오염시키므로 추정값을 저장하지 않습니다.

## 6. Feature Store

초기 feature store는 PostgreSQL에 둡니다. 별도 벡터 DB나 feature store 제품은 첫 모델 검증 후 필요할 때 도입합니다.

초기 feature groups:

- `item_identity_features`
  - internal track id, provider ids, ISRC, MusicBrainz id, Wikidata id
- `item_metadata_features`
  - artist, album, release year, country, label, tags, genres
- `item_audio_features`
  - danceability, energy, valence, acousticness, tempo 등 provider-neutral snapshot
- `user_sequence_features`
  - 최근 N개 event, event type, timestamp bucket, context
- `user_profile_features`
  - 선호 artist/genre/tag, platform mix, novelty tolerance, skip tendency
- `playlist_features`
  - coherence, diversity, duplicate ratio, coverage, confidence

feature는 원본 테이블에서 직접 조립하지 않고, 모델 입력용 snapshot으로 materialize합니다. 이렇게 해야 학습 재현성과 추천 결과 설명이 가능합니다.

## 7. 추천 파이프라인

### 7-1. Candidate Generation

후보군은 여러 경로에서 가져옵니다.

- EMS public playlist pool
- 사용자의 PMS library와 유사한 artist/genre/tag track
- Last.fm/ListenBrainz 기반 장기 취향 후보
- 최근 많이 들은 artist의 관련 track
- 낮은 노출 빈도의 novelty 후보

EMS 검색 결과는 일회성 preview로 버리지 않고 EMS POOL에 먼저 적재한 뒤 `search_pool` 소스로 EMS candidate pool에 편입합니다. 검색 직후에는 playlist/track 메타데이터를 `ems_pool_*` 테이블에 저장하고, 백그라운드 worker가 event/scheduler 기반으로 EMS 본 테이블에 반영합니다. 사용자가 검색 playlist detail을 열면 해당 track 목록과 playlist-track 링크를 보강합니다. POOL 진행률과 실패 항목은 관리자 전용 `/ems/pool-admin` 화면에서 확인합니다.

### 7-2. Ranking

초기 ranking은 규칙 기반 baseline과 모델 점수를 결합합니다.

추천 점수 초안:

```text
score =
  affinity_score
  + novelty_bonus
  + coherence_bonus
  + diversity_bonus
  - redundancy_penalty
  + confidence_bonus
```

Transformer model은 `affinity_score`와 next-item probability를 제공하고, playlist evaluator는 나머지 품질 축을 보정합니다.

### 7-3. Playlist 평가 6축

| Axis | 의미 |
|---|---|
| `Affinity` | 사용자 취향 적합도 |
| `Novelty` | 너무 익숙한 곡만 반복하지 않는 정도 |
| `Coherence` | playlist 내부 mood/genre/tempo 흐름 |
| `Diversity` | artist, genre, era, source의 적절한 다양성 |
| `Redundancy Penalty` | 중복 artist/track/버전 과다 감점 |
| `Confidence` | metadata, identity, audio feature coverage 신뢰도 |

추천 playlist는 평균 track 점수만으로 통과시키지 않고 이 6축을 함께 통과해야 합니다.

## 8. 학습 방식

### 8-1. Offline Batch

주기적으로 전체 interaction snapshot을 사용해 공통 모델을 학습합니다.

초기 학습 task:

- next-item prediction
- masked-item prediction
- positive/negative feedback ranking
- playlist continuation

초기 loss:

- sampled softmax 또는 cross entropy
- pairwise ranking loss
- feedback label weighted loss

### 8-2. Near-real-time Personalization

사용자 행동이 들어올 때마다 전체 모델을 다시 학습하지 않습니다.

빠르게 반영할 항목:

- user embedding
- user preference vector
- ranking head 또는 reranker feature
- 최근 session context

batch 재학습 대상:

- item embedding
- sequence encoder
- metadata encoder
- 전체 ranking calibration

## 9. 평가 기준

### 9-1. Offline Metrics

- `HitRate@K`
- `NDCG@K`
- `MRR@K`
- `Recall@K`
- `Coverage`
- `Novelty`
- `Diversity`
- `Calibration`

### 9-2. Playlist Quality Metrics

- 6축 playlist score
- metadata confidence 평균
- audio feature coverage
- duplicate artist/track ratio
- source platform balance

### 9-3. Product Metrics

- 추천 track 저장률
- 추천 playlist 저장률
- 추천 후 재생 완료율
- skip rate 감소
- 반복 재생 증가
- GMS 평가 참여율
- PMS playlist 추가율

## 10. 구현 단계

### Phase 1. 학습 데이터 기반

목표: 모델 학습에 필요한 canonical event와 feature snapshot을 만든다.

작업:

- `user_music_event` schema 추가
- player, PMS, EMS, GMS 행동 이벤트 적재
- recommendation snapshot schema 추가
- metadata confidence field 추가
- EMS/PMS audio feature coverage를 feature로 연결

완료 기준:

- 한 사용자의 300곡 playlist와 이후 행동이 시간순 sequence로 재현된다.
- 추천 결과와 사용자 반응을 같은 `recommendation_id`로 추적할 수 있다.

### Phase 2. Metadata Normalization

목표: cross-platform track identity를 안정화한다.

작업:

- ISRC 보강 queue 설계
- MusicBrainz/Wikidata/Discogs identity candidate 저장
- identity confidence rule 정의
- 잘못된 identifier rollback/검토 상태 추가

완료 기준:

- ISRC 없는 track도 `unresolved` 상태로 추적된다.
- 확실한 경우에만 canonical identifier가 채워진다.

### Phase 3. Baseline Recommender

목표: Transformer 이전에 제품 루프를 닫는 baseline 추천을 만든다.

작업:

- user profile vector 산출
- EMS candidate affinity scoring
- playlist 6축 evaluator 구현
- GMS recommendation snapshot 저장
- 평가/저장/스킵 feedback 환류

완료 기준:

- GMS가 EMS pool에서 사용자별 추천 후보를 생성한다.
- 추천 결과에 대해 왜 추천됐는지 설명 가능한 점수가 남는다.

### Phase 4. Sequential Model MVP

목표: Hugging Face/PyTorch 기반 sequential recommender를 AI service에 붙인다.

작업:

- sequence dataset exporter
- item vocabulary와 tokenization
- SASRec MVP 학습
- inference endpoint
- offline metric report

완료 기준:

- AI service가 사용자 id와 candidate set을 받아 top-K 점수를 반환한다.
- baseline보다 offline metric 또는 product proxy metric이 개선된다.

### Phase 5. 개인화 갱신

목표: 새 행동이 추천에 빠르게 반영되게 한다.

작업:

- user embedding update job
- recent session reranking
- feedback label weighting
- cold-start user fallback

완료 기준:

- 새 저장/스킵/반복 재생 이벤트가 다음 추천 batch에 반영된다.
- 전체 모델 재학습 없이 사용자별 rank가 바뀐다.

### Phase 6. 운영화

목표: 추천 품질과 데이터 품질을 지속적으로 감시한다.

작업:

- model versioning
- training dataset snapshot versioning
- feature coverage dashboard
- recommendation audit log
- drift and stale metadata detection

완료 기준:

- 어떤 모델 버전이 어떤 데이터로 어떤 추천을 냈는지 추적 가능하다.
- 품질 저하 시 이전 모델 또는 baseline으로 되돌릴 수 있다.

## 11. API/서비스 경계

### Spring API

책임:

- 사용자 계정, platform credential, PMS/EMS/GMS 데이터 영속화
- 행동 이벤트 수집
- metadata enrichment orchestration
- recommendation snapshot 저장
- AI service 호출과 결과 API 제공

### FastAPI AI Service

책임:

- feature dataset validation
- model training
- inference
- offline evaluation report
- embedding/vector artifact 관리

초기에는 Spring API가 후보군을 만들고, AI service가 후보군을 scoring합니다. 후보 생성까지 AI service로 넘기는 것은 2차 단계로 둡니다.

## 12. 위험과 대응

| Risk | 대응 |
|---|---|
| 개인 데이터 300곡만으로 과적합 | 공통 baseline, metadata feature, EMS candidate pool을 함께 사용 |
| ISRC 오염 | confidence 기준 미달 시 비워두기 |
| audio feature coverage 부족 | feature missing indicator를 모델 입력에 포함 |
| provider API 제약 | 플랫폼 데이터와 canonical PMS 모델 분리 |
| 검색 결과 오염 | 검색 결과는 검증 전 canonical table에 저장하지 않음 |
| 추천 설명 불가 | recommendation snapshot에 axis score와 model version 저장 |

## 13. 첫 개발 체크리스트

개발은 모델부터 시작하지 않습니다. 먼저 비교 가능한 baseline과 학습 가능한 event log를 고정합니다.

우선순위:

1. `user_music_event` 최소 schema와 적재 API 설계
2. 공통 player event 적재
3. PMS save/add-to-playlist event 적재
4. GMS recommendation snapshot schema 설계
5. recommendation snapshot에 `model_version`, `feature_snapshot_id`, axis score 저장
6. baseline affinity scorer 작성
7. playlist 6축 evaluator 작성
8. EMS candidate feature snapshot exporter 작성
9. AI service dataset export/import harness 작성
10. SASRec MVP 학습 스크립트 작성
11. offline metric report 생성

첫 구현 순서는 아래로 고정합니다.

```text
event schema
-> recommendation snapshot
-> baseline scorer
-> playlist evaluator
-> dataset exporter
-> SASRec MVP
```

### 13-1. Baseline First

SASRec/BERT4Rec 이전에 `metadata + behavior weight + playlist 6축 evaluator` 기반 baseline을 먼저 만듭니다.

이 baseline은 두 가지 역할을 합니다.

- 모델 성능 비교 기준
- Transformer가 아직 학습되지 않았을 때도 동작하는 제품 fallback

### 13-2. Event Logging First

추천 모델의 1차 개발 대상은 모델 코드가 아니라 행동 로그입니다.

초기 필수 이벤트:

- `play_started`
- `play_paused`
- `play_resumed`
- `play_completed`
- `skip_next`
- `skip_previous`
- `replay`
- `track_saved`
- `added_to_playlist`
- `recommendation_liked`
- `recommendation_rejected`

스킵과 무반응은 강한 부정으로 바로 해석하지 않습니다. `skip_next`와 `stopped_midway`는 약한 부정으로 두고, `ignored_recommendation`은 더 약한 신호로 처리합니다.

### 13-3. Snapshot First

추천 결과는 반드시 snapshot으로 저장합니다.

필수 snapshot fields:

- `recommendation_id`
- `user_id`
- `candidate_track_id`
- `candidate_playlist_id`
- `model_version`
- `feature_snapshot_id`
- `affinity_score`
- `novelty_score`
- `coherence_score`
- `diversity_score`
- `redundancy_penalty`
- `confidence_score`
- `rank`
- `created_at`

이 snapshot이 있어야 사용자가 추천을 평가했을 때 어떤 모델과 feature가 그 추천을 만들었는지 추적할 수 있습니다.

### 13-4. Cold Start

신규 사용자는 행동 로그가 적습니다. 따라서 초기 추천은 아래 fallback을 사용합니다.

- imported playlist metadata
- artist/tag affinity
- provider-neutral audio feature coverage
- Last.fm signal
- EMS public playlist pool

행동 로그가 쌓이면 sequential model score 비중을 점진적으로 높입니다.

### 13-5. 현재 구현 시작점

2026-05-11 기준 1차 개발은 행동 로그 적재부터 시작했습니다.

구현된 범위:

- `V23__create_user_music_event.sql` migration 추가
- `UserMusicEventService`와 `/api/v1/recommendations/events` 적재 API 추가
- local profile용 in-memory store와 non-local profile용 JPA store 추가
- 웹 플레이어에서 `play_started`, `play_paused`, `play_resumed`, `play_completed`, `skip_next`, `skip_previous` 적재
- PMS 개인 플레이리스트 저장 시 `added_to_playlist` 적재
- GMS feedback 저장 시 `recommendation_liked`, `recommendation_rejected`, `track_saved`, `ignored_recommendation` 적재
- `V24__create_recommendation_snapshot.sql` migration 추가
- GMS preview 결과를 `recommendation_snapshot`에 `gms-baseline-v1` snapshot으로 저장
- snapshot에는 `affinity_score`, `novelty_score`, `coherence_score`, `redundancy_penalty`, `confidence_score`, `rank`, `feature_snapshot_id`를 저장
- `PlaylistQualityEvaluator` 추가
- GMS preview 후보 리스트 전체를 기준으로 playlist-level `coherence_score`, `diversity_score`, `redundancy_penalty`를 계산해 snapshot에 반영
- `GET /api/v1/recommendations/datasets/users/{userId}/sequence` dataset exporter 추가
- exporter는 `user_music_event`와 `recommendation_snapshot`을 시간순 sequence item으로 합쳐 AI service 학습/검증 입력 경계로 제공
- AI service `POST /v1/recommendations/datasets/validate` dataset import harness 추가
- import harness는 exporter payload의 count 정합성, source id 연결, sequence 정렬, positive/negative signal, unique track coverage, training readiness를 검증
- AI service `POST /v1/recommendations/datasets/sasrec/prepare` SASRec MVP dataset 준비 경로 추가
- SASRec 준비 경로는 sequence payload를 `track_id -> item_index` vocabulary와 next-item training window로 변환
- AI service `POST /v1/recommendations/datasets/sasrec/offline-report` offline metric report 경로 추가
- offline report는 leave-last-out 방식으로 recency baseline의 `HitRate@K`, `MRR@K`, `NDCG@K`를 계산해 PyTorch SASRec MVP의 비교 기준으로 사용
- AI service `POST /v1/recommendations/datasets/sasrec/train` PyTorch SASRec MVP 학습 경로 추가
- SASRec MVP는 1-layer Transformer encoder로 next-item prediction을 학습하고, final loss와 leave-last-out metric을 반환
- SASRec MVP 학습 결과를 `AI_MODEL_ARTIFACT_DIR/sasrec/{model_version}/model.pt`와 `metadata.json`으로 저장
- 학습 응답은 recency baseline metric, SASRec metric, metric delta, artifact path를 함께 반환
- Spring API `POST /api/v1/recommendations/datasets/users/{userId}/sasrec/train` 경로 추가
- 이 경로는 Spring dataset exporter 결과를 AI service `/v1/recommendations/datasets/sasrec/train`에 그대로 전달해 사용자별 SASRec MVP artifact를 생성
- AI service `POST /v1/recommendations/datasets/sasrec/rank` 후보 랭킹 경로 추가
- rank 경로는 저장된 `model.pt`와 `metadata.json`을 로드해 context track 기준 candidate track logits를 정렬
- AI service `GET /v1/recommendations/datasets/sasrec/models/latest` 최신 SASRec artifact 조회 경로 추가
- Spring API GMS preview가 `AI_SASREC_MODEL_VERSION` 설정 시 AI service `POST /v1/recommendations/datasets/sasrec/rank`를 호출해 PMS 라이브러리 기반 playable 후보를 재정렬
- `AI_SASREC_MODEL_VERSION` 미설정 시 Spring API가 사용자 기준 최신 SASRec artifact를 AI service에서 조회해 재정렬에 사용
- SASRec 재정렬은 새 플랫폼 검색이나 외부 변환 없이, 이미 DB/PMS에 있는 candidate track id와 request seed/context track id만 사용
- SASRec raw score는 GMS affinity score와 70:30으로 혼합하고, ranking 실패 또는 model 미설정 시 기존 GMS playable 후보 정렬로 유지
- SASRec 적용 시 GMS response context engine과 recommendation snapshot `model_version`에 `sasrec:{model_version}`을 남겨 이후 학습 데이터에서 모델 기여도를 추적
- 공통 플레이어 TIDAL repeat-one 자동 재시작 시점에 `replay` 이벤트를 적재해 반복 재생 신호를 학습 데이터에 포함
- 공통 플레이어 Spotify 재생 경로에서 SDK state change의 직전/현재 비교(직전 position이 duration의 95% 이상)로 `play_completed`를 적재하고, 같은 트랙이 0초 근처에서 재개되면 `replay`도 적재
- EMS playlist detail, EMS 검색 playlist detail, PMS playlist detail 화면의 track row에 하트 모양 like 버튼을 노출하고, 누르면 `track_saved` 이벤트를 `source_space=ems` 또는 `source_space=pms`로 적재 (현재는 학습 신호용. 라이브러리 저장 흐름과는 별개 — PMS personal playlist 추가는 `added_to_playlist`로 분리)
- 관리자 전용 `GET /api/v1/recommendations/admin/playlist-quality/recent` endpoint를 통해 recent recommendation snapshot을 `recommendation_id`로 그룹화하고 affinity/novelty/confidence는 그룹 평균, coherence/diversity/redundancy는 playlist-level 값으로 6축 quality summary를 조회
- 관리자 전용 `/recommendations/quality-admin` 화면에 6축 평균 카드와 최근 추천 playlist별 점수 테이블을 표시 (Sidebar admin 메뉴 등록)
- AI service SASRec MVP 학습 응답에 `qualification`(qualified bool, threshold, reason) 자동 평가를 추가하고 Spring API/training 응답까지 통과해, recency baseline 대비 회귀가 없을 때만 qualified=true로 표시 (회귀 시 warnings에도 reason 적재)
- AI service `SasrecModelRegistryService`에 `registry.json` 기반 promote/disable/rollback/latest 정책을 추가하고, `POST /v1/recommendations/datasets/sasrec/models/{version}/promote`, `.../disable`, `POST .../models/rollback` 엔드포인트로 노출 (latest_model은 promoted 우선 + disabled 제외 시간순 정렬)
- Spring API에 admin 전용 통과 endpoint(`GET /api/v1/recommendations/admin/sasrec/models/latest`, `POST .../promote`, `.../disable`, `POST .../rollback`)와 frontend `/recommendations/sasrec-admin` 관리자 화면을 추가해 active model 확인 + promote/disable/rollback을 ConfirmDialog로 수행

- Phase 2 5차: 사용자별 모델 단계(cold-start/baseline/personalized) 정의 + admin 조회 endpoint 추가. `cold-start` = PMS user library 비어 있음, `baseline` = import 완료 후 SASRec promoted model 없음(기본 baseline 흐름), `personalized` = SASRec promoted active model 존재. `GET /api/v1/recommendations/admin/sasrec/models/users/{targetUserId}/status?user_id={adminUserId}` 가 PMS track 수, active model_version, 최근 학습 이력, 총 event 수, 마지막 학습 이후 event delta 를 반환. 관리자 전용 `/recommendations/sasrec-admin` 화면에 "Other user lookup" 섹션 추가. 일반 사용자에게는 모델 상태가 노출되지 않으며 결과(추천 곡)만 보인다.
- GMS playlist 흐름 1차 진입: `GET /api/v1/gms/playlists/preview?user_id&limit` endpoint 추가. 사용자가 cold-start 면 409 반환, 그렇지 않으면 EMS 본 테이블에 적재된 공개 playlist 중 사용자 PMS user_library 의 artist 집합과 curator/title 일치 + audio feature coverage + 트랙 수 기반의 단순 affinity score 로 정렬해 상위 N(기본 12, max 50) 후보를 반환.
- GMS playlist 흐름 2차: `POST /api/v1/gms/playlists/{playlistId}/save?user_id` + 선택 body `{title}` endpoint 추가. EMS playlist 를 PMS personal playlist(`gms-ems-{emsPlaylistId}` deterministic id) 로 import 한다. 같은 사용자가 같은 EMS playlist 를 다시 save 하면 기존 personal playlist 를 재사용해 누락된 track 만 append. 매 track 마다 `added_to_playlist` 이벤트(source_space=gms) 적재. cold-start / 빈 playlist / 미존재 playlist 는 각각 409/409/404.
- GMS playlist 흐름 3차 (Frontend): `/gms-playlists` 페이지 추가. `fetchGmsPlaylistPreview(userId, limit)` 로 후보를 로드해 cover/curator/affinity/confidence/track count 카드 그리드로 노출하고, 각 카드의 "Save to PMS" 가 공통 `ConfirmDialog` 로 확인을 받은 뒤 `saveGmsPlaylistToPms(playlistId, userId)` 를 호출한다. 저장 직후 동일 playlist 카드는 "Saved" 상태로 잠긴다. Sidebar workspace 메뉴에 "GMS Playlists" 등록.
- GMS playlist 흐름 4차 (6축 ranking): `GmsPlaylistPreviewService`가 각 후보의 link된 track 리스트를 `PlaylistQualityEvaluator`에 통과시켜 coherence/diversity/redundancy 를 산출하고, `RecommendationAxisEvidenceBuilder`로 affinity/novelty/coherence/diversity/redundancy/confidence 6축 evidence를 만든다. novelty 는 candidate playlist 의 unique artist 가운데 사용자 PMS user_library 에 없는 비율로 계산한다. composite score(`0.45*affinity + 0.15*novelty + 0.15*coherence + 0.10*diversity + 0.10*confidence - 0.05*redundancy`) 기준으로 후보를 재정렬하고, 응답 `axis_evidence` + `composite_score` 를 `/gms-playlists` 카드 아래 evidence 패널과 Composite metric으로 표시한다.
- GMS playlist 흐름 5차 (Preview before save): `/gms-playlists` 각 후보 카드에 "Preview tracks" 버튼 추가. 클릭 시 `fetchEmsCollectedPlaylistDetail(playlistId)` 로 트랙 목록을 로드한 모달이 열리며, 모달 안에서 트랙별/전체 재생을 기존 `playQueue` 로 시청할 수 있고 그 자리에서 바로 PMS 저장도 가능하다. 사용자는 저장 결정 전에 표지/제목만 보는 게 아니라 실제 트랙을 들어볼 수 있다.
- GMS playlist 흐름 6차 (review/remove): "Preview tracks" 모달에 play/pause, prev/next, seek, volume이 있는 미니 플레이어와 저장 전 track 제외(`excluded_track_ids`)를 추가했다. 카드 단위 `Remove from GMS`는 `POST /api/v1/gms/playlists/{playlistId}/dismiss?user_id`로 `ignored_recommendation` playlist 이벤트를 남기고, preview 생성 시 최근 ignored GMS playlist와 이미 PMS에 저장된 `gms-ems-*` playlist를 후보에서 제외한다. EMS 원본 playlist/track row는 삭제하지 않는다.
- Phase 6 feature coverage dashboard 1차: 관리자 전용 `GET /api/v1/recommendations/admin/feature-coverage?user_id&target_user_id` endpoint 가 PMS user library, EMS collected pool, learning data coverage를 한 응답으로 집계한다. Frontend `/recommendations/feature-coverage` 화면은 PMS audio/ISRC/playback target, EMS audio/ISRC/canonical link, source platform별 coverage, user event/recommendation snapshot 수를 표시한다. EMS repository가 없는 profile에서는 degraded warning을 노출해 저장소 경계 부재를 숨기지 않는다.
- Phase 6 recommendation audit log 1차: `recommendation_audit_log` 테이블(V37)과 local/JPA store를 추가했다. GMS preview 생성 시 `preview_generated` 로그가 user/recommendation/request id, item count, model version, SASRec 적용 여부, fallback reason을 기록하고, GMS feedback 저장 시 `feedback_recorded` 로그가 feedback type과 target track/playlist를 기록한다. 관리자 전용 `GET /api/v1/recommendations/admin/audit-log/recent?user_id&target_user_id&limit` endpoint 로 최근 감사 로그를 조회한다.
- Phase 6 drift / stale metadata detection 1차: `DriftSignalEvaluator`를 추가해 feature coverage 응답에 drift signal 목록을 같이 반환한다. PMS audio/playback/ISRC, EMS source별 audio/ISRC/canonical link coverage, learning data event 수가 `app.recommendation.drift.*` 임계치 미만이면 `category/severity(warn|info)/target_scope/message/actual_value/threshold/sample_size`를 갖는 신호로 변환된다. EMS source 단위 신호는 `ems-min-track-count`(기본 20)과 `ems-canonical-min-track-count`(기본 50) 가드로 표본이 부족하면 억제된다. `/recommendations/feature-coverage`에 status banner로 노출된다.
- Phase 6 drift / stale metadata detection 2차: feature coverage 응답에 PMS/EMS `stale_audio_feature_count`, `stale_audio_feature_ratio`, `latest_audio_resolved_at`을 추가하고, `audio_resolved_at`이 `app.recommendation.drift.audio-stale-days`(기본 90일) 기준보다 오래된 audio feature 비율이 `audio-stale-max-ratio`(기본 25%)를 넘으면 `audio_stale` drift signal을 만든다. 또한 최근 EMS acquisition run 20개를 집계해 `skipped_article_count + skipped_seed_count` 비율이 `ems-acquisition-skip-max-ratio`(기본 80%)를 넘으면 `ems_acquisition_skips` signal을 노출한다. Frontend `/recommendations/feature-coverage`는 Stale Audio와 EMS Acquisition 패널/테이블 컬럼을 표시한다.
- Phase 6 audio feature completion 운영 가시화: feature coverage 응답에 `audio_feature_completion` 블록을 추가했다. 최근 completion job 500개 기준 `recent_job_count`, status count, `unresolved`/`retry_wait`/`failed` top reason 분포를 반환하고 `/recommendations/feature-coverage` 화면의 Audio Completion 패널과 status/reason table에 표시해 ReccoBeats 실패, Last.fm partial inference, 후속 LLM/search 대기량을 같은 운영 맥락으로 볼 수 있게 한다.
- Audio feature completion Phase 4 1차: `services/ai`의 `POST /v1/audio-features/infer`가 OpenAI web search + structured output으로 `llm_search_inferred` estimate를 생성한다. Spring worker는 ReccoBeats/Last.fm 이후 이 endpoint를 호출하고, evidence non-empty + confidence gate + 필수 numeric feature 완비일 때만 PMS/EMS snapshot과 `track_audio_feature_evidence` result를 저장한 뒤 job을 `completed`로 바꾼다.
- Phase 5 sub-item 1 (user embedding update job) 1차: `user_personalization_profile`(V38) 테이블과 `UserPersonalizationProfileStore`(in-memory + JPA) + `UserPersonalizationProfileService.recompute(userId)`를 추가. 최근 user_music_event 를 행동 가중치로 합산해 top artist / top source platform 신호를 score+signalCount로 저장한다. SASRec sequence encoder 재학습은 비싸지만 이 프로필은 가볍고 자주 갱신할 수 있어, 이미 반영된 reranker/feedback weighting/cold-start fallback의 입력이 된다. 관리자 전용 `GET/POST /api/v1/recommendations/admin/personalization-profile[/recompute]` endpoint와 `/recommendations/sasrec-admin` Personalization Profile 패널로 운영자가 수동 트리거/조회 가능. reranker 통합은 완료됐고, 프로필 자동 recompute scheduler는 필요 시 별도 운영 강화로 둔다.
- Phase 5 sub-item 2 (recent session reranking) 1차: `RecommendationReranker`를 추가해 GMS preview 응답 직전에 user personalization 프로필로 candidate를 재정렬한다. artist 매칭 시 `score * (1 + α * normalized_artist_score)` 부스트(α 기본 0.3, `app.recommendation.rerank.artist-boost-weight`), source platform 매칭은 약한 보조 부스트(β 기본 0.1). 재정렬 후 rank 1..N으로 다시 매기고, 응답 `warnings`에 `"Session reranked N candidate(s) via personalization profile"` 메모를 남긴다. 프로필이 없거나 비어 있으면 no-op이라 cold-start 흐름과 충돌하지 않는다.
- Phase 5 sub-item 3 (feedback label weighting) 1차: `EventSignalWeights` @Component를 단일 source of truth로 추가했다. 이전에는 `UserMusicEventService`(저장 시)와 `UserPersonalizationProfileService.signalWeight`(fallback)가 각각 다른 가중치 맵을 갖고 있고 어휘도 불일치(`replay` vs `repeat_played`, `skip_next` vs `skipped_early`)했다. 두 서비스 모두 canonical 가중치 표를 공유하고, 별칭(`repeat_played→replay`, `skipped_early→skip_next`, `recommendation_saved→track_saved`)은 자동 정규화되어 누락 신호가 사라진다. 가중치는 plan §4-2 "신호 해석"을 따름(강한 긍정 +2.0 / 긍정 +1.0~1.5 / 약한 긍정 +0.3 / neutral 0 / 약한 부정 -0.1~-0.25 / 강한 부정 -2.0).
- Phase 5 sub-item 4 (cold-start user fallback) 1차: `ColdStartFallbackService`를 추가해 PMS user library가 비어 있는 사용자에 대해 EMS 본 테이블의 최근 audio-feature-filled 트랙을 fallback 후보로 제공한다. `GmsRecommendationPreviewService`는 AI가 item을 반환했지만 PMS 매핑이 비어 있을 때 이전에는 `IllegalArgumentException`("Import a real playlist...")으로 끊었지만, 이제는 cold-start 사용자라면 fallback 아이템으로 응답한다. 사용자의 `preferredPlatformId`를 우선 사용하고 그 플랫폼이 비어 있으면 다른 플랫폼으로 fallback. response warnings에 `"Cold-start fallback applied: N EMS pool track(s)..."` 메모, audit log `fallback_reason='cold_start_pms_empty'` 기록. `app.recommendation.cold-start.fallback-limit`로 갯수 튜닝(기본 12). EMS pool도 비어 있으면 기존 오류로 끊는다(데이터 부재 case). `/gms-preview`는 PMS playlist가 없어도 EMS fallback preview를 요청할 수 있고, fallback 결과에는 `/pms` import CTA를 노출해 사용자를 실제 PMS import로 유도한다.

§13-5 첫 단계 운영 범위가 닫힘. 아래 항목은 이미 반영된 강화 이력이다.

- 사용자별 음악 학습 모델의 sequence encoder 재학습 자동화 — 1차 적용: `RecommendationModelTrainingService.autoTrainAndPromote(adminUserId)`가 train 후 qualification=true 이면 `SasrecModelRegistryAdminService.promote`를 호출해 active model 로 자동 승격. 관리자 전용 `POST /api/v1/recommendations/admin/sasrec/models/auto-train` endpoint와 `/recommendations/sasrec-admin` 화면의 Auto-Train 버튼으로 노출.
- 2차 적용: `SasrecAutoTrainScheduler` 추가. `app.recommendation.sasrec.auto-train.enabled=true` + `...user-id` 설정 시 `fixed-delay-ms`(기본 24시간) 주기로 지정 user 의 모델을 자동 학습/promote. 기본 disabled.
- 3차 적용: scheduler 가 `user-id` 미설정 시 `UserMusicEventStore.findActiveUserIds(now - active-window-hours, max-active-users)` 로 활성 사용자를 자동 추출하고 각자 학습한다. event 수 기반 drift 도 추가됨 — `min-event-delta`(기본 50) 이상일 때만 다음 학습이 통과.
- 4차 적용: 학습 이력 영속 저장. 새 `sasrec_auto_train_log` 테이블(V26)과 `SasrecAutoTrainLogStore` (in-memory + JPA 구현) 추가. scheduler 의 in-memory `lastTrainStateByUser` 가 store 호출로 대체되어 서버 재시작 후에도 drift 판단이 유지된다. 각 tick 결과(user_id, trained_at, event_count_at_train, model_version, qualified, promoted, summary)가 영속 기록된다.
- 5차 적용: admin 인증 주체와 학습 대상 user 를 분리. 관리자 수동 endpoint 는 `target_user_id` query 를 받아 다른 사용자의 SASRec 모델도 학습/promote 할 수 있고, scheduler 는 관리자 권한 검증 경계가 아닌 내부 `RecommendationModelTrainingService`를 직접 호출해 활성 일반 사용자 모델을 정상 학습한다. `/recommendations/sasrec-admin` 의 Other user lookup 에 `Train Target` 버튼 추가.
- 6차 적용: training dataset snapshot versioning. Spring dataset exporter 가 `recommendation-sequence-v1` + deterministic `sha256:` fingerprint 를 `summary.dataset_version`/`summary.dataset_fingerprint` 로 생성하고, AI training artifact `metadata.json`, model registry latest 응답, `sasrec_auto_train_log`(V36), `/recommendations/sasrec-admin` Active Model/Auto-Train/Other user lookup 화면에 노출한다. fingerprint 는 generated_at 을 제외하고 user, export limit, event/snapshot/sequence 내용을 기준으로 계산해 같은 학습 입력을 재식별할 수 있게 한다.
- Phase 2 metadata normalization 1차 진입: `MusicBrainzClient` 추가 (recording 검색 read-only, 익명 rate limit 준수용 User-Agent 헤더). 관리자 전용 `GET /api/v1/recommendations/admin/metadata/musicbrainz/recordings?user_id&title&artist&limit` endpoint 가 title + artist 로 MBID/ISRC/release 후보를 반환.
- Phase 2 2차: `track_identity_candidate` 테이블(V27)과 `TrackIdentityCandidateStore`(in-memory + JPA 구현). lookup endpoint 에 `persist=true` 옵션 추가 시 응답 후보를 각각 mbid/isrc kind 로 저장한다. 관리자 전용 `GET .../candidates?status&limit`, `POST .../candidates/{id}/accept`, `POST .../candidates/{id}/reject` endpoint.
- Phase 2 3차: 관리자 전용 `/recommendations/metadata-admin` 화면 추가. title/artist/limit/persist 입력으로 MusicBrainz lookup 결과 표시 + `track_identity_candidate` 목록을 status 필터(pending/accepted/rejected/all)로 조회, 각 row 에서 accept/reject를 `ConfirmDialog` 로 처리. Sidebar admin 메뉴에 "Metadata Normalize" 등록.
- Phase 2 4차: confidence 기반 bulk auto-accept. `POST /admin/metadata/candidates/auto-accept?user_id&min_score&limit&source&candidate_kind` endpoint 가 status=pending 인 후보 중 `candidate_score >= min_score` (기본 0.95) 인 항목을 일괄 accept (resolved_by=admin, notes="auto-accepted (score>=...)"). Frontend `/recommendations/metadata-admin` 에 threshold input + Auto-accept 버튼 + 결과 요약 inline notice 추가.
- Phase 2 5차: accepted ISRC candidate 적용 경계 추가. `POST /admin/metadata/candidates/apply-accepted-isrcs?user_id&limit` endpoint 가 status=accepted, candidate_kind=isrc 인 후보를 query title/artist 기준 EMS track 과 매칭하고, 기존 `isrc` 가 비어 있으면 적용한다. 이미 다른 ISRC 가 있으면 덮어쓰지 않고 `conflict`, 매칭 track 이 없으면 `no_match`, 적용 완료는 `applied` 로 candidate 상태를 남긴다.
- Phase 2 6차: `/recommendations/metadata-admin` 화면에 `Apply ISRCs` 버튼과 `applied/no_match/conflict` status 필터를 추가해 운영자가 accepted 후보를 실제 EMS track row 에 반영하고 결과를 확인할 수 있게 했다.
- Phase 2 7차: `MetadataNormalizationApplyScheduler` 추가. `app.recommendation.metadata.apply-accepted-isrcs.enabled=true` 와 `...admin-user-id` 설정 시 accepted ISRC 후보 적용을 주기적으로 실행한다. 기본값은 disabled 이며, 수동 endpoint 와 같은 `MetadataNormalizationAdminService.applyAcceptedIsrcCandidates(...)` 경계를 재사용한다.
- Phase 2 8차: applied ISRC rollback/review 경계와 구조화 감사 이력 추가. `V28__create_track_identity_candidate_audit.sql` 이 candidate별 apply/conflict/no_match/rollback/review_required 이력을 track id, 이전 ISRC, 새 ISRC, operator, timestamp 와 함께 저장한다. `POST /admin/metadata/candidates/{candidateId}/rollback-applied-isrc?user_id` endpoint 는 audit table 에 기록된 applied EMS track id 에 대해서만, 현재 `isrc` 가 candidate 값과 일치할 때 clear 한다. clear 되면 `rolled_back`, audit 이 없거나 현재 값이 달라 자동 rollback 이 불가능하면 `review_required` 로 남긴다. `GET /admin/metadata/candidates/{candidateId}/audit?user_id` endpoint 와 Frontend `/recommendations/metadata-admin` 의 `Audit` 패널로 candidate별 이력을 조회할 수 있다. 같은 화면에는 applied ISRC 후보 전용 `Rollback` 버튼과 `rolled_back/review_required` 필터도 추가했다.
- Phase 2 9차: canonical track identity schema/store 기반 추가. `V29__create_canonical_track_identity.sql` 이 `canonical_track` 과 `canonical_track_identity` 를 만들고, `CanonicalTrackIdentityStore` + local/JPA 구현이 ISRC/MBID/provider id 같은 외부 식별자를 normalized source/kind/value 기준으로 active identity 에 upsert 할 수 있게 했다.
- Phase 2 10차: applied candidate -> canonical identity 승격 경계 추가. `POST /api/v1/recommendations/admin/metadata/candidates/{candidateId}/promote-canonical?user_id=...` endpoint 가 `status=applied` 인 ISRC/MBID candidate 를 `canonical_track_identity` 로 upsert 하고, candidate 상태는 유지한 채 `track_identity_candidate_audit` 에 `canonical_promote` 이력을 남긴다. Frontend `/recommendations/metadata-admin` 에 applied candidate 전용 `Promote` 버튼과 canonical track/identity 결과 요약을 추가했다.
- Phase 2 11차: EMS/PMS track row -> canonical track 연결 추가. `V30__link_tracks_to_canonical_track.sql` 이 `ems_collected_track`, `pms_imported_track`, `pms_user_track` 에 nullable `canonical_track_id` FK 를 추가한다. canonical promotion 이 ISRC candidate 에서 실행되면 같은 ISRC 를 가진 EMS/PMS track row 의 비어 있는 `canonical_track_id` 를 채우고, 이미 다른 canonical track 에 묶인 row 는 덮어쓰지 않고 conflict count 로 반환한다.
- Phase 2 12차: canonical link conflict review UI 추가. `GET /api/v1/recommendations/admin/metadata/candidates/{candidateId}/canonical-link-conflicts?user_id=...` endpoint 가 promoted ISRC candidate 의 target canonical track 과 다른 `canonical_track_id` 를 가진 EMS/PMS row 를 조회한다. Frontend `/recommendations/metadata-admin` 에 applied ISRC candidate 전용 `Conflicts` 버튼과 conflict row review table 을 추가했다.
- Phase 2 13차: Wikidata·Discogs identity candidate 통합. `GET /api/v1/recommendations/admin/metadata/wikidata/entities` endpoint 는 Wikidata entity search 결과를 `wikidata_qid` candidate 로 저장하고, `GET /api/v1/recommendations/admin/metadata/discogs/masters` endpoint 는 Discogs master search 결과를 `discogs_master_id` candidate 로 저장한다. Discogs lookup 은 `DISCOGS_TOKEN` 설정이 없으면 실패하며 빈 결과나 mock 으로 대체하지 않는다. accepted external identity candidate 는 canonical identity 로 promote 할 수 있다.
- Phase 2 14차: external identity candidate quality scoring. `CandidateQualityScorer` 추가로 MusicBrainz/Wikidata/Discogs candidate 모두에 normalized 0..1 `candidate_score`를 일관 적용. title/artist Jaccard 토큰 유사도(+부분 문자열 보너스)로 title × 0.6 + artist × 0.4 base를 계산하고, MusicBrainz raw Lucene score는 0.2 weight로 blend. Wikidata description의 `song by X` 패턴과 Discogs `Artist - Title` 포맷에서 artist를 자동 추출. 같은 auto-accept threshold(`>=0.95`)가 모든 source에서 같은 의미로 동작.
- Phase 2 15차: Discogs release context enrichment. `V34__add_canonical_track_release_context.sql`로 `canonical_track`에 `release_year`/`release_country`를 nullable로 추가하고, Discogs candidate promote 시 metadata JSON에서 year/country를 파싱해 함께 적재. 동일 canonical track이 이미 있으면 fill-if-null 정책(기존 값 보존)으로 `CanonicalTrackIdentityStore.fillReleaseContextIfMissing` 호출. promote 응답과 `/recommendations/metadata-admin` 결과 요약에 release 정보가 노출됨.
- Phase 2 16차: Discogs label enrichment. `V39__add_canonical_track_release_label.sql`로 `canonical_track.release_label`을 추가하고, Discogs candidate promote 시 `/masters/{id}` detail의 `main_release`를 `/releases/{id}`로 다시 조회해 primary label을 fill-if-null로 보강한다. `DISCOGS_TOKEN` 미설정, provider 실패, main_release 누락은 빈 결과/mock으로 숨기지 않고 실패로 노출한다.
- 남은 Phase 2 강화 항목: 없음. 이후는 운영 검증과 품질 튜닝으로 분리한다.
- ~~recommendation snapshot에 explanation/axis evidence를 더해 사용자에게 노출할 reason 텍스트 안정화~~ → Spring GMS preview response의 `RecommendationItem`에 `axis_evidence`(affinity/novelty/coherence/diversity/redundancy/confidence 각 6축의 score/level/한국어 summary)를 추가하고, 프론트 GMS Preview 카드 아래에 axis별 짧은 evidence 패널을 노출.
- ~~recency baseline 대비 metric 개선 검증 자동화~~ → AI service `SasrecTrainingService`는 이미 매 학습마다 recency baseline metric을 계산해 SASRec과 비교한 `metric_delta`/`qualification`을 응답에 포함하고, qualified=true일 때만 promote 후보로 처리됨. Spring `SasrecAutoTrainScheduler`가 tick마다 Hit@K/MRR@K/nDCG@K 측정값과 baseline 비교값을 `sasrec_auto_train_log`(V35)에 영속 기록해 시계열을 보존함. `/recommendations/sasrec-admin`의 Auto-Train 결과와 Other user lookup `latest_train_log`에 SASRec vs Baseline vs Δ를 나란히 비교하는 표를 추가해, baseline 대비 개선(emerald) / 회귀(rose)를 관리자가 한눈에 확인 가능.
- ~~feature coverage dashboard~~ → 관리자 전용 `/recommendations/feature-coverage`와 Spring feature coverage endpoint로 PMS/EMS/Learning coverage를 확인 가능.
- ~~recommendation audit log~~ → GMS preview/feedback 경계에서 `recommendation_audit_log`를 기록하고 admin recent endpoint로 조회 가능.
- ~~최신 SASRec artifact 조회를 넘어서는 model registry 승격/비활성화/롤백 정책~~ → AI service registry와 Spring admin pass-through, Frontend `/recommendations/sasrec-admin`에서 active model 확인/promote/disable/rollback을 제공.

### 13-6. 현재 1차 마감 순서

문서 정리, TIDAL PMS provider 오류 경계 보강, Discogs label enrichment, cold-start import 유도 UX, EMS acquisition 운영 확장은 완료했다. 추천 모델/EMS 데이터 풀을 실제 운영 가능한 1차 제품형 상태로 닫기 위한 남은 항목은 코드 구현이 아니라 최종 검증이다.

1. **전체 회귀 테스트** — API/Web 자동 테스트를 다시 실행한다.
2. **실제 TIDAL import 회귀** — 실제 계정 PMS import와 provider 오류 경계를 확인한다.
3. **EMS acquisition runbook 재실행** — 기본 source와 가능하면 `editorial-expanded` preset run까지 확인한다.

운영 확장 구현 내용: `/api/v1/ems/acquisition/source-presets`, `source_preset` run 옵션, `editorial-expanded` preset, scheduler/source-quality/skip-drift/admin collection target UI.

## 14. 내부 참고 문서

- [PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md)
- [product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md)
- [product/MUSIC_DISCOVERY_AND_LISTENING_UX.md](/Users/woosungjo/music-space/my-forever-music/docs/product/MUSIC_DISCOVERY_AND_LISTENING_UX.md)
- [architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md)
- [api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md)
- [api/GMS_RECOMMENDATION_PREVIEW_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/GMS_RECOMMENDATION_PREVIEW_API.md)
- [api/USER_MUSIC_EVENT_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/USER_MUSIC_EVENT_API.md)
