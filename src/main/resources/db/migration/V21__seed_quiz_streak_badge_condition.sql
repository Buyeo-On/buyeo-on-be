-- #187: 퀴즈 5개 연속 정답 QuizCorrectStreakProvider(QUIZ_CORRECT_STREAK)를 구현했으므로
-- V17에서 지급 중단으로 등록했던 '무결점' 배지의 조건을 등록하고 지급 중단을 해제한다(#163, #127).

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES ('30000000-0000-4000-8000-000000000004', 'QUIZ_CORRECT_STREAK', 5);

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000004';
