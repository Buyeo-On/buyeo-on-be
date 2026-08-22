-- #189: 복합 조건 배지 '백제 문화유산 완주자'(30000000-0000-4000-8000-000000000007)를 지급 가능 상태로
-- 전환한다. HERITAGE_VISITED_COUNT(#185와 무관, 기존 구현)·QUIZ_CORRECT_COUNT(#185)·PHOTO_SUBMISSION_COUNT(#186)
-- 를 각각 15 이상으로 AND 판정하도록 3개 조건을 등록하고, V17에서 지급 중단으로 채워둔 retired_at을 해제한다.

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES
    ('30000000-0000-4000-8000-000000000007', 'HERITAGE_VISITED_COUNT', 15),
    ('30000000-0000-4000-8000-000000000007', 'QUIZ_CORRECT_COUNT', 15),
    ('30000000-0000-4000-8000-000000000007', 'PHOTO_SUBMISSION_COUNT', 15);

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000007';

-- V22가 '부여 마스터'(...0008)를 등록할 때 이 배지 자신을 제외한 지급 가능 배지 수를 2로 잡았지만,
-- 그 시점 기준으로 이미 V18~V21이 ...0002~...0005의 지급을 재개한 뒤였고 이번에 ...0007도 해제된다.
-- 배지 도메인 규칙(badge rules #2)에 따라 threshold는 "이 배지 자신을 제외한 현재 지급 가능한 배지 수"여야 하므로,
-- 현재 지급 가능한 ...0001~...0007 7종을 모두 획득해야 하도록 threshold를 7로 갱신한다.
UPDATE badge_conditions SET threshold = 7
WHERE badge_id = '30000000-0000-4000-8000-000000000008' AND metric_key = 'BADGE_ACQUIRED_COUNT';
