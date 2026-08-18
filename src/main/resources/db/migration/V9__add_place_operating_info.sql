ALTER TABLE places
    ADD COLUMN operating_hours_raw text, -- 관람시간 원문(TourAPI usetime 등, 파싱 실패 시 UI 표시용)
    ADD COLUMN opens_at time, -- 파싱에 성공한 경우의 관람 시작 시각
    ADD COLUMN closes_at time, -- 파싱에 성공한 경우의 관람 종료 시각
    ADD COLUMN admission_fee integer; -- 입장료(원), 무료는 0

ALTER TABLE places
    ADD CONSTRAINT places_admission_fee_non_negative_ck
    CHECK (admission_fee IS NULL OR admission_fee >= 0);
