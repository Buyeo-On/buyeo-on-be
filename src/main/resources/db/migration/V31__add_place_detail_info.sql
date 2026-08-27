ALTER TABLE places
    ADD COLUMN detail_info jsonb NOT NULL DEFAULT '{}'::jsonb; -- TourAPI detailInfo2 이용안내(항목명 -> 내용). 내용이 빈 항목은 저장하지 않는다
