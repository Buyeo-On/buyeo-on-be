INSERT INTO places (
    id,
    category,
    name,
    summary,
    description,
    address,
    location,
    source_name,
    external_id
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    'HERITAGE',
    '부여 정림사지',
    '백제 사비도성의 중심 사찰터',
    '정림사지 오층석탑과 석조여래좌상이 남아 있는 백제 시대의 사찰터입니다.',
    '충청남도 부여군 부여읍 정림로 83',
    ST_SetSRID(ST_MakePoint(126.9137, 36.2798), 4326)::geography,
    'BUYEO_ON_SAMPLE',
    'jeongnimsa'
);

INSERT INTO missions (
    id,
    place_id,
    type,
    title,
    description,
    reward_points,
    max_attempts,
    ox_correct_answer
) VALUES
    (
        '20000000-0000-0000-0000-000000000101',
        '20000000-0000-0000-0000-000000000001',
        'OX',
        '백제의 석탑',
        '정림사지 오층석탑은 백제 시대에 세워졌다.',
        100,
        3,
        true
    ),
    (
        '20000000-0000-0000-0000-000000000102',
        '20000000-0000-0000-0000-000000000001',
        'MULTIPLE_CHOICE',
        '정림사지 시대 맞히기',
        '정림사지 오층석탑이 세워진 시대를 선택하세요.',
        100,
        NULL,
        NULL
    ),
    (
        '20000000-0000-0000-0000-000000000103',
        '20000000-0000-0000-0000-000000000001',
        'PHOTO',
        '오층석탑 사진 남기기',
        '정림사지 오층석탑이 잘 보이도록 사진을 촬영하세요.',
        100,
        NULL,
        NULL
    );

INSERT INTO mission_choices (id, mission_id, label, correct, sort_order) VALUES
    (
        '20000000-0000-0000-0000-000000000201',
        '20000000-0000-0000-0000-000000000102',
        '백제',
        true,
        1
    ),
    (
        '20000000-0000-0000-0000-000000000202',
        '20000000-0000-0000-0000-000000000102',
        '신라',
        false,
        2
    ),
    (
        '20000000-0000-0000-0000-000000000203',
        '20000000-0000-0000-0000-000000000102',
        '고려',
        false,
        3
    );
