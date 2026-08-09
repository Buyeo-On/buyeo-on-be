# ADR-011: iOS 단일 출시를 Flutter 기능 중심 구조로 구현한다

- **상태:** 승인됨
- **결정일:** 2026-08-09
## 맥락
첫 출시는 한 플랫폼에 집중하되 향후 Android 등 멀티플랫폼 확장 가능성을 유지해야 한다.
## 결정
- MVP는 iOS만 지원하고 클라이언트 기술은 Flutter를 유지한다.
- feature-first 구조와 Riverpod을 사용한다.
- 각 기능은 presentation, application, domain, data 계층으로 나눈다.
- OpenAPI에서 Dart 클라이언트를 생성하고 feature Repository가 이를 감싼다.
- 오프라인 쓰기·재전송 큐·영구 로컬 DB는 지원하지 않는다.
- Flutter CI는 현재 `flutter analyze`, `flutter test`만 실행한다.
- iOS 서명과 배포 자동화는 TBD다.
## 결과
- iOS 출시에 집중하면서 Flutter 기반의 향후 플랫폼 확장 여지를 유지한다.
- 네트워크 단절 중 쓰기 작업은 수행할 수 없다.
## 기각한 대안
- 첫 출시부터 iOS·Android·Web을 모두 지원하는 방식
- Flutter Web을 동일 범위에 포함하는 방식
