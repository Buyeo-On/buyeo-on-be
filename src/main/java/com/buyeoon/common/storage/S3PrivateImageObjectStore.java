package com.buyeoon.common.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@Service
public final class S3PrivateImageObjectStore implements PrivateImageObjectStore {

	private final String bucket;
	private final S3Client s3Client;

	public S3PrivateImageObjectStore(@Value("${storage.images.bucket:}") String bucket,
			@Value("${storage.images.region:ap-northeast-2}") String region) {
		this.bucket = bucket;
		this.s3Client = S3Client.builder().region(Region.of(region)).build();
	}

	@Override
	public void delete(String objectKey) {
		if (bucket.isBlank()) {
			throw new IllegalStateException("IMAGE_BUCKET 설정이 필요합니다.");
		}
		if (!objectKey.startsWith("private/")) {
			throw new IllegalArgumentException("비공개 이미지 객체 키가 아닙니다.");
		}
		s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
	}

	@PreDestroy
	void close() {
		s3Client.close();
	}
}
