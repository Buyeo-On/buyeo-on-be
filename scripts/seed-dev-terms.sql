-- 개발·UI 테스트 전용 약관이다. 운영 DB에 실행하지 않는다.
-- 법적 효력이 있는 최종 약관은 별도 Flyway 마이그레이션으로 버전을 추가한다.

BEGIN;

INSERT INTO terms (id, type, version, required, title, content, effective_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'SERVICE', 'dev-0', true,
     '서비스 이용약관', E'[개발 테스트용 · 법적 효력 없음]\n\n부여ON 서비스 이용 조건을 확인하기 위한 임시 본문입니다.',
     '2026-08-01T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000002', 'PRIVACY', 'dev-0', true,
     '개인정보 수집·이용 동의', E'[개발 테스트용 · 법적 효력 없음]\n\n회원 식별자와 서비스 이용 기록의 수집·이용 동의를 확인하기 위한 임시 본문입니다.',
     '2026-08-01T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000003', 'LOCATION', 'dev-0', true,
     '위치기반서비스 이용약관', E'[개발 테스트용 · 법적 효력 없음]\n\n부여군 경계와 미션 거리 판정에 현재 위치를 사용하는 임시 약관입니다.',
     '2026-08-01T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000004', 'MARKETING', 'dev-0', false,
     '마케팅 정보 수신 동의', E'[개발 테스트용 · 법적 효력 없음]\n\n할인·행사·관광 소식 앱 푸시 수신 여부를 확인하기 위한 임시 본문입니다.',
     '2026-08-01T00:00:00Z')
ON CONFLICT (type, version) DO UPDATE
SET required = EXCLUDED.required,
    title = EXCLUDED.title,
    content = EXCLUDED.content,
    effective_at = EXCLUDED.effective_at;

COMMIT;
