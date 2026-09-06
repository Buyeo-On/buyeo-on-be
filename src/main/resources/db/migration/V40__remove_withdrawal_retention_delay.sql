-- 기존 30일 유예 대상도 다음 파기 작업에서 처리한다.
-- 사진 객체 키를 잃지 않도록 이 마이그레이션에서는 회원/사진 행을 삭제하지 않는다.
UPDATE members
SET purge_after = withdrawn_at
WHERE status = 'WITHDRAWN'
  AND purged_at IS NULL
  AND purge_after > withdrawn_at;
