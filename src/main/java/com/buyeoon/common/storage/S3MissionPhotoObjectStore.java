package com.buyeoon.common.storage;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Service
public class S3MissionPhotoObjectStore implements MissionPhotoObjectStore {

	private static final String OWNER_METADATA_KEY = "member-id";
	private static final String DECLARED_FILE_SIZE_METADATA_KEY = "declared-file-size-bytes";
	private static final String DECLARED_CONTENT_TYPE_METADATA_KEY = "declared-content-type";

	private final String bucket;
	private final S3Client s3Client;

	public S3MissionPhotoObjectStore(@Value("${storage.images.bucket:}") String bucket,
			@Value("${storage.images.region:ap-northeast-2}") String region) {
		this.bucket = bucket;
		this.s3Client = S3Client.builder().region(Region.of(region)).build();
	}

	@Override
	public Optional<MissionPhotoObject> head(String objectKey) {
		if (bucket.isBlank()) {
			throw new IllegalStateException("IMAGE_BUCKET 설정이 필요합니다.");
		}
		try {
			HeadObjectResponse response = s3Client
					.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
			Map<String, String> metadata = response.metadata();
			String ownerId = metadata.get(OWNER_METADATA_KEY);
			String declaredContentType = metadata.get(DECLARED_CONTENT_TYPE_METADATA_KEY);
			String declaredFileSizeBytes = metadata.get(DECLARED_FILE_SIZE_METADATA_KEY);
			if (ownerId == null || declaredFileSizeBytes == null) {
				// 발급 경로를 거치지 않아 서명된 메타데이터가 없는 객체다. 존재하지 않는 것과 동일하게 취급한다.
				return Optional.empty();
			}
			return Optional.of(new MissionPhotoObject(UUID.fromString(ownerId), response.contentType(),
					response.contentLength(), declaredContentType, Long.parseLong(declaredFileSizeBytes)));
		} catch (NoSuchKeyException exception) {
			return Optional.empty();
		} catch (IllegalArgumentException exception) {
			// 손상된 메타데이터(UUID·숫자 형식 오류)다. 존재하지 않는 것과 동일하게 취급한다.
			return Optional.empty();
		}
	}

	@PreDestroy
	void close() {
		s3Client.close();
	}
}
