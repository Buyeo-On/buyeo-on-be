package com.buyeoon.common.storage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 사진 인증 미션의 S3 Presigned PUT URL 발급 seam이다. 테스트는 실제 AWS 호출 없이 이 인터페이스를 대체한다.
 */
public interface MissionPhotoUploadPresigner {

	MissionPhotoUploadTarget presign(String objectKey, UUID memberId, String contentType, long fileSizeBytes);

	record MissionPhotoUploadTarget(String uploadUrl, Map<String, String> headers, Instant expiresAt) {

		public MissionPhotoUploadTarget {
			headers = Map.copyOf(headers);
		}
	}
}
