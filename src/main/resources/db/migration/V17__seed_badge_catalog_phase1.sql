-- #163에서 분류한 13종 후보 배지 중 "1. 현재 구현으로 바로 도입 가능"·"2. 간단한 집계 메트릭 추가로
-- 도입 가능" 2개 분류, 총 8종을 초기 catalog로 시딩한다(#127).
--
-- "2. 간단한 집계 메트릭 추가로 도입 가능" 6종은 아직 BadgeMetricProvider와 badge_conditions가 없어
-- retired_at을 채워 등록한다. 이렇게 하면 신규 지급은 막히면서도 BadgeCatalogValidator의 startup
-- 검증(조건·Provider 필수)을 통과한다. Provider 구현 후 별도 migration으로 badge_conditions를 등록하고
-- retired_at을 해제한다.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM badges) THEN
        RAISE EXCEPTION 'Badge catalog must be empty before applying V17';
    END IF;
END $$;

INSERT INTO badges (id, category, name, description, image_key, condition_text, retired_at)
VALUES
    -- 1. 현재 구현으로 바로 도입 가능
    -- 원본 배지 스펙(Figma 티어 목록)에서 "백제 문화유산 3곳 방문하기"는 "백제 유산 답사자"이며,
    -- "왕도의 발자취"는 15곳 방문(최상위 티어)이다. #163은 이름과 threshold를 잘못 매칭했었다.
    ('30000000-0000-4000-8000-000000000001', 'EXPLORATION', '백제 유산 답사자', '백제 문화유산 3곳 방문하기', 'public/badges/807ad218-f368-4677-99eb-13aeffaa186e.png', '백제 문화유산 3곳 방문하기', NULL),
    ('30000000-0000-4000-8000-000000000006', 'ASSET', '사비의 마음', '포인트 기부하기', 'public/badges/32750b5f-bbb1-4d15-8fbd-0a4692c2d44b.png', '포인트 기부하기', NULL),

    -- 2. 간단한 집계 메트릭 추가로 도입 가능 (Provider 미구현 — 지급 중단 상태로 등록)
    ('30000000-0000-4000-8000-000000000002', 'QUIZ', '백제 박사', '퀴즈 15개 정답', 'public/badges/d86f3856-cf33-4fec-a4a4-3b6693363900.png', '퀴즈 15개 정답', now()),
    ('30000000-0000-4000-8000-000000000003', 'QUIZ', '집중력', '60분 내에 퀴즈 10개 정답', 'public/badges/aa0f435e-6e61-420e-9a5a-acb98b4aa851.png', '60분 내에 퀴즈 10개 정답', now()),
    ('30000000-0000-4000-8000-000000000004', 'QUIZ', '무결점', '퀴즈 5개 연속 정답', 'public/badges/b7a050d3-5495-4a49-a1b2-65e23923fffa.png', '퀴즈 5개 연속 정답', now()),
    ('30000000-0000-4000-8000-000000000005', 'RECORD', '추억 수집가', '인증 사진 15회 제출', 'public/badges/2d1be927-f4c6-417c-829e-28ba01cf48cb.png', '인증 사진 15회 제출', now()),
    ('30000000-0000-4000-8000-000000000007', 'SPECIAL', '백제 문화유산 완주자', '문화유산 15곳 방문, 퀴즈 15개 정답, 인증 사진 15회 제출', 'public/badges/06537313-d433-498f-8791-500c864d363e.png', '문화유산 15곳 방문, 퀴즈 15개 정답, 인증 사진 15회 제출', now()),
    ('30000000-0000-4000-8000-000000000008', 'SPECIAL', '부여 마스터', '전체 업적 100% 달성', 'public/badges/bbfea294-db74-4269-92c9-0734e0ae9688.png', '전체 업적 100% 달성', now());

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES
    ('30000000-0000-4000-8000-000000000001', 'HERITAGE_VISITED_COUNT', 3),
    ('30000000-0000-4000-8000-000000000006', 'POINT_DONATION_COUNT', 1);
