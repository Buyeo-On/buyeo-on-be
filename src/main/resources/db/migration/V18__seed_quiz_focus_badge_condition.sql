-- #188: 배지 '집중력'(60분 내 퀴즈 10개 정답)의 BadgeMetricProvider 구현이 끝나 badge_conditions를
-- 등록하고 V17에서 지급 중단으로 등록했던 retired_at을 해제한다(#163, #127).

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000003';

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES
    ('30000000-0000-4000-8000-000000000003', 'QUIZ_CORRECT_WITHIN_60_MINUTES_COUNT', 10);
