# ADR-008: 이미지는 Private S3와 Presigned URL로 제공한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
- **개정일:** 2026-08-12
## 맥락
공개 정적 콘텐츠와 회원의 비공개 미션 사진을 애플리케이션 서버가 중계하지 않고 전송하되, 비공개 사진에는 조회 권한과 삭제 정책을 적용해야 한다. MVP 트래픽에서 CloudFront Signed URL, OAC와 별도 서명 키를 운영하는 복잡도는 효용보다 크다.
## 결정
- 하나의 Private S3 Bucket에서 공개 정적 콘텐츠는 `public/`, 비공개 미션 사진은 `private/` prefix로 분리한다.
- S3 Block Public Access와 SSE-S3를 사용하고 객체를 공개 S3 URL로 제공하지 않는다.
- 업로드는 Presigned PUT URL을 사용하고 서버가 소유자, 크기와 MIME 타입을 검증한다.
- 공개 콘텐츠는 콘텐츠 API가, 비공개 사진은 회원 소유권을 확인한 사진 API가 각각 10분 유효한 S3 Presigned GET URL을 생성한다.
- 공개 콘텐츠 테이블에는 만료되는 Presigned URL 대신 `public/` prefix의 S3 객체 키를 `image_key`로 저장한다.
- 24시간 미제출 객체, 탈퇴 후 30일 경과 사진과 미완료 multipart upload를 삭제한다.
- 개인정보 삭제 시 이전 객체 버전이 남지 않도록 이 Bucket은 버전 관리를 사용하지 않는다.
## 결과
- 이미지 전송 부하는 S3가 담당하면서 비공개 사진의 회원별 접근 제어를 유지한다.
- CloudFront Distribution, OAC, Trusted Key Group과 서명 키 관리가 필요하지 않다.
- 공개 콘텐츠도 영구 공개 URL이 아니라 API가 발급한 임시 URL을 사용하며 CDN cache를 사용하지 않는다.
- Presigned GET URL은 만료 전까지 소지자가 사용할 수 있는 bearer URL이므로 로그에 남기지 않고 앱이 외부로 공유하지 않는다.
- 기존 `image_url` 값이 URL이면 `public/` 객체 키로 먼저 교체해야 `image_key` 마이그레이션을 적용할 수 있다.
- `image_url`을 `image_key`로 바꾸는 V5는 최초 운영 배포 전에 적용하며, 적용 후 배포한 앱을 rollback 가능한 최초 운영 기준 SHA로 삼는다.
## 기각한 대안
- 사진을 공개 S3 URL로 제공하는 방식
- 사진 바이트를 Spring 서버가 중계하는 방식
- CloudFront OAC와 Signed URL을 운영하는 방식
- 공개·비공개 콘텐츠마다 별도 S3 Bucket을 운영하는 방식
- 삭제된 개인정보가 이전 버전에 남는 S3 버전 관리
