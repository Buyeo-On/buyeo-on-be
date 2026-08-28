package com.buyeoon.common.storage;

import java.util.Optional;

/**
 * 관리자용 공개 이미지에서 실제로 업로드된 S3 객체의 실체를 확인하는 seam이다. 테스트는 실제 AWS 호출 없이 이 인터페이스를
 * 대체해 크기·Content-Type의 일치·불일치와 존재하지 않는 객체를 조합해 검증한다.
 */
public interface PublicImageObjectStore {

	Optional<PublicImageObject> head(String objectKey);

	/**
	 * {@code contentType}·{@code fileSizeBytes}는 실제 업로드된 객체의 값이고,
	 * {@code declaredContentType}·{@code declaredFileSizeBytes}는 Presigned URL 발급
	 * 요청에서 서명된 메타데이터로 남긴 값이다.
	 */
	record PublicImageObject(String contentType, long fileSizeBytes, String declaredContentType,
			long declaredFileSizeBytes) {
	}
}
