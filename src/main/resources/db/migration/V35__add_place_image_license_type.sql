ALTER TABLE places
ADD COLUMN source_image_license_type text
CHECK (source_image_license_type IS NULL OR source_image_license_type IN ('KOGL_TYPE_1', 'KOGL_TYPE_3'));
