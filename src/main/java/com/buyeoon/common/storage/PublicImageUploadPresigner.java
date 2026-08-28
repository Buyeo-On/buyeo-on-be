package com.buyeoon.common.storage;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자용 공개 이미지(장소/배지 등)의 S3 Presigned PUT URL 발급 seam이다. 테스트는 실제 AWS 호출 없이 이 인터페이스를
 * 대체한다.
 */
public interface PublicImageUploadPresigner {

	PublicImageUploadTarget presign(String objectKey, String contentType, long fileSizeBytes);

	record PublicImageUploadTarget(String uploadUrl, Map<String, String> headers, Instant expiresAt) {

		public PublicImageUploadTarget {
			headers = Map.copyOf(headers);
		}
	}
}
