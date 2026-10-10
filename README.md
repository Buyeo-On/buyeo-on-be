# 부여ON · Backend

**군민증부터 미션과 여행 기록까지, 부여 여행의 흐름을 연결하는 API.**

App Store에 출시한 부여ON의 서버입니다. 회원·디지털 군민증, 장소 탐색, 현장 미션, 포인트·배지와 여행의 발자취를 도메인별로 관리합니다.

[App Store](https://apps.apple.com/kr/app/id6807093401) · [프로젝트 소개](https://jinhyeong-studio.jinhyeong9568-663.workers.dev/projects/buyeo-on) · [API 계약](docs/raw/openapi.yaml)

## 주요 기능

| 도메인 | 책임 |
| --- | --- |
| 회원 | 카카오·Apple 인증, 약관, 군민증·프로필, 인증 세션 |
| 여행 | 시작·종료, 방문 기록, 통계·발자취 |
| 장소 | 문화유산·식당·카페 탐색과 저장, TourAPI 동기화 |
| 미션 | 위치 조건을 확인하는 퀴즈·사진 미션 |
| 포인트·배지 | 보상 내역, 정산과 조건별 배지 |
| 알림 | 알림함·발송 자격과 FCM |

군민증 바코드와 지역화폐 결제 연계는 현재 제품 소개 범위가 아닙니다.

## 기술과 구조

**Java 21 · Spring Boot 4.1 · Spring Data JPA · Flyway · PostgreSQL / PostGIS**

```text
Flutter iOS → Cloudflare HTTPS → Nginx → Spring Boot
                                      ├─ PostgreSQL / PostGIS
                                      ├─ Private S3 · Presigned URL
                                      └─ Kakao / Apple / FCM / TourAPI
```

모듈러 모놀리스로 배포하고 도메인 간 협력은 서비스·사건을 통해 연결합니다. 장소 조회에는 공간 인덱스를, 여행 시작에는 부여 경계 GeoJSON 기반 위치 검증을 사용합니다.

## 로컬 실행

Java 21과 **PostGIS가 준비된 개발용 PostgreSQL**이 필요합니다.

```bash
cp .env.example .env
# DB, JWT, 카카오 등 실행에 필요한 로컬 값 설정
./gradlew bootRun
```

환경 파일은 Spring 설정에서 읽습니다. 기동 시 Flyway가 적용되고 JPA는 스키마를 검증합니다. 운영 DB를 로컬 테스트 대상으로 사용하지 않습니다.

## 검증

```bash
./gradlew test
```

통합 테스트의 Testcontainers 실행에는 Docker가 필요합니다.

## 인증과 비공개 데이터

- 액세스 JWT와 회전하는 리프레시 토큰을 분리합니다.
- 리프레시 토큰 원문 대신 해시를 저장하고, 로그아웃·탈퇴 시 세션을 폐기합니다.
- 외부 OAuth 토큰·인가 코드·개인 키를 DB나 로그에 남기지 않습니다.
- 미션 사진은 비공개 S3 객체로 관리하며 소유권 확인 후 제한 시간의 URL을 발급합니다.
- Presigned URL도 만료 전 사용할 수 있는 권한이므로 로그·외부 공유에서 보호해야 합니다.

## 배포와 문서

단일 EC2의 Nginx·Spring 컨테이너와 Private RDS·S3 구성을 사용합니다. GitHub Actions의 테스트·이미지 배포·SSM 실행 흐름과 비밀 값 관리는 운영 문서를 따릅니다. 단일 인스턴스 MVP이며 무중단·고가용성 구성이 아닙니다.

[제품](docs/prd.md) · [Architecture](docs/architecture.md) · [도메인 지도](docs/domains/README.md) · [유즈케이스](docs/use-cases.md) · [전역 정책](docs/policies/README.md) · [DB 스키마](docs/raw/db-schema.sql) · [ADR](docs/adr/README.md)
