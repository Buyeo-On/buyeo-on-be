-- 운영에서는 권한이 분리된 Flyway 실행 전에 인프라 bootstrap으로 PostGIS를 설치한다.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TYPE social_provider AS ENUM ('KAKAO', 'APPLE');
CREATE TYPE member_status AS ENUM ('ACTIVE', 'WITHDRAWN');
CREATE TYPE term_type AS ENUM ('SERVICE', 'PRIVACY', 'MARKETING');
CREATE TYPE trip_status AS ENUM ('IN_PROGRESS', 'ENDED', 'SETTLED');
CREATE TYPE place_category AS ENUM ('HERITAGE', 'RESTAURANT', 'CAFE');
CREATE TYPE mission_type AS ENUM ('MULTIPLE_CHOICE', 'OX', 'PHOTO');
CREATE TYPE mission_status AS ENUM ('AVAILABLE', 'EXHAUSTED', 'COMPLETED');
CREATE TYPE settlement_choice AS ENUM ('LEAVE_TO_BUYEO', 'CARRY_OVER');
CREATE TYPE point_transaction_type AS ENUM ('EARN', 'LEAVE_TO_BUYEO', 'EXPIRE', 'ADJUST');
CREATE TYPE badge_category AS ENUM ('EXPLORATION', 'QUIZ', 'RECORD', 'ASSET', 'SPECIAL');
CREATE TYPE notification_type AS ENUM ('POINT', 'BADGE', 'NEARBY_QUIZ', 'DISCOUNT', 'CITIZEN_CARD', 'BUYEO_NEWS');

-- 서비스 회원과 탈퇴 상태를 관리한다.
CREATE TABLE members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 회원 ID
    status member_status NOT NULL DEFAULT 'ACTIVE', -- 회원 상태
    created_at timestamptz NOT NULL DEFAULT now(), -- 가입 시각
    withdrawn_at timestamptz, -- 탈퇴 확정 시각
    purge_after timestamptz, -- 개인정보 파기 기한
    CHECK (
        (status = 'ACTIVE' AND withdrawn_at IS NULL AND purge_after IS NULL)
        OR (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL AND purge_after IS NOT NULL)
    )
);

-- 회원과 카카오·Apple 계정의 연결을 저장한다.
CREATE TABLE social_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 소셜 계정 연결 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    provider social_provider NOT NULL, -- 소셜 로그인 제공자
    provider_subject text NOT NULL, -- 제공자가 발급한 사용자 식별자
    created_at timestamptz NOT NULL DEFAULT now(), -- 연결 시각
    UNIQUE (provider, provider_subject),
    UNIQUE (member_id, provider)
);

-- 로그인 유지와 로그아웃에 사용하는 인증 세션이다.
CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 세션 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    refresh_token_hash text NOT NULL UNIQUE, -- 리프레시 토큰 해시
    expires_at timestamptz NOT NULL, -- 세션 만료 시각
    revoked_at timestamptz, -- 로그아웃·탈퇴로 폐기된 시각
    created_at timestamptz NOT NULL DEFAULT now() -- 세션 생성 시각
);

-- 현재 인증 세션의 기기로 FCM 알림을 보내기 위한 등록 토큰이다.
CREATE TABLE push_tokens (
    auth_session_id uuid PRIMARY KEY REFERENCES auth_sessions(id) ON DELETE CASCADE, -- 인증 세션 ID
    registration_token text NOT NULL UNIQUE, -- FCM 등록 토큰
    created_at timestamptz NOT NULL DEFAULT now(), -- 최초 등록 시각
    updated_at timestamptz NOT NULL DEFAULT now() -- 마지막 갱신 시각
);

-- 버전별 서비스 약관 원문을 관리한다.
CREATE TABLE terms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 약관 ID
    type term_type NOT NULL, -- 약관 종류
    version text NOT NULL, -- 약관 버전
    required boolean NOT NULL, -- 필수 동의 여부
    title text NOT NULL, -- 약관 제목
    content text NOT NULL, -- 약관 본문
    effective_at timestamptz NOT NULL, -- 시행 시각
    UNIQUE (type, version)
);

-- 회원이 동의한 약관과 버전을 기록한다.
CREATE TABLE term_consents (
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    term_id uuid NOT NULL REFERENCES terms(id), -- 약관 ID
    agreed boolean NOT NULL, -- 동의 여부
    agreed_at timestamptz NOT NULL DEFAULT now(), -- 동의·거부 시각
    PRIMARY KEY (member_id, term_id)
);

-- 군민증과 프로필에서 선택할 캐릭터 목록이다.
CREATE TABLE card_characters (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 캐릭터 ID
    name text NOT NULL, -- 캐릭터 이름
    image_key text NOT NULL CHECK (image_key LIKE 'public/%') -- 캐릭터 이미지 객체 키
);

-- 디지털 군민증에서 선택할 카드 테마 목록이다.
CREATE TABLE card_themes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 테마 ID
    name text NOT NULL, -- 테마 이름
    image_key text NOT NULL CHECK (image_key LIKE 'public/%') -- 테마 이미지 객체 키
);

-- 마이페이지와 군민증에 표시할 회원 프로필이다.
CREATE TABLE member_profiles (
    member_id uuid PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    display_name varchar(8) NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 8), -- 표시 이름
    character_id uuid NOT NULL REFERENCES card_characters(id), -- 선택한 캐릭터 ID
    updated_at timestamptz NOT NULL DEFAULT now() -- 마지막 수정 시각
);

-- 회원별 디지털 군민증 발급 정보를 저장한다.
CREATE TABLE citizen_cards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 군민증 ID
    member_id uuid NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE, -- 소유 회원 ID
    theme_id uuid NOT NULL REFERENCES card_themes(id), -- 선택한 카드 테마 ID
    barcode_value text NOT NULL UNIQUE, -- 시연용 바코드 값
    issued_at timestamptz NOT NULL DEFAULT now() -- 발급 시각
);

-- 회원별 알림·화면 설정을 저장한다.
CREATE TABLE member_settings (
    member_id uuid PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    nearby_quiz_notification_enabled boolean NOT NULL DEFAULT false, -- 근처 퀴즈 알림 동의 여부
    dark_mode_enabled boolean NOT NULL DEFAULT false, -- 다크 모드 사용 여부
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0) -- 낙관적 잠금 버전
);

-- 문화재·식당·카페 등 탐색 가능한 장소다.
CREATE TABLE places (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 장소 ID
    category place_category NOT NULL, -- 장소 분류
    name text NOT NULL, -- 장소명
    summary text, -- 목록용 요약
    description text, -- 상세 설명
    address text, -- 주소
    image_key text CHECK (image_key IS NULL OR image_key LIKE 'public/%'), -- 대표 이미지 객체 키
    location geography(Point, 4326) NOT NULL, -- 장소 위치
    source_name text, -- 관광데이터 제공처
    external_id text, -- 제공처가 부여한 장소 식별자
    source_url text, -- 관광데이터 원문 URL
    CHECK (external_id IS NULL OR source_name IS NOT NULL)
);

-- 회원이 저장한 장소 관계다.
CREATE TABLE saved_places (
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    place_id uuid NOT NULL REFERENCES places(id) ON DELETE CASCADE, -- 장소 ID
    saved_at timestamptz NOT NULL DEFAULT now(), -- 저장 시각
    PRIMARY KEY (member_id, place_id)
);

-- 부여에서 시작해 종료·정산할 때까지의 여행 단위다.
CREATE TABLE trips (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 여행 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 여행 회원 ID
    status trip_status NOT NULL DEFAULT 'IN_PROGRESS', -- 여행 상태
    started_at timestamptz NOT NULL DEFAULT now(), -- 여행 시작 시각
    ended_at timestamptz, -- 여행 종료 시각
    settled_at timestamptz, -- 포인트 정산 완료 시각
    UNIQUE (member_id, id),
    CHECK (
        (status = 'IN_PROGRESS' AND ended_at IS NULL AND settled_at IS NULL)
        OR (status = 'ENDED' AND ended_at IS NOT NULL AND settled_at IS NULL)
        OR (status = 'SETTLED' AND ended_at IS NOT NULL AND settled_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX trips_one_in_progress_per_member
    ON trips (member_id) WHERE status = 'IN_PROGRESS';

-- 문화재와 연결된 퀴즈·사진 인증 미션이다.
CREATE TABLE missions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 미션 ID
    place_id uuid NOT NULL REFERENCES places(id), -- 연결된 문화재 장소 ID
    type mission_type NOT NULL, -- 미션 유형
    title text NOT NULL, -- 미션 제목
    description text NOT NULL, -- 문제·촬영 안내
    reward_points integer NOT NULL CHECK (reward_points >= 0), -- 완료 보상 포인트
    max_attempts integer CHECK (max_attempts IS NULL OR max_attempts > 0), -- 최대 도전 횟수
    ox_correct_answer boolean, -- OX 미션 정답
    CHECK (
        (type = 'OX' AND ox_correct_answer IS NOT NULL)
        OR (type <> 'OX' AND ox_correct_answer IS NULL)
    ),
    CHECK (type <> 'PHOTO' OR max_attempts IS NULL)
);

-- 객관식 미션의 선택지다.
CREATE TABLE mission_choices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 선택지 ID
    mission_id uuid NOT NULL REFERENCES missions(id) ON DELETE CASCADE, -- 미션 ID
    label text NOT NULL, -- 선택지 내용
    correct boolean NOT NULL, -- 정답 여부
    sort_order integer NOT NULL, -- 표시 순서
    UNIQUE (mission_id, sort_order)
);

-- 특정 여행에서의 미션 진행 상태다.
CREATE TABLE mission_participations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 미션 참여 ID
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE, -- 여행 ID
    mission_id uuid NOT NULL REFERENCES missions(id), -- 미션 ID
    status mission_status NOT NULL DEFAULT 'AVAILABLE', -- 참여 상태
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0), -- 도전 횟수
    completed_at timestamptz, -- 완료 시각
    UNIQUE (trip_id, mission_id),
    CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    )
);

-- 사진 인증 미션에서 서버에 업로드한 사진이다.
CREATE TABLE mission_photos (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 사진 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 촬영 회원 ID
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE, -- 여행 ID
    mission_id uuid NOT NULL REFERENCES missions(id), -- 사진 미션 ID
    object_key text NOT NULL UNIQUE, -- 스토리지 객체 키
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')), -- 파일 MIME 타입
    file_size_bytes bigint NOT NULL CHECK (file_size_bytes > 0), -- 파일 크기
    uploaded_at timestamptz NOT NULL DEFAULT now() -- 업로드 완료 시각
);

-- 회원이 미션에 제출한 답이나 사진을 기록한다.
CREATE TABLE mission_submissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 제출 ID
    participation_id uuid NOT NULL REFERENCES mission_participations(id) ON DELETE CASCADE, -- 미션 참여 ID
    type mission_type NOT NULL, -- 제출 유형
    choice_id uuid REFERENCES mission_choices(id), -- 객관식 선택지 ID
    ox_answer boolean, -- OX 답안
    photo_id uuid REFERENCES mission_photos(id), -- 제출 사진 ID
    correct boolean NOT NULL, -- 정답·인증 성공 여부
    submitted_at timestamptz NOT NULL DEFAULT now(), -- 제출 시각
    CHECK (
        (type = 'MULTIPLE_CHOICE' AND choice_id IS NOT NULL AND ox_answer IS NULL AND photo_id IS NULL)
        OR (type = 'OX' AND choice_id IS NULL AND ox_answer IS NOT NULL AND photo_id IS NULL)
        OR (type = 'PHOTO' AND choice_id IS NULL AND ox_answer IS NULL AND photo_id IS NOT NULL)
    )
);

-- 미션 완료 시 연결된 문화재의 방문 기록을 생성한다.
CREATE TABLE visit_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 방문 기록 ID
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE, -- 여행 ID
    mission_id uuid NOT NULL REFERENCES missions(id), -- 방문을 확정한 미션 ID
    place_id uuid NOT NULL REFERENCES places(id), -- 방문한 문화재 장소 ID
    visited_at timestamptz NOT NULL DEFAULT now(), -- 방문 확정 시각
    UNIQUE (trip_id, place_id)
);

-- 잔액은 point_transactions.amount 합계로 계산한다.
CREATE TABLE point_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 포인트 내역 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    trip_id uuid REFERENCES trips(id) ON DELETE SET NULL, -- 관련 여행 ID
    participation_id uuid REFERENCES mission_participations(id), -- 적립을 만든 미션 참여 ID
    type point_transaction_type NOT NULL, -- 포인트 변동 유형
    amount bigint NOT NULL CHECK (amount <> 0), -- 증감 포인트
    description text NOT NULL, -- 활동명·변동 사유
    occurred_at timestamptz NOT NULL DEFAULT now(), -- 포인트 변동 시각
    CHECK (
        (type = 'EARN' AND amount > 0)
        OR (type IN ('LEAVE_TO_BUYEO', 'EXPIRE') AND amount < 0)
        OR type = 'ADJUST'
    )
);

CREATE UNIQUE INDEX point_transactions_one_reward_per_mission
    ON point_transactions (participation_id) WHERE type = 'EARN';

-- 이번 여행에서 적립한 미정산 포인트만 애플리케이션 트랜잭션에서 정산한다.
CREATE TABLE point_settlements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 정산 ID
    trip_id uuid NOT NULL UNIQUE REFERENCES trips(id) ON DELETE CASCADE, -- 정산 대상 여행 ID
    choice settlement_choice NOT NULL, -- 남기기·이월 선택
    settled_points bigint NOT NULL CHECK (settled_points >= 0), -- 이번 여행에서 정산한 포인트
    expires_at timestamptz, -- 이월 포인트 만료 시각
    settled_at timestamptz NOT NULL DEFAULT now(), -- 정산 완료 시각
    CHECK (
        (choice = 'LEAVE_TO_BUYEO' AND expires_at IS NULL)
        OR (choice = 'CARRY_OVER' AND expires_at = settled_at + INTERVAL '240 hours')
    )
);

-- 획득 가능한 배지의 조건과 표시 정보를 관리한다.
CREATE TABLE badges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 배지 ID
    category badge_category NOT NULL, -- 배지 분야
    name text NOT NULL, -- 배지명
    description text NOT NULL, -- 배지 설명
    image_key text CHECK (image_key IS NULL OR image_key LIKE 'public/%'), -- 배지 이미지 객체 키
    condition_text text NOT NULL, -- 사용자에게 표시할 달성 조건
    retired_at timestamptz -- 신규 지급 중단 시각
);

-- 배지를 획득하기 위해 모두 충족해야 하는 메트릭 조건이다.
CREATE TABLE badge_conditions (
    badge_id uuid NOT NULL REFERENCES badges(id) ON DELETE CASCADE, -- 배지 ID
    metric_key text NOT NULL, -- Spring이 지원하는 메트릭 키
    threshold bigint NOT NULL CHECK (threshold > 0), -- 달성 기준값
    PRIMARY KEY (badge_id, metric_key)
);

-- 회원이 획득한 배지 이력이다.
CREATE TABLE member_badges (
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 회원 ID
    badge_id uuid NOT NULL REFERENCES badges(id), -- 배지 ID
    trip_id uuid NOT NULL, -- 배지를 처음 획득한 여행 ID
    earned_at timestamptz NOT NULL DEFAULT now(), -- 획득 시각
    PRIMARY KEY (member_id, badge_id),
    FOREIGN KEY (member_id, trip_id) REFERENCES trips(member_id, id)
);

-- 회원에게 표시할 서비스 알림이다.
CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- 알림 ID
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 수신 회원 ID
    type notification_type NOT NULL, -- 알림 종류
    title text NOT NULL, -- 알림 제목
    body text NOT NULL, -- 알림 내용
    read_at timestamptz, -- 읽은 시각
    target_type text, -- 연결 대상 종류
    target_id uuid, -- 연결 대상 ID
    occurred_at timestamptz NOT NULL DEFAULT now() -- 알림 발생 시각
);

-- 같은 회원과 키에 다른 operation 또는 request_hash가 들어오면 애플리케이션에서 409를 반환한다.
CREATE TABLE idempotency_requests (
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE, -- 요청 회원 ID
    idempotency_key varchar(128) NOT NULL, -- 클라이언트가 보낸 멱등성 키
    operation text NOT NULL, -- API 작업 이름
    request_hash text NOT NULL, -- 요청 본문 해시
    response_status integer, -- 최초 처리 HTTP 상태 코드
    response_body jsonb, -- 최초 처리 응답 본문
    created_at timestamptz NOT NULL DEFAULT now(), -- 키 등록 시각
    expires_at timestamptz NOT NULL, -- 키 보관 만료 시각
    PRIMARY KEY (member_id, idempotency_key),
    CHECK (char_length(idempotency_key) BETWEEN 8 AND 128)
);

CREATE INDEX auth_sessions_member_idx ON auth_sessions (member_id, expires_at);
CREATE INDEX trips_member_idx ON trips (member_id, started_at DESC);
CREATE INDEX places_location_gix ON places USING GIST (location);
CREATE UNIQUE INDEX places_source_external_id_uq
    ON places (source_name, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX missions_place_idx ON missions (place_id);
CREATE INDEX mission_participations_trip_idx ON mission_participations (trip_id, status);
CREATE INDEX visit_records_trip_idx ON visit_records (trip_id, visited_at);
CREATE INDEX point_transactions_member_idx ON point_transactions (member_id, occurred_at DESC);
CREATE INDEX badge_conditions_metric_idx ON badge_conditions (metric_key);
CREATE INDEX member_badges_trip_idx ON member_badges (trip_id, earned_at);
CREATE INDEX notifications_member_idx ON notifications (member_id, occurred_at DESC);
