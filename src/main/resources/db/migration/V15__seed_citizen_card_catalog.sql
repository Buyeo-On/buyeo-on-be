DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM card_characters) OR EXISTS (SELECT 1 FROM card_themes) THEN
        RAISE EXCEPTION 'Citizen card catalog must be empty before applying V15';
    END IF;
END $$;

INSERT INTO card_characters (id, name, image_key, sort_order)
VALUES
    ('10000000-0000-4000-8000-000000000001', '금동이', 'public/characters/geumdong.png', 1),
    ('10000000-0000-4000-8000-000000000002', '금용이', 'public/characters/geumyong.png', 2),
    ('10000000-0000-4000-8000-000000000003', '금황이', 'public/characters/geumhwang.png', 3);

INSERT INTO card_themes (id, name, image_key, sort_order)
VALUES
    ('20000000-0000-4000-8000-000000000001', '봉황', 'public/themes/phoenix.svg', 1),
    ('20000000-0000-4000-8000-000000000002', '연화문', 'public/themes/lotus.svg', 2),
    ('20000000-0000-4000-8000-000000000003', '금관', 'public/themes/crown.svg', 3),
    ('20000000-0000-4000-8000-000000000004', '금관 장식', 'public/themes/crown_ornament.svg', 4),
    ('20000000-0000-4000-8000-000000000005', '석탑', 'public/themes/pagoda.svg', 5),
    ('20000000-0000-4000-8000-000000000006', '돛배', 'public/themes/sailboat.svg', 6);
