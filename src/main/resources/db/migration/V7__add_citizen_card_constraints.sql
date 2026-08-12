ALTER TABLE card_characters ADD COLUMN sort_order smallint;
ALTER TABLE card_themes ADD COLUMN sort_order smallint;

WITH ranked AS (
    SELECT id, row_number() OVER (ORDER BY id)::smallint AS sort_order
    FROM card_characters
)
UPDATE card_characters character
SET sort_order = ranked.sort_order
FROM ranked
WHERE character.id = ranked.id;

WITH ranked AS (
    SELECT id, row_number() OVER (ORDER BY id)::smallint AS sort_order
    FROM card_themes
)
UPDATE card_themes theme
SET sort_order = ranked.sort_order
FROM ranked
WHERE theme.id = ranked.id;

ALTER TABLE card_characters ALTER COLUMN sort_order SET NOT NULL;
ALTER TABLE card_characters ADD CONSTRAINT card_characters_sort_order_ck CHECK (sort_order > 0);
ALTER TABLE card_characters ADD CONSTRAINT card_characters_sort_order_uq UNIQUE (sort_order);

ALTER TABLE card_themes ALTER COLUMN sort_order SET NOT NULL;
ALTER TABLE card_themes ADD CONSTRAINT card_themes_sort_order_ck CHECK (sort_order > 0);
ALTER TABLE card_themes ADD CONSTRAINT card_themes_sort_order_uq UNIQUE (sort_order);

ALTER TABLE member_profiles DROP CONSTRAINT member_profiles_display_name_check;
ALTER TABLE member_profiles ADD CONSTRAINT member_profiles_display_name_ck CHECK (
    char_length(display_name) BETWEEN 1 AND 8
    AND display_name = btrim(display_name)
    AND display_name !~ '[[:cntrl:]]'
);

-- MVP 운영 전 마이그레이션이며 기존 시연 값은 UUID 바코드로 교체한다.
UPDATE citizen_cards
SET barcode_value = gen_random_uuid()::text
WHERE barcode_value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$';

ALTER TABLE citizen_cards ADD CONSTRAINT citizen_cards_barcode_value_ck CHECK (
    barcode_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
);
