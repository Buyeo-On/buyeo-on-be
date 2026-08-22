-- #163 "2. 간단한 집계 메트릭 추가로 도입 가능" 분류의 "백제 박사" 배지(퀴즈 15개 정답)를 지급 가능 상태로
-- 전환한다(#185). QuizCorrectAnswerCountProvider(QUIZ_CORRECT_COUNT) 구현이 끝났으므로
-- badge_conditions를 등록하고 V17에서 채워둔 retired_at을 해제한다.

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES
    ('30000000-0000-4000-8000-000000000002', 'QUIZ_CORRECT_COUNT', 15);

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000002';
