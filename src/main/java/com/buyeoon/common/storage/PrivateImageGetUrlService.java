package com.buyeoon.common.storage;

/**
 * {@code private/} prefix 전용 GET Presigned URL 발급 seam이다(ADR-008). 발급 전 사진의 소유권
 * 확인은 호출하는 도메인 서비스의 책임이다. 테스트는 실제 AWS 호출 없이 이 인터페이스를 대체한다.
 */
public interface PrivateImageGetUrlService {

	String create(String objectKey);
}
