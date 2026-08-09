# ADR-008: 미션 사진은 Private S3와 CloudFront Signed URL로 제공한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
## 맥락
미션 사진은 회원 비공개 데이터이며 애플리케이션 서버를 경유하지 않고 업로드하되 조회 권한과 삭제 정책을 적용해야 한다.
## 결정
- 공개 정적 콘텐츠와 비공개 사진 저장 영역을 분리한다.
- S3 Block Public Access와 SSE-S3를 사용하고 CloudFront만 OAC로 읽는다.
- 업로드는 Presigned PUT URL을 사용하고 서버가 소유자, 크기와 MIME 타입을 검증한다.
- 조회 권한 확인 후 `CloudFrontUtilities`가 네트워크 호출 없이 10분 Signed URL을 생성한다.
- CloudFront Trusted Key Group에는 공개 키를 두고 비공개 키는 Parameter Store에서 로드한다.
- 24시간 미제출 객체, 탈퇴 후 30일 경과 사진과 미완료 multipart upload를 삭제한다.
- 비공개 사진 버킷은 버전 관리를 사용하지 않는다.
## 결과
- 사진 전송 부하는 S3·CloudFront가 담당하면서 회원별 접근 제어를 유지한다.
- 키 관리와 고아 객체 정리 작업이 필요하다.
## 기각한 대안
- 사진을 공개 S3 URL로 제공하는 방식
- 사진 바이트를 Spring 서버가 중계하는 방식
- 삭제된 개인정보가 이전 버전에 남는 S3 버전 관리
