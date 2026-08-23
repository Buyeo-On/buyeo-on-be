-- 문화재 내 특정 지점을 가리킬 수 있도록 미션에도 좌표를 추가한다. 장소 규모가 제각각이라 장소 좌표와의 거리 제약은 두지 않는다.
ALTER TABLE missions ADD COLUMN location geography(Point, 4326);

UPDATE missions m
SET location = p.location
FROM places p
WHERE m.place_id = p.id AND m.location IS NULL;

ALTER TABLE missions ALTER COLUMN location SET NOT NULL;
