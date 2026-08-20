# AWS Console 구성 Runbook

## 목적과 운영 원칙

부여ON MVP 운영 인프라를 서울 리전 `ap-northeast-2`의 단일 AWS 계정에 Console로 생성·변경하고 검증하는 순서를 정의한다.

- 현재 시스템 구성의 원본은 [아키텍처](./architecture.md)다.
- 이 문서는 Console 작업 순서, 실제 리소스 식별자와 변경 이력의 원본이다.
- 운영 담당자는 전용 IAM User의 Console 비밀번호와 MFA로 로그인한다.
- 운영 담당자 IAM User의 Access Key는 생성하지 않으며 Root 계정을 일상 작업에 사용하지 않는다.
- 비밀번호, 토큰, 인증서 비공개 키와 Parameter Store `SecureString` 값은 기록하지 않는다.
- Console 변경 계획은 실행 전에 문서 PR로 검토하고, 실행 후 실제 식별자와 검증 결과를 갱신한다.

## 단계별 Blocker

모든 항목을 한 번에 확정할 필요는 없다. 각 표의 항목은 해당 단계만 차단한다. 값이 바뀌면 리소스 목록과 관련 아키텍처 문서를 함께 갱신한다.

### A. Resource 생성 입력

각 AWS resource를 생성하기 전에 해당 입력을 확정한다. Domain이나 배포 자동화가 준비되지 않아도 이 입력이 준비된 resource부터 생성할 수 있다.

| 입력 | 현재 값 | 차단되는 작업 |
| --- | --- | --- |
| AWS Account ID | 확정 필요 | 모든 AWS resource 생성 |
| AWS Region | `ap-northeast-2` | 모든 regional resource 생성 |
| VPC CIDR | 확정 필요 | VPC 생성 |
| Public Subnet CIDR·AZ | 확정 필요 | Public Subnet과 EC2 생성 |
| Private DB Subnet 2개의 CIDR·AZ | 확정 필요, 서로 다른 AZ | DB Subnet Group과 RDS 생성 |
| EC2 AMI·인스턴스 타입·루트 볼륨 | 확정 필요 | EC2 생성 |
| PostgreSQL 버전·RDS 타입·스토리지·DB 이름 | 확정 필요 | RDS 생성 |
| S3 Bucket 이름 | 확정 필요, 전역 유일 이름 | 이미지 S3 생성 |

### B. 최초 Production 배포 조건

AWS resource는 먼저 생성할 수 있지만, 아래 항목이 준비되기 전에는 외부에 Production 서비스를 공개하지 않는다.

| 입력·산출물 | 현재 값 | 차단되는 작업 |
| --- | --- | --- |
| 운영 Domain | 확정 필요 | Production API 공개 |
| Cloudflare zone·API hostname | 확정 필요 | DNS·Proxy·TLS 연결 |
| Production Compose 파일 | `compose.prod.yaml` | EC2 애플리케이션 실행 |
| Nginx 설정과 Origin Certificate 주입·갱신 절차 | `docker/nginx/nginx.prod.conf`, `scripts/deploy/restore-origin-tls.sh` | HTTPS origin 공개 |
| DB bootstrap과 Parameter Store 운영 값 | DB bootstrap 완료, TourAPI·Admin 값 추가 필요 | Spring Boot 최초 시작 |

### C. 자동 배포 완료 조건

아래 항목은 AWS resource 생성이나 수동 배포를 차단하지 않는다. 승인된 GitHub Actions → ECR → SSM 자동 배포를 사용하기 전에 완료한다.

| 입력·산출물 | 현재 값 | 차단되는 작업 |
| --- | --- | --- |
| GitHub organization/repository | `Buyeo-On/buyeo-on-be` | GitHub OIDC Trust Policy 생성 |
| SSM 배포·rollback script 또는 Command Document | `scripts/deploy/` | SSM 자동 배포·rollback |
| GitHub Actions production 배포 Workflow | `.github/workflows/deploy-production.yml` | GitHub Actions 자동 배포 |

## 리소스 목록

Console에서 생성한 직후 실제 ID, ARN 또는 Endpoint를 기록한다. 삭제 후 같은 이름으로 재생성한 경우 식별자를 반드시 교체한다.

| 구분 | 권장 이름 | 실제 ID·ARN·Endpoint |
| --- | --- | --- |
| 운영 담당자 IAM User | `buyeoon-operator` | 미생성 |
| GitHub OIDC Provider | `token.actions.githubusercontent.com` | 생성 완료 |
| GitHub Automation Role | `buyeoon-github-deploy` | ARN은 GitHub `AWS_DEPLOY_ROLE_ARN` Variable로 관리 |
| EC2 Instance Role·Profile | `buyeoon-ec2` | `buyeoon-ec2` |
| VPC | `buyeoon-vpc` | Console ID 기록 필요 |
| Public Subnet | `buyeoon-public-a` | Console ID 기록 필요 |
| Private DB Subnet | `buyeoon-db-a` | Console ID 기록 필요 |
| Private DB Subnet | `buyeoon-db-c` | Console ID 기록 필요 |
| Internet Gateway | `buyeoon-igw` | Console ID 기록 필요 |
| Public Route Table | `buyeoon-public-rt` | Console ID 기록 필요 |
| EC2 Security Group | `buyeoon-ec2-sg` | Console ID 기록 필요 |
| RDS Security Group | `buyeoon-rds-sg` | Console ID 기록 필요 |
| RDS DB Subnet Group | `buyeoon-db-subnets` | Console ID 기록 필요 |
| RDS | `buyeoon-db` | `buyeoon-db` |
| EC2 | `buyeoon-app` | `i-02040f4aa55f0e233` |
| EIP | `buyeoon-app-eip` | Console 주소 기록 필요 |
| ECR | `buyeoon/app` | 생성 여부 확인 필요 |
| 이미지 S3 | `buyeoon-images` | `buyeoon-images` |
| Parameter Store 경로 | `/buyeoon/` | `/buyeoon/` |
| CloudWatch Log Group | `/buyeoon/app`, `/buyeoon/nginx` | 9단계에서 생성 |

모든 Tag 지원 리소스에는 다음 값을 적용한다.

| Key | Value |
| --- | --- |
| `Project` | `buyeoon` |
| `Environment` | `prod` |
| `ManagedBy` | `aws-console` |

## 구성 순서

### 1. 운영 담당자 IAM User

1. Root 계정에 MFA를 설정한다.
2. 최초 bootstrap에만 Root로 로그인해 `buyeoon-operator` IAM User를 생성한다.
3. Console 로그인을 활성화하고 강한 비밀번호와 MFA를 설정한다.
4. MVP Console 프로비저닝에 필요한 관리자 권한을 연결한다.
5. Access Key는 생성하지 않는다.
6. 이후 Root에서 로그아웃하고 모든 작업을 운영 담당자 IAM User로 수행한다.

검증:

- [ ] MFA 없이는 운영 담당자 로그인이 완료되지 않는다.
- [ ] 운영 담당자 IAM User에 Access Key가 없다.
- [ ] Root 계정 Access Key가 없다.

### 2. 네트워크

1. DNS support와 DNS hostnames를 활성화한 VPC를 생성한다.
2. EC2를 배치할 Public Subnet 하나를 생성한다.
3. 서로 다른 Availability Zone에 Private DB Subnet 두 개를 생성한다.
4. Internet Gateway를 생성해 VPC에 연결한다.
5. Public Route Table에 `0.0.0.0/0 → Internet Gateway`를 추가하고 Public Subnet에 연결한다.
6. Private DB Subnet에는 인터넷에서 들어오는 경로를 추가하지 않는다.
7. EC2 Security Group inbound는 Cloudflare IP 대역의 TCP 443만 허용하고 SSH 22를 열지 않는다.
8. RDS Security Group inbound는 EC2 Security Group에서 오는 TCP 5432만 허용한다.

검증:

- [ ] Public Subnet만 Internet Gateway 기본 경로를 가진다.
- [ ] Private DB Subnet 두 개가 서로 다른 Availability Zone에 있다.
- [ ] 인터넷에서 RDS TCP 5432로 들어오는 경로가 없다.
- [ ] EC2 TCP 22가 열려 있지 않다.

### 3. 데이터·이미지·배포 저장소

1. 두 Private DB Subnet으로 RDS DB Subnet Group을 생성한다.
2. PostgreSQL RDS를 Single-AZ로 생성하고 Public Access를 비활성화한다.
3. RDS Security Group과 DB Subnet Group을 연결한다.
4. 저장소 암호화, 자동 백업 7일, PITR과 삭제 방지를 활성화한다.
5. ECR `buyeoon/app` Repository를 생성하고 Tag immutability, push 시 이미지 스캔과 untagged 이미지 수명주기를 설정한다.
6. 이미지 S3 Bucket 하나를 생성하고 Block Public Access와 SSE-S3를 활성화한다.
7. 개인정보 삭제 시 이전 버전이 남지 않도록 S3 Versioning을 활성화하지 않는다.
8. 객체 키를 `public/`과 `private/` prefix로 분리한다.
9. 공개 콘텐츠 DB 필드에는 `public/` prefix의 S3 객체 키를 `image_key`로 저장하고 Presigned URL을 저장하지 않는다.
10. Bucket Policy에서 `aws:SecureTransport=false`인 모든 요청을 거부한다.
11. S3 Lifecycle에 1일이 지난 미완료 multipart upload를 중단하는 규칙을 추가한다.
12. CloudWatch Log Group을 생성한다.
13. `/buyeoon/` 아래에 필요한 Parameter 이름을 생성하고 비밀값은 `SecureString`으로 저장한다.

7단계 배포가 읽는 Parameter 계약은 다음과 같다. 값은 이 문서나 셸 이력에 기록하지 않는다.

| Parameter | Type | 용도 |
| --- | --- | --- |
| `/buyeoon/aws/region` | String | AWS SDK·CLI Region |
| `/buyeoon/db/url` | String | JDBC URL |
| `/buyeoon/db/username`, `/buyeoon/db/password` | SecureString | 애플리케이션 DB Role |
| `/buyeoon/jwt/secret-base64` | SecureString | HS256 서명 키 |
| `/buyeoon/social/kakao/app-id` | String | 카카오 숫자형 앱 ID |
| `/buyeoon/storage/image-bucket` | String | Private 이미지 Bucket 이름 |
| `/buyeoon/tourapi/service-key` | SecureString | TourAPI 장소 동기화 |
| `/buyeoon/admin/api-key` | SecureString | `/admin/**` 인증 |

Apple 로그인은 기본적으로 비활성화한다. 활성화할 때만 `/buyeoon/social/apple/enabled=true`와 `client-id`, `team-id`, `key-id`, `private-key-base64`를 추가하며 비공개 키만 SecureString으로 저장한다. 8단계 TLS에서는 `/buyeoon/tls/origin-certificate`와 `/buyeoon/tls/origin-private-key`를 추가하고 비공개 키를 SecureString으로 저장한다.

RDS 삭제는 일반 변경과 분리해 승인된 변경으로만 수행한다.

1. 필요하면 삭제 전에 별도 수동 Snapshot을 만들고 `available` 상태를 확인한다.
2. 삭제 영향과 복구 방법을 승인받은 뒤 삭제 방지를 비활성화한다.
3. RDS 삭제를 요청하면서 전역에서 구분되는 최종 Snapshot 이름을 지정한다.
4. RDS 삭제가 시작된 뒤 최종 Snapshot이 생성되고 `available` 상태가 될 때까지 확인한다.
5. 최종 Snapshot ARN과 복원 검증 계획을 변경 이력에 기록한다.

검증:

- [ ] RDS Public Access가 비활성화되어 있다.
- [ ] RDS 백업 보존, PITR과 삭제 방지가 활성화되어 있다.
- [ ] S3 Bucket은 공개 접근과 Versioning이 비활성화되어 있다.
- [ ] S3가 비 HTTPS 요청을 거부하고 미완료 multipart upload lifecycle을 가진다.

### 4. EC2 Instance Role과 EC2

EC2 Instance Role은 다음 권한만 가진다.

| 대상 | 권한 |
| --- | --- |
| Systems Manager | `AmazonSSMManagedInstanceCore` 수준의 Managed Node 권한 |
| ECR | 인증 토큰 조회와 `buyeoon/app` 이미지 pull |
| Parameter Store | `/buyeoon/*`의 필요한 Parameter 조회·복호화 |
| 이미지 S3 Bucket | prefix 범위의 `ListBucket`, 객체 `GetObject`, `PutObject`, `DeleteObject` |
| CloudWatch Logs | 지정 Log Group의 Stream 생성과 이벤트 전송 |

S3 `ListBucket`은 `public/*`, `private/*` prefix 조건으로 제한하고 객체 권한은 해당 객체 ARN으로 제한한다. 이 권한으로 애플리케이션이 Presigned PUT·GET을 생성하고 `HeadObject` 검증과 삭제 작업을 수행한다.

현재 단일 EC2 Docker 구조에서는 애플리케이션이 Presigned URL을 만들기 위해 Instance Profile의 S3 권한을 사용한다. 따라서 Parameter Store 권한은 위에 열거한 운영 Parameter ARN만, S3 권한은 `buyeoon-images`의 필요한 prefix와 action만 허용한다. 컨테이너별 IAM 격리가 필요해지는 시점에는 ECS Task Role 구조로 이전한다.

1. EC2 Instance Role과 Instance Profile을 생성한다.
2. Public Subnet에 EC2를 생성하고 Instance Profile과 EC2 Security Group을 연결한다. 최초 package 설치와 SSM 등록을 위해 launch 시 public IPv4 자동 할당을 활성화한다.
3. EC2가 Running이 되면 즉시 EIP를 생성해 연결하고 임시 public IPv4를 대체한다.
4. SSM Agent가 포함된 승인 AMI를 사용하고 User data에는 Docker, Docker Compose Plugin과 CloudWatch Agent 설치만 둔다.
5. Root EBS와 Snapshot 암호화를 활성화하고 IMDSv2를 Required, hop limit을 2로 설정한다.
6. 애플리케이션·Nginx 구성과 비밀값은 AMI와 User data에 넣지 않는다.

검증:

- [ ] EC2가 Systems Manager Managed Node에 Online으로 표시된다.
- [ ] SSH 없이 Session Manager로 접속할 수 있다.
- [ ] EIP 연결 후 외부로 보이는 IP가 EIP와 일치한다.
- [ ] 필요한 Parameter와 S3 prefix에는 접근하고 다른 경로에는 접근하지 못한다.
- [ ] 지정된 CloudWatch Log Group에 로그를 전송할 수 있다.

### 5. DB bootstrap

EC2가 준비된 뒤 Session Manager로 접속해 최초 한 번 수행한다.

1. RDS 관리자 자격증명으로 접속한다.
2. PostGIS extension을 설치한다.
3. 로그나 명령 이력에 남기지 않는 방식으로 무작위 애플리케이션 DB 비밀번호를 생성한다.
4. `buyeoon_app`을 `LOGIN`, 비 Superuser Role로 생성하고 생성한 비밀번호를 설정한다.
5. `buyeoon_app`에 Flyway DDL과 애플리케이션 DML에 필요한 권한을 부여한다.
6. `/buyeoon/db/username`과 `/buyeoon/db/password` SecureString을 애플리케이션 계정 값으로 갱신한다.
7. RDS 관리자 자격증명을 애플리케이션 Parameter에 넣지 않는다.
8. 기존 개발 DB의 `image_url` 값이 있으면 `public/` S3 객체 키로 먼저 교체하고 V5를 적용한다.
9. V5가 적용된 앱 SHA를 최초 운영 기준 SHA로 기록한다. V5 이전 앱으로는 rollback하지 않는다.

검증:

- [ ] `buyeoon_app`은 Superuser가 아니다.
- [ ] EC2가 Parameter Store에서 읽은 애플리케이션 계정으로 로그인할 수 있다.
- [ ] 같은 Parameter 값으로 Flyway 전체 마이그레이션이 성공한다.
- [ ] 공개 이미지 컬럼이 `image_key`이고 모든 값이 `public/` prefix를 사용한다.
- [ ] EC2 외부에서 RDS에 연결할 수 없다.

### 6. GitHub OIDC와 Automation Role

GitHub Automation Role은 production Environment의 신뢰된 배포 경로다. DB 비밀번호를 직접 읽는 권한은 없지만, EC2에 명령을 보내고 ECR 이미지를 배포할 수 있으므로 침해 시 EC2 Instance Role을 통해 런타임 비밀에 간접 접근할 수 있다.

Trust Policy 조건:

| Claim | 허용 값 |
| --- | --- |
| `aud` | `sts.amazonaws.com` |
| `sub` | `repo:Buyeo-On/buyeo-on-be:environment:production` |

권한 경계:

| 대상 | 허용 | 비고 |
| --- | --- | --- |
| ECR authorization | `ecr:GetAuthorizationToken` | AWS API 제약으로 Resource `*` |
| `buyeoon/app` ECR ARN | 이미지 layer 확인·업로드, image push | 다른 Repository 제외 |
| `AWS-RunShellScript` 또는 확정한 SSM Document ARN | `ssm:SendCommand` | Document를 하나로 고정 |
| 운영 EC2 ARN | `ssm:SendCommand` | Tag와 Instance ARN으로 제한 |
| Command 결과 조회 | `ssm:GetCommandInvocation`, `ssm:ListCommandInvocations` | 지원되는 최소 Resource 범위 사용 |

1. GitHub OIDC Provider를 생성한다.
2. 위 Trust Policy로 GitHub Automation Role을 생성한다.
3. GitHub production Environment는 보호된 `main`만 배포하도록 제한하고 승인자를 설정한다.

검증:

- [ ] production Environment Job만 Role을 Assume할 수 있다.
- [ ] 임의 브랜치와 다른 Repository는 Role을 Assume할 수 없다.
- [ ] Role로 RDS, VPC, IAM User와 Parameter Store를 생성·변경하거나 Parameter 값을 읽을 수 없다.
- [ ] 허용된 ECR Repository와 운영 EC2에만 배포할 수 있다.

### 7. 배포 산출물과 호스트 복구

다음 산출물이 Repository에 구현되고 검토되기 전에는 최초 배포를 진행하지 않는다.

- `compose.prod.yaml`: SHA 이미지, localhost 내부 health 포트, 앱·Nginx 로그 볼륨
- `docker/nginx/nginx.prod.conf`: Cloudflare Origin TLS와 query string을 제외한 JSON access log
- `scripts/deploy/fetch-runtime-env.sh`: Parameter Store 값을 `/opt/buyeoon/runtime/runtime.env`에 root 전용 shell export 형식으로 복원
- `scripts/deploy/restore-origin-tls.sh`: Origin Certificate와 비공개 키를 `/opt/buyeoon/tls`에 복원
- `scripts/deploy/bootstrap-release.sh`: ECR SHA 이미지에서 같은 SHA의 배포 bundle을 추출
- `scripts/deploy/deploy.sh`, `rollback.sh`: Compose 교체, 내부 health, 현재·이전 SHA 기록과 rollback
- `.github/workflows/deploy-production.yml`: production Environment OIDC, ECR push, SSM polling과 공개 health 실패 rollback

Workflow의 외부 Action과 운영 컨테이너 이미지는 검증한 commit SHA 또는 digest로 고정한다. SSM Command는 기본 waiter보다 긴 20분 제한으로 terminal status를 polling하고 제한을 넘으면 cancellation을 요청한다.

호스트 상태는 `/opt/buyeoon/state/current-{sha,image,mode}`와 `previous-{sha,image,mode}`에 기록한다. 배포 bundle은 `/opt/buyeoon/releases/<commit-sha>`에 보관하며, EC2 디스크에는 RDS·S3의 영구 데이터를 저장하지 않는다.

GitHub production Environment에는 다음 Variables를 설정한다.

| Variable | 값 |
| --- | --- |
| `AWS_ACCOUNT_ID` | 운영 AWS Account ID |
| `AWS_DEPLOY_ROLE_ARN` | 6단계 GitHub Automation Role ARN |
| `AWS_REGION` | `ap-northeast-2` |
| `EC2_INSTANCE_ID` | 운영 EC2 Instance ID |
| `ECR_REPOSITORY` | `buyeoon/app` |
| `PUBLIC_HEALTH_URL` | 8단계에서 확정할 `https://<api-host>/actuator/health` |
| `PRODUCTION_DEPLOY_ENABLED` | 8단계 검증 전 `false`, 검증 후 `true` |

Stage 7에서는 Workflow를 수동 실행하고 `app-only`를 선택한다. 이 모드는 Nginx와 인증서 없이 앱 컨테이너를 실행하고 EC2 localhost의 `127.0.0.1:18080/actuator/health`만 검증한다. Stage 8에서 Origin Certificate, DNS와 Cloudflare를 준비한 뒤 `full`을 실행해 Nginx와 공개 health 검증을 활성화한다. `main` push 자동 배포는 `PRODUCTION_DEPLOY_ENABLED=true`일 때만 실행된다.

EC2 복구는 새 EC2를 같은 Public Subnet과 Instance Profile로 생성하고 EIP를 재연결한 뒤, 동일한 SSM 배포 산출물로 Nginx와 애플리케이션을 복원하는 방식으로 검증한다. 인증서와 애플리케이션 비밀값은 Parameter Store에서 다시 읽으며 EC2 디스크를 백업 원본으로 사용하지 않는다.

검증:

- [ ] 빈 EC2에서 수동 `app-only` Workflow만으로 앱과 Flyway를 시작할 수 있다.
- [ ] 8단계 이후 빈 EC2에서 `full` Workflow만으로 Nginx와 앱을 시작할 수 있다.
- [ ] 현재와 이전 app SHA가 모두 ECR에 존재한다.
- [ ] health check 실패 시 이전 SHA로 재실행된다.
- [ ] EC2 교체 후 RDS와 S3 데이터가 유지된다.

### 8. 최초 배포와 이미지 흐름

1. Cloudflare API DNS Record를 EIP로 연결하고 Proxy를 활성화한다.
2. Nginx에 Origin Certificate를 복원하고 TLS를 `Full (strict)`로 설정한다.
3. 군민증 캐릭터·테마·배지 등 앱이 관리할 공용 이미지 객체 키를 확정하고 `buyeoon-images/public/`에 실제 파일을 업로드한다.
4. 같은 `public/` 객체 키를 저장하는 Flyway 시드를 작성하고 테스트한다. TourAPI 원본 장소 이미지는 `source_image_href`를 사용하므로 일괄 복사하지 않는다.
5. GitHub Actions를 `full`로 실행해 테스트를 통과한 commit SHA 이미지를 ECR에 push하고 SSM Command 결과를 확인한다.
6. Spring Boot의 Flyway와 JPA validate가 성공한 뒤 GitHub Actions가 Cloudflare 경유 공개 health endpoint를 호출한다. 실패하면 Workflow가 실패하고 이전 SHA를 재배포한다.
7. 공개 콘텐츠 API가 `public/` 객체의 10분 Presigned GET URL을 발급하는지 확인한다.
8. Presigned PUT 업로드 후 서버가 크기·MIME 타입·소유자를 검증하는지 확인한다.

비공개 사진 Presigned GET과 다른 회원 접근 거부 검증은 현재 Repository에 조회 API가 없어 수행할 수 없다. 이 기능이 제품에 필요하면 인프라 배포 PR과 분리된 API 작업으로 구현한 뒤 아래 보류 체크를 활성화한다.

검증:

- [ ] Cloudflare 경유 HTTPS와 공개 health endpoint가 성공한다.
- [ ] EIP 직접 HTTPS 접근은 Cloudflare 대역 외에서 차단된다.
- [ ] S3 객체를 공개 S3 URL로 직접 조회할 수 없다.
- [ ] 보류: 다른 회원은 API를 통해 해당 비공개 사진의 Presigned URL을 발급받을 수 없다.
- [ ] Presigned URL은 HTTPS이며 만료 후 사용할 수 없다.
- [ ] Presigned URL은 만료 전까지 소지자가 사용할 수 있는 bearer URL임을 클라이언트·로그 정책에 반영했다.
- [ ] 실행 이미지 Tag가 배포 commit SHA와 일치한다.

### 9. 관측성과 복구

1. `/var/log/buyeoon/app/application.json`, `/var/log/buyeoon/nginx/access.log`, `/var/log/buyeoon/nginx/error.log`를 CloudWatch Logs에 전송하고 30일 보관한다.
2. RDS Snapshot 복원과 이전 app SHA 재배포를 비운영 복원 대상으로 검증한다.

검증:

- [ ] 로그에 토큰, Presigned URL, DB 비밀번호와 개인정보가 노출되지 않는다.
- [ ] Spring과 Nginx 로그가 지정된 Log Group에 기록되고 30일 후 만료된다.
- [ ] RDS Snapshot에서 새 인스턴스를 복원할 수 있다.

## Console 변경 절차

1. 변경 목적, 영향받는 리소스, 예상 중단과 복구 방법을 이 문서의 변경 이력에 먼저 작성한다.
2. 문서 변경 PR을 검토받고 승인된 뒤 Console 변경을 수행한다.
3. 의존 리소스와 필요한 RDS Snapshot을 확인한다.
4. Console에서 한 번에 하나의 논리 변경만 수행한다.
5. 관련 단계의 검증 체크리스트를 다시 수행한다.
6. 실제 ID·ARN·Endpoint와 검증 결과를 문서에 반영해 후속 PR을 올린다.

## 변경 이력

| 일시 | 작업자 | 변경 목적 | 대상 리소스 | 검증 결과 | 복구 방법 |
| --- | --- | --- | --- | --- | --- |
| 미작성 | 미작성 | 최초 운영 인프라 구성 | 전체 | 미검증 | 해당 단계 Blocker와 단계별 검증 완료 필요 |

## Terraform 재검토 기준

다음 중 하나가 발생하면 Console 운영을 계속하지 않고 Terraform 도입을 검토한다.

- 운영 외 AWS 환경을 추가한다.
- 두 명 이상이 정기적으로 인프라를 변경한다.
- 동일 구성을 반복 생성하거나 재해 복구 시간을 단축해야 한다.
- Console 설정 누락이나 문서와 실제 상태의 불일치가 반복된다.
- 감사 가능한 변경 승인과 자동 drift 탐지가 필요해진다.
