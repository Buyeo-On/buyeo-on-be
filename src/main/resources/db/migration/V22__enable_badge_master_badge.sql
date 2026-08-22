-- #190: "부여 마스터"(30000000-0000-4000-8000-000000000008) meta 배지를 활성화한다.
--
-- threshold는 이 배지를 제외한 현재 지급 가능(retired_at IS NULL)한 배지 수다. 순환 정의를 피하기 위해
-- 배지 자신은 분모에서 제외한다: 이 migration 적용 시점에 지급 가능한 배지는 V17에서 등록한
-- '백제 유산 답사자'(...0001), '사비의 마음'(...0006) 2종뿐이므로 threshold=2다.
--
-- 이후 QUIZ·RECORD 등 나머지 배지(...0002~...0005, ...0007)의 Provider가 구현되어 retired_at이
-- NULL로 풀리면, 그 migration에서 이 배지의 threshold도 "새로 늘어난 지급 가능 배지 수만큼" 함께 갱신해야
-- 이 배지가 정말 "전체 업적 100% 달성"을 의미하게 된다.

UPDATE badges SET retired_at = NULL WHERE id = '30000000-0000-4000-8000-000000000008';

INSERT INTO badge_conditions (badge_id, metric_key, threshold)
VALUES ('30000000-0000-4000-8000-000000000008', 'BADGE_ACQUIRED_COUNT', 2);
