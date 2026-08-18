package com.buyeoon.common.storage;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3MissionPhotoUploadPresigner implements MissionPhotoUploadPresigner {

	private static final Duration VALIDITY = Duration.ofMinutes(10);
	private static final String OWNER_METADATA_KEY = "member-id";
	private static final String DECLARED_FILE_SIZE_METADATA_KEY = "declared-file-size-bytes";
	private static final String DECLARED_CONTENT_TYPE_METADATA_KEY = "declared-content-type";

	private final String bucket;
	private final S3Presigner presigner;

	public S3MissionPhotoUploadPresigner(@Value("${storage.images.bucket:}") String bucket,
			@Value("${storage.images.region:ap-northeast-2}") String region) {
		this.bucket = bucket;
		this.presigner = S3Presigner.builder().region(Region.of(region)).build();
	}

	@Override
	public MissionPhotoUploadTarget presign(String objectKey, UUID memberId, String contentType, long fileSizeBytes) {
		if (bucket.isBlank()) {
			throw new IllegalStateException("IMAGE_BUCKET 설정이 필요합니다.");
		}
		if (!objectKey.startsWith("private/")) {
			throw new IllegalArgumentException("비공개 이미지 객체 키가 아닙니다.");
		}
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(OWNER_METADATA_KEY, memberId.toString());
		metadata.put(DECLARED_FILE_SIZE_METADATA_KEY, Long.toString(fileSizeBytes));
		metadata.put(DECLARED_CONTENT_TYPE_METADATA_KEY, contentType);
		PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucket).key(objectKey)
				.contentType(contentType).metadata(metadata).build();
		var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(VALIDITY)
				.putObjectRequest(putObjectRequest).build());

		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", contentType);
		metadata.forEach((key, value) -> headers.put("x-amz-meta-" + key, value));
		return new MissionPhotoUploadTarget(presigned.url().toExternalForm(), headers, Instant.now().plus(VALIDITY));
	}

	@PreDestroy
	void close() {
		presigner.close();
	}
}
