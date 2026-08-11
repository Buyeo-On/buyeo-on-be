DO $$
BEGIN
    IF EXISTS (
        SELECT image_url FROM card_characters WHERE image_url NOT LIKE 'public/%'
        UNION ALL
        SELECT image_url FROM card_themes WHERE image_url NOT LIKE 'public/%'
        UNION ALL
        SELECT image_url FROM places WHERE image_url IS NOT NULL AND image_url NOT LIKE 'public/%'
        UNION ALL
        SELECT image_url FROM badges WHERE image_url IS NOT NULL AND image_url NOT LIKE 'public/%'
    ) THEN
        RAISE EXCEPTION 'Public image URLs must be replaced with public/ S3 object keys before V5';
    END IF;
END $$;

ALTER TABLE card_characters
    RENAME COLUMN image_url TO image_key;

ALTER TABLE card_characters
    ADD CONSTRAINT card_characters_image_key_prefix_ck
    CHECK (image_key LIKE 'public/%');

ALTER TABLE card_themes
    RENAME COLUMN image_url TO image_key;

ALTER TABLE card_themes
    ADD CONSTRAINT card_themes_image_key_prefix_ck
    CHECK (image_key LIKE 'public/%');

ALTER TABLE places
    RENAME COLUMN image_url TO image_key;

ALTER TABLE places
    ADD CONSTRAINT places_image_key_prefix_ck
    CHECK (image_key IS NULL OR image_key LIKE 'public/%');

ALTER TABLE badges
    RENAME COLUMN image_url TO image_key;

ALTER TABLE badges
    ADD CONSTRAINT badges_image_key_prefix_ck
    CHECK (image_key IS NULL OR image_key LIKE 'public/%');
