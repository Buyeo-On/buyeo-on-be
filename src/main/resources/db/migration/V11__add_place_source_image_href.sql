ALTER TABLE places
    ADD COLUMN source_image_href text; -- TourAPI 등 외부 출처의 대표이미지 URL(S3 객체 키가 아님)
