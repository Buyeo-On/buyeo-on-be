# ADR-006: 배포는 GitHub Actions·ECR·SSM, 인프라는 AWS Console로 관리한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-12
## 맥락
단일 EC2 배포는 재현 가능하고 롤백 가능해야 한다. 한편 작은 팀이 단일 운영 환경만 관리하는 MVP에서 Terraform 코드, Remote State와 별도 실행 권한을 함께 운영하는 비용은 현재 인프라 규모에 비해 크다.
## 결정
- Pull Request CI는 AWS 권한 없이 Testcontainers를 포함한 검증을 수행한다.
- GitHub Actions가 테스트 후 커밋 SHA 태그의 단일 애플리케이션 이미지를 ECR에 저장한다.
- SSM으로 EC2가 이미지를 pull하고 Docker Compose를 실행한다.
- Actuator 헬스 체크 실패 시 이전 커밋 SHA 이미지로 자동 롤백한다.
- MVP의 AWS 인프라는 운영 담당자가 [AWS Console 구성 Runbook](../aws-console-provisioning.md)에 따라 Console에서 생성·변경한다.
- 운영 담당자는 전용 IAM User의 Console 비밀번호와 MFA로 접근한다. 이 IAM User의 Access Key는 생성하지 않고 Root 계정을 일상 작업에 사용하지 않는다.
- Console 변경 전에 Runbook에 목적, 대상과 복구 방법을 기록해 검토받고, 실행 후 실제 리소스 정보와 검증 결과를 갱신한다.
- ECR push와 SSM 배포는 GitHub Actions만 수행하고 하나의 GitHub Automation OIDC IAM Role을 사용한다.
- GitHub Automation Role은 GitHub OIDC의 production Environment subject만 신뢰한다. production Environment는 보호된 `main`만 배포하도록 제한하고 승인을 요구한다.
- EC2 Instance Role은 SSM 연결, ECR pull, Parameter Store 조회, CloudWatch 전송과 이미지 S3 Bucket의 제한된 객체 작업에 사용하며 GitHub Automation Role과 분리한다.
- 장기 AWS Access Key를 사용하지 않는다.
- AWS에는 운영만 상시 유지하고 로컬 개발은 개발용 Supabase, CI는 Testcontainers를 사용한다.
## 결과
- 동일한 이미지를 배포·롤백하고 Console 변경 계획을 실행 전에 문서로 검토할 수 있다.
- Terraform state와 apply 권한 없이 MVP 인프라를 시작할 수 있다.
- Console 설정의 재현성과 변경 추적은 Terraform보다 약하므로 Runbook과 변경 이력을 운영 상태의 원본으로 유지해야 한다.
- 장기 Access Key는 없지만 관리자 권한의 IAM User Console 자격증명을 관리해야 하므로 MFA와 사용자 잠금이 주요 수동 운영 보안 경계가 된다.
- GitHub Automation Role은 ECR push와 SSM 배포 권한만 가지며 production Environment가 주요 배포 보안 경계가 된다.
- 인프라 규모, 운영 인력 또는 환경 수가 늘면 Terraform 도입을 재검토한다.
## 기각한 대안
- EC2에서 소스를 받아 직접 빌드하는 방식
- GitHub에 장기 AWS Access Key를 저장하는 방식
- MVP부터 Terraform, Remote State와 별도 Terraform Role을 운영하는 방식
- ECR push와 SSM 배포 Role을 각각 분리하는 방식
