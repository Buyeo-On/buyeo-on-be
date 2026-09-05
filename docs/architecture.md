# 아키텍처

## 목표와 범위
- **출시 대상:** iOS 단일 플랫폼
- **클라이언트:** 향후 멀티플랫폼 확장을 고려해 Flutter 유지
- **백엔드:** Spring Boot 모듈러 모놀리스
- **운영 수준:** 단일 EC2 장애와 배포 중 일시 중단을 허용하는 MVP
- **AWS 리전·계정:** 서울 `ap-northeast-2`, 단일 계정
## 전체 요청 흐름
```text
iOS Flutter
├─ Cloudflare HTTPS → Nginx → Spring Boot → RDS PostgreSQL/PostGIS
├─ Kakao Map SDK
├─ FCM
├─ S3 Presigned PUT
└─ S3 Presigned GET
```
## 클라이언트
- Flutter의 현재 지원 플랫폼은 iOS다. Android 등은 추후 확장한다.
- 기능 중심 구조를 사용하며 각 기능을 `presentation`, `application`, `domain`, `data`로 나눈다.
- 상태 관리는 Riverpod을 사용하고 위젯이 API 클라이언트나 저장소를 직접 호출하지 않는다.
- 액세스 토큰은 메모리, 리프레시 토큰은 `flutter_secure_storage`에 저장한다.
- 동시에 여러 인증 요청이 실패해도 토큰 갱신은 하나만 실행한다.
- MVP에서는 오프라인 쓰기·재전송 큐·영구 로컬 DB를 지원하지 않는다.
- `raw/openapi.yaml`에서 Dart API 클라이언트를 생성하고 feature Repository가 이를 감싼다.
- Flutter CI는 현재 `flutter analyze`, `flutter test`만 수행한다.
- iOS 서명, TestFlight, App Store 자동화는 **TBD**다.
## 외부 트래픽과 네트워크
- Cloudflare 프록시와 `Full (strict)` TLS를 사용한다.
- Nginx에는 Cloudflare Origin Certificate를 적용한다.
- EC2 보안 그룹은 Cloudflare IP 대역의 443만 허용하고 서버 관리는 SSH 대신 SSM을 사용한다.
- Nginx가 `request_id`를 만들고 Spring MDC에 전달하며 `CF-Ray`도 함께 기록한다.
- RDS는 Private Subnet에 배치하고 Public Access를 비활성화한다.
- RDS 인바운드는 EC2 Security Group에서 오는 PostgreSQL 연결만 허용한다.
- Nginx는 IP 기준, Spring은 회원·도메인 기준으로 요청 속도를 제한하고 초과 시 `429`를 반환한다.
- 단일 인스턴스 단계에서는 Redis 기반 분산 Rate Limiter를 사용하지 않는다.
## 백엔드
- 언어: Java 21
- Spring Boot 하나를 배포 단위로 유지하고 [도메인 지도](./domains/README.md)에 따라 회원, 여행, 장소, 미션, 포인트, 배지, 알림 패키지를 분리한다.
- 도메인 간 협력은 서비스와 도메인 사건을 사용하고 다른 도메인의 Repository를 직접 사용하지 않는다.
- 랭킹처럼 여러 도메인의 데이터를 결합해야 하는 조회 유즈케이스는 주도 도메인의 조회 서비스가 쓰기 모델을 변경하지 않는 전용 projection으로 한 번에 조회할 수 있다. 다른 도메인의 Repository를 직접 호출하거나 조회 결과로 다른 도메인의 상태를 변경하지 않는다.
- 배지는 Spring 내부의 메트릭 Provider와 데이터 조건으로 판정한다.
- FCM은 Firebase Admin SDK로 Spring이 직접 발송하며 실패가 본 업무를 롤백하지 않는다.
- 고아 사진 삭제와 포인트 만료 같은 내부 정기 작업은 멱등한 Spring Scheduler 작업으로 실행한다.
- 관광공사(TourAPI) 데이터는 사용자 요청 전에 내부 DB로 동기화한다. 관리자 전용 API가 부여 지역 목록(`areaBasedList2`)과 항목별 상세(`detailCommon2`, `detailIntro2`, `detailInfo2`)를 호출해 `places`를 `external_id` 기준으로 upsert하며, 항목 단위 실패는 건너뛰고 계속 진행한다. 대표이미지는 `cpyrhtDivCd` 이용허락 유형도 함께 저장한다. 자세한 내용은 [ADR-013](./adr/adr-013-tourapi-place-sync.md), [ADR-015](./adr/adr-015-tourapi-image-attribution.md)를 참고한다.
- 지도 렌더링·마커·현재 위치는 Flutter의 Kakao Map SDK가 담당한다. 서버용 지오코딩이 필요할 때만 Spring이 Kakao REST API를 호출한다.
- 부여 행정구역은 국토교통부 법정구역정보의 시군구(`SIG`) 전체 데이터에서 부여군 코드 `44760`을 추출한 버전 관리 GeoJSON과 출처·추출일·SHA-256 메타데이터로 고정하고, 여행 시작 위치를 서버에서 point-in-polygon 방식으로 검증한다.
- Redis, 별도 비동기 워커와 API Gateway·Lambda 기반 배지 판정은 보류한다.
## 인증
- 소셜 인증은 회원 도메인의 제공자 검증 인터페이스 뒤에서 카카오와 Apple 외부 어댑터로 구현한다. 자동 테스트는 제어 가능한 가짜 어댑터를 사용한다.
- 카카오 네이티브 Flutter SDK가 발급한 access token을 앱이 전달하면 서버가 카카오 API에서 유효성과 제공자 회원 식별자를 확인한다.
- Apple은 앱이 전달한 인가 코드, identity token과 nonce를 서버가 검증하고 subject를 회원 식별자로 사용한다.
- OAuth 인가 코드, 외부 access token, identity token과 client secret은 DB나 로그에 저장하지 않는다.
- 소셜 계정 연결은 로그인된 회원이 별도 API로 명시적으로 수행한다.
- 액세스 토큰은 수명 1시간의 JWT다.
- 액세스 JWT는 단일 백엔드 MVP에서 Parameter Store의 256-bit 이상 비밀 키로 `HS256` 서명한다.
- 액세스 JWT의 `sub`에는 회원 ID, `sid`에는 인증 세션 ID를 넣는다. 인증이 필요한 요청은 `sid`의 세션이 만료·폐기되지 않았는지 확인한다.
- 리프레시 토큰은 `sessionId.randomSecret` 형태의 opaque token이며 DB에는 secret 해시만 저장한다.
- 리프레시 토큰 secret은 CSPRNG로 256-bit 이상 생성하고 DB에는 SHA-256 해시를 저장한다.
- 리프레시 토큰 수명은 30일이고 갱신할 때마다 교체한다.
- 이전 토큰의 재사용은 `401`로 거절하되 MVP에서는 이를 탈취로 단정해 전체 세션을 폐기하지 않는다.
- 갱신할 때 인증 세션 행을 직렬화하며 먼저 확정된 요청만 리프레시 토큰을 교체한다.
- 로그아웃·탈퇴 시 세션을 즉시 폐기한다.
- FCM 등록 토큰은 현재 인증 세션과 1:1로 관리한다. 로그아웃·탈퇴 시 발송 대상에서 제외하고 FCM이 무효로 응답한 토큰은 삭제한다.
- JWT 키와 OAuth 시크릿은 Parameter Store `SecureString`에서 읽는다.
- Apple 로그인은 필요한 Apple 자격증명이 준비될 때만 활성화한다. 비활성화된 제공자로 로그인하면 소셜 인증 실패로 처리하고 다른 제공자와 애플리케이션 기동에는 영향을 주지 않는다.
## 데이터베이스
- AWS RDS PostgreSQL과 PostGIS를 사용한다.
- 장소는 `geography(Point, 4326)`와 GiST 인덱스로 저장한다.
- 운영 JPA는 `ddl-auto=validate`를 사용한다.
- 데이터 접근은 Spring Data JPA를 기본으로 사용한다. 조회는 JPQL + constructor projection을 기본으로 하고, JPQL로 표현할 수 없는 SQL(집계·PostgreSQL 전용 문법)에만 네이티브 쿼리를 허용한다.
- Spring Boot가 시작될 때 런타임 DB Role로 Flyway 마이그레이션을 실행하며 실패하면 애플리케이션 시작도 실패한다.
- MVP에서는 Flyway DDL과 애플리케이션 DML에 하나의 PostgreSQL Role을 사용한다. RDS 관리자 계정은 최초 bootstrap에만 사용하고 애플리케이션에 제공하지 않는다.
- 운영 PostGIS 확장은 최초 애플리케이션 배포 전에 인프라 bootstrap 단계에서 설치한다.
- 호환 가능한 단계적 마이그레이션을 사용하고 운영에서 자동 down migration을 하지 않는다.
- `V5__replace_public_image_urls_with_keys.sql`은 최초 production 배포 전에 한 번만 적용하는 비호환 migration이다. V5를 적용한 앱을 최초 운영 기준 SHA로 삼으며 그 이전 앱으로의 rollback은 지원하지 않는다.
- MVP의 장소·미션 예시 카탈로그는 버전 관리되는 Flyway 시드로 로컬·CI·운영에 동일하게 적용한다. 별도의 정식 운영 콘텐츠 관리·갱신 절차를 마련하기 전까지 임시 데이터로 사용한다.
- 애플리케이션은 `Instant`, DB는 `timestamptz`, 서버·DB 시스템 시간대는 UTC를 사용한다. 사용자 표시와 날짜 판정만 `Asia/Seoul`로 변환한다.
- RDS 자동 백업은 7일 보존하고 PITR과 삭제 방지를 활성화한다. RDS 삭제 절차에서는 이름이 지정된 최종 Snapshot 생성과 완료를 확인한다.
- Multi-AZ와 다중 리전은 MVP에서 사용하지 않는다.
## 이미지와 정적 콘텐츠
- 공개 정적 콘텐츠와 비공개 미션 사진은 하나의 Private S3 Bucket에서 `public/`과 `private/` prefix로 분리한다.
- S3는 Block Public Access와 SSE-S3를 사용하고 Bucket Policy로 비 HTTPS 요청을 거부하며 객체를 공개 S3 URL로 제공하지 않는다.
- 미션 사진은 Presigned PUT URL로 직접 업로드하고 서버가 소유자, 크기와 MIME 타입을 검증한다.
- 공개 콘텐츠는 콘텐츠 API, 비공개 사진은 소유권 확인이 필요한 사진 API가 각각 10분 유효한 S3 Presigned GET URL을 발급한다.
- 공개 콘텐츠 테이블에는 만료되는 Presigned URL 대신 `public/` prefix의 S3 객체 키를 `image_key`로 저장한다.
- Presigned GET URL은 만료 전까지 소지자가 사용할 수 있는 bearer URL이므로 로그에 기록하거나 외부로 공유하지 않는다.
- 탈퇴 회원의 등록 사진은 별도 30일 유예 없이 다음 회원 파기 작업에서 삭제한다. 기본 작업 간격은 이전 실행 종료 후 1분이며 실패하면 다음 실행에서 재시도한다.
- 24시간 안에 제출되지 않은 고아 사진, 탈퇴 후 지연 업로드, 과거 객체 버전과 실패한 multipart upload 정리는 별도 저장소 정책·구현을 검증해야 한다. 회원 파기 작업만으로 해당 정리가 완료된다고 간주하지 않는다.
- 개인정보 삭제 시 이전 객체 버전이 남지 않도록 이 Bucket은 버전 관리를 사용하지 않는다.
## 배포와 인프라 관리
- 운영은 EIP가 연결된 단일 EC2에서 Nginx와 Spring 컨테이너를 Docker Compose로 실행한다.
- EC2는 stateless하며 영구 데이터는 RDS·S3·CloudWatch에만 저장한다.
- GitHub Actions가 테스트 후 커밋 SHA 태그의 단일 애플리케이션 이미지를 ECR에 push한다.
- EC2는 SSM 명령으로 이미지를 pull하고 Docker Compose를 실행하며 애플리케이션 시작 과정에서 Flyway를 적용한다.
- 최초 운영 기준 SHA 이후 배포는 `/actuator/health`가 실패하면 직전 운영 SHA 이미지로 자동 롤백한다.
- 배포가 끝나면 GitHub Actions가 Cloudflare를 경유해 공개 health endpoint를 확인한다.
- Cloudflare 준비 전에는 수동 `app-only` 배포로 내부 health만 확인하고, production Environment의 자동 `full` 배포는 명시적 Repository Variable로 활성화한다.
- AWS 인프라는 전용 IAM User로 접근한 운영 담당자가 [AWS Console 구성 Runbook](./aws-console-provisioning.md)에 따라 Console에서 생성·변경하고 리소스 목록과 변경 이력을 문서에 남긴다. IAM User는 MFA와 Console 비밀번호만 사용하고 Access Key를 만들지 않는다.
- ECR push와 SSM 배포는 GitHub Actions만 수행하고 하나의 GitHub Automation OIDC IAM Role을 사용한다.
- GitHub Automation Role과 EC2 Instance Role은 신뢰 주체가 다르므로 분리하며 장기 AWS Access Key를 사용하지 않는다.
- AWS에는 운영 환경만 상시 유지한다. 로컬 개발은 개발용 Supabase와 Git에서 제외한 `.env`를, CI는 임시 PostgreSQL/PostGIS를 사용한다.
## CI 품질 게이트
- Spring 단위·통합 테스트
- 임시 PostgreSQL/PostGIS에 Flyway 전체 마이그레이션 적용
- OpenAPI 문법·참조 검증
- Flutter `analyze`, `test`
- Docker 이미지 빌드
- 의존성과 컨테이너 이미지 취약점 스캔
- 모든 검사를 통과한 `main`만 운영 배포
## 관측성과 복구
- SLF4J 구조화 로그를 사용하고 개발은 일반 로그, 운영은 JSON 로그로 출력한다.
- Spring ECS JSON 로그와 Nginx JSON access·error 로그를 호스트의 `/var/log/buyeoon`에 기록하고 CloudWatch Logs에 전송해 30일 보관한다.
- 액세스 토큰, OAuth 코드, Presigned URL과 개인정보는 로그에 기록하지 않는다.
- Docker `HEALTHCHECK`는 Actuator liveness를 확인한다.
- 공개 헬스 응답은 내부 구성과 DB 상세정보를 노출하지 않는다.
## 미정·보류
- 관광공사 데이터 자동 재시딩 주기(현재는 관리자가 수동 호출)
- 예시 시드를 대체할 정식 운영 미션 콘텐츠 관리·갱신 절차
- iOS 서명·TestFlight·App Store 배포 자동화
- EC2·RDS 인스턴스 크기
- Multi-AZ·다중 리전
- Redis와 별도 비동기 워커
## 관련 ADR
- [Architecture Decision Records](./adr/README.md)
