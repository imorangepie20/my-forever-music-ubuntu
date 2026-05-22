# Recommendation Audit Log Admin API

작성일: `2026-05-22`

추천 감사 로그와 taste mode rollout 상태를 운영자가 확인하기 위한 관리자 전용 API입니다.

## 접근 권한

- `user_id`가 관리자 계정이어야 합니다.
- 현재 관리자 기준은 `jowoosungtidal@gmail.com`으로 제한합니다.
- `target_user_id`를 생략하면 `user_id` 본인의 감사 로그를 조회합니다.

## 최근 감사 로그 조회

```http
GET /api/v1/recommendations/admin/audit-log/recent?user_id={adminUserId}&target_user_id={targetUserId}&limit=50
```

응답은 최근 추천 감사 로그를 `created_at` 내림차순으로 반환합니다. `taste_mode_gate_summary`가 있으면 원본 JSON 문자열 그대로 포함됩니다.

## Taste Mode Rollout 요약

```http
GET /api/v1/recommendations/admin/audit-log/taste-mode-summary?user_id={adminUserId}&target_user_id={targetUserId}&limit=50
```

최근 감사 로그의 `taste_mode_gate_summary`를 집계합니다.

주요 응답 필드:

- `entries_analyzed`: 조회한 감사 로그 수
- `entries_with_summary`: taste mode summary를 정상 파싱한 로그 수
- `parse_error_count`: summary JSON 파싱 실패 수
- `boost_enabled_count`: `apply_ranking_boost=true`였던 로그 수
- `evaluated_total`, `eligible_total`, `dry_run_total`, `blocked_total`, `not_applicable_total`
- `boost_applied_total`, `rank_changed_total`
- `max_positive_delta`, `max_negative_delta`
- `reason_counts`: gate reason별 누적 건수
- `latest_summary`: 가장 최근 정상 summary의 원본 정보
- `recommendation`: 운영 판단 힌트

`recommendation` 값:

- `boost_active`: 실제 boost나 rank change가 관측됨
- `dry_run_only`: 후보는 있으나 실제 ranking 영향은 아직 없음
- `blocked_by_confidence`: profile confidence gate에 막히는 경향
- `blocked_by_gate`: 다른 gate reason으로 대부분 차단
- `no_taste_mode_data`: 관찰 가능한 summary가 없음
