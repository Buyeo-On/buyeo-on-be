-- #186: PHOTO_SUBMISSION_COUNT BadgeMetricProvider 구현에 맞춰 '추억 수집가'
-- (30000000-0000-4000-8000-000000000005) 배지의 condition을 등록하고 지급 중단을 해제한다.

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES ('30000000-0000-4000-8000-000000000005', 'PHOTO_SUBMISSION_COUNT', 15);

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000005';
