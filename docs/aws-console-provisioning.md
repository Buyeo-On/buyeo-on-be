# AWS Console 구성 Runbook

## 목적과 운영 원칙

부여ON MVP 운영 인프라를 서울 리전 `ap-northeast-2`의 단일 AWS 계정에 Console로 생성·변경하고 검증하는 순서를 정의한다.

- 현재 시스템 구성의 원본은 [아키텍처](./architecture.md)다.
- 이 문서는 Console 작업 순서, 실제 리소스 식별자와 변경 이력의 원본이다.
- 운영 담당자는 전용 IAM User의 Console 비밀번호와 MFA로 로그인한다.
- 운영 담당자 IAM User의 Access Key는 생성하지 않으며 Root 계정을 일상 작업에 사용하지 않는다.
- 비밀번호, 토큰, 인증서 비공개 키와 Parameter Store `SecureString` 값은 기록하지 않는다.
- Console 변경 계획은 실행 전에 문서 PR로 검토하고, 실행 후 실제 식별자와 검증 결과를 갱신한다.

## 최초 구성 Blocker

아래 입력을 모두 확정하고 배포 산출물을 구현하기 전에는 최초 운영 구성을 시작하지 않는다. 값이 바뀌면 리소스 목록과 관련 아키텍처 문서를 함께 갱신한다.

| 입력 | 현재 값 |
| --- | --- |
| AWS Account ID | 확정 필요 |
| AWS Region | `ap-northeast-2` |
| GitHub organization/repository | `Buyeo-On/buyeo-on-be` |
| 운영 도메인 | 확정 필요 |
| VPC CIDR | 확정 필요 |
| Public Subnet CIDR·AZ | 확정 필요 |
| Private DB Subnet 2개의 CIDR·AZ | 확정 필요, 서로 다른 AZ |
| EC2 AMI·인스턴스 타입·루트 볼륨 | 확정 필요 |
| PostgreSQL 버전·RDS 타입·스토리지·DB 이름 | 확정 필요 |
| S3 Bucket 이름 | 확정 필요, 전역 유일 이름 |
| Cloudflare zone·API hostname | 확정 필요 |
| Production Compose 파일 | 미구현 |
| SSM 배포·롤백 스크립트 또는 Command Document | 미구현 |
| GitHub Actions production 배포 Workflow | 미구현 |
| Nginx Origin Certificate 주입·갱신 절차 | 미구현 |

## 리소스 목록

Console에서 생성한 직후 실제 ID, ARN 또는 Endpoint를 기록한다. 삭제 후 같은 이름으로 재생성한 경우 식별자를 반드시 교체한다.

| 구분 | 권장 이름 | 실제 ID·ARN·Endpoint |
| --- | --- | --- |
| 운영 담당자 IAM User | `buyeoon-operator` | 미생성 |
| GitHub OIDC Provider | `token.actions.githubusercontent.com` | 미생성 |
| GitHub Automation Role | `buyeoon-prod-github-automation` | 미생성 |
| EC2 Instance Role·Profile | `buyeoon-prod-ec2` | 미생성 |
| VPC | `buyeoon-prod-vpc` | 미생성 |
| Public Subnet | `buyeoon-prod-public-a` | 미생성 |
| Private DB Subnet | `buyeoon-prod-db-a` | 미생성 |
| Private DB Subnet | `buyeoon-prod-db-c` | 미생성 |
| Internet Gateway | `buyeoon-prod-igw` | 미생성 |
| Public Route Table | `buyeoon-prod-public-rt` | 미생성 |
| EC2 Security Group | `buyeoon-prod-ec2-sg` | 미생성 |
| RDS Security Group | `buyeoon-prod-rds-sg` | 미생성 |
| RDS DB Subnet Group | `buyeoon-prod-db-subnets` | 미생성 |
| RDS | `buyeoon-prod-db` | 미생성 |
| EC2 | `buyeoon-prod-app` | 미생성 |
| EIP | `buyeoon-prod-app-eip` | 미생성 |
| ECR | `buyeoon/app` | 미생성 |
| 이미지 S3 | 확정 필요 | 미생성 |
| Parameter Store 경로 | `/buyeoon/prod/` | 미생성 |
| CloudWatch Log Group | `/buyeoon/prod/app`, `/buyeoon/prod/nginx` | 미생성 |
| CloudWatch Alarm | `buyeoon-prod-*` | 미생성 |
| Nginx 5xx Log Metric Filter | `buyeoon-prod-nginx-5xx` | 미생성 |
| SNS Topic·Subscription | `buyeoon-prod-alerts` | 미생성 |

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
12. CloudWatch Log Group, SNS Topic·Subscription을 생성한다.
13. `/buyeoon/prod/` 아래에 필요한 Parameter 이름을 생성하고 비밀값은 `SecureString`으로 저장한다.

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
- [ ] SNS 이메일 구독이 확인된 상태다.

### 4. EC2 Instance Role과 EC2

EC2 Instance Role은 다음 권한만 가진다.

| 대상 | 권한 |
| --- | --- |
| Systems Manager | `AmazonSSMManagedInstanceCore` 수준의 Managed Node 권한 |
| ECR | 인증 토큰 조회와 `buyeoon/app` 이미지 pull |
| Parameter Store | `/buyeoon/prod/*`의 필요한 Parameter 조회·복호화 |
| 이미지 S3 Bucket | prefix 범위의 `ListBucket`, 객체 `GetObject`, `PutObject`, `DeleteObject` |
| CloudWatch Logs | 지정 Log Group의 Stream 생성과 이벤트 전송 |
| CloudWatch Metrics | Agent namespace로 제한한 `PutMetricData` |

S3 `ListBucket`은 `public/*`, `private/*` prefix 조건으로 제한하고 객체 권한은 해당 객체 ARN으로 제한한다. 이 권한으로 애플리케이션이 Presigned PUT·GET을 생성하고 `HeadObject` 검증과 삭제 작업을 수행한다.

1. EC2 Instance Role과 Instance Profile을 생성한다.
2. Public Subnet에 EC2를 생성하고 Instance Profile과 EC2 Security Group을 연결한다. 최초 package 설치와 SSM 등록을 위해 launch 시 public IPv4 자동 할당을 활성화한다.
3. EC2가 Running이 되면 즉시 EIP를 생성해 연결하고 임시 public IPv4를 대체한다.
4. SSM Agent가 포함된 승인 AMI를 사용하고 User data에는 Docker, Docker Compose Plugin과 CloudWatch Agent 설치만 둔다.
5. 애플리케이션·Nginx 구성과 비밀값은 AMI와 User data에 넣지 않는다.

검증:

- [ ] EC2가 Systems Manager Managed Node에 Online으로 표시된다.
- [ ] SSH 없이 Session Manager로 접속할 수 있다.
- [ ] EIP 연결 후 외부로 보이는 IP가 EIP와 일치한다.
- [ ] 필요한 Parameter와 S3 prefix에는 접근하고 다른 경로에는 접근하지 못한다.
- [ ] CloudWatch Logs와 Agent metric을 전송할 수 있다.

### 5. DB bootstrap

EC2가 준비된 뒤 Session Manager로 접속해 최초 한 번 수행한다.

1. RDS 관리자 자격증명으로 접속한다.
2. PostGIS extension을 설치한다.
3. 로그나 명령 이력에 남기지 않는 방식으로 무작위 애플리케이션 DB 비밀번호를 생성한다.
4. `buyeoon_app`을 `LOGIN`, 비 Superuser Role로 생성하고 생성한 비밀번호를 설정한다.
5. `buyeoon_app`에 Flyway DDL과 애플리케이션 DML에 필요한 권한을 부여한다.
6. `/buyeoon/prod/db/username`과 `/buyeoon/prod/db/password` SecureString을 애플리케이션 계정 값으로 갱신한다.
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
4. 장기 AWS Access Key를 GitHub에 저장하지 않는다.

검증:

- [ ] production Environment Job만 Role을 Assume할 수 있다.
- [ ] 임의 브랜치와 다른 Repository는 Role을 Assume할 수 없다.
- [ ] Role로 RDS, VPC, IAM User와 Parameter Store를 생성·변경하거나 Parameter 값을 읽을 수 없다.
- [ ] 허용된 ECR Repository와 운영 EC2에만 배포할 수 있다.

### 7. 배포 산출물과 호스트 복구

다음 산출물이 Repository에 구현되고 검토되기 전에는 최초 배포를 진행하지 않는다.

- Production Docker Compose 파일
- Nginx 설정
- Parameter Store 값을 root 전용 임시 환경 파일로 만드는 절차
- Cloudflare Origin Certificate와 비공개 키를 Parameter Store에서 복원하는 절차
- ECR pull, Compose 교체, health check와 이전 SHA 롤백을 수행하는 SSM 배포 스크립트 또는 Document
- 현재·이전 배포 SHA를 기록하는 위치와 형식
- `environment: production`, OIDC 권한, SHA 이미지 push, SSM SendCommand·결과 polling, Cloudflare 경유 공개 health 확인과 실패 시 이전 SHA 롤백을 포함한 GitHub Actions Workflow

EC2 복구는 새 EC2를 같은 Public Subnet과 Instance Profile로 생성하고 EIP를 재연결한 뒤, 동일한 SSM 배포 산출물로 Nginx와 애플리케이션을 복원하는 방식으로 검증한다. 인증서와 애플리케이션 비밀값은 Parameter Store에서 다시 읽으며 EC2 디스크를 백업 원본으로 사용하지 않는다.

검증:

- [ ] 빈 EC2에서 SSM 배포만으로 Nginx와 앱을 시작할 수 있다.
- [ ] 현재와 이전 app SHA가 모두 ECR에 존재한다.
- [ ] health check 실패 시 이전 SHA로 재실행된다.
- [ ] EC2 교체 후 RDS와 S3 데이터가 유지된다.

### 8. 최초 배포와 이미지 흐름

1. Cloudflare API DNS Record를 EIP로 연결하고 Proxy를 활성화한다.
2. Nginx에 Origin Certificate를 복원하고 TLS를 `Full (strict)`로 설정한다.
3. 테스트를 통과한 commit SHA 이미지를 ECR에 push한다.
4. GitHub Actions가 SSM으로 운영 EC2에 배포 명령을 전달하고 Command 성공 여부를 확인한다.
5. Spring Boot의 Flyway와 JPA validate가 성공한 뒤 GitHub Actions가 Cloudflare 경유 공개 health endpoint를 호출한다. 실패하면 Workflow가 실패하고 이전 SHA를 재배포한다.
6. 공개 콘텐츠 API가 `public/` 객체의 10분 Presigned GET URL을 발급하는지 확인한다.
7. 비공개 사진 API가 소유권 확인 후 `private/` 객체의 10분 Presigned GET URL을 발급하는지 확인한다.
8. Presigned PUT 업로드 후 서버가 크기·MIME 타입·소유자를 검증하는지 확인한다.

검증:

- [ ] Cloudflare 경유 HTTPS와 공개 health endpoint가 성공한다.
- [ ] EIP 직접 HTTPS 접근은 Cloudflare 대역 외에서 차단된다.
- [ ] S3 객체를 공개 S3 URL로 직접 조회할 수 없다.
- [ ] 다른 회원은 API를 통해 해당 비공개 사진의 Presigned URL을 발급받을 수 없다.
- [ ] Presigned URL은 HTTPS이며 만료 후 사용할 수 없다.
- [ ] Presigned URL은 만료 전까지 소지자가 사용할 수 있는 bearer URL임을 클라이언트·로그 정책에 반영했다.
- [ ] 실행 이미지 Tag가 배포 commit SHA와 일치한다.

### 9. 관측성과 복구

1. Spring과 Nginx 로그를 CloudWatch Logs에 전송하고 30일 보관한다.
2. Nginx JSON 로그에서 5xx를 집계하는 CloudWatch Logs Metric Filter와 사용자 정의 Metric을 생성한다.
3. EC2 상태·Agent 디스크 Metric·Nginx 5xx Metric과 RDS 저장 공간·연결 수 Alarm을 생성한다.
4. 테스트 Alarm과 SNS 이메일을 수신한다.
5. RDS Snapshot 복원과 이전 app SHA 재배포를 비운영 복원 대상으로 검증한다.

검증:

- [ ] 로그에 토큰, Presigned URL, DB 비밀번호와 개인정보가 노출되지 않는다.
- [ ] Agent disk metric과 모든 Alarm이 데이터를 수신한다.
- [ ] 테스트 5xx 요청이 Log Metric Filter와 Alarm에 집계된다.
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
| 미작성 | 미작성 | 최초 운영 인프라 구성 | 전체 | 미검증 | 최초 구성 Blocker와 단계별 검증 완료 필요 |

## Terraform 재검토 기준

다음 중 하나가 발생하면 Console 운영을 계속하지 않고 Terraform 도입을 검토한다.

- 운영 외 AWS 환경을 추가한다.
- 두 명 이상이 정기적으로 인프라를 변경한다.
- 동일 구성을 반복 생성하거나 재해 복구 시간을 단축해야 한다.
- Console 설정 누락이나 문서와 실제 상태의 불일치가 반복된다.
- 감사 가능한 변경 승인과 자동 drift 탐지가 필요해진다.
