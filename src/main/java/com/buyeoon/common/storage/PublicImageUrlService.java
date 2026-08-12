package com.buyeoon.common.storage;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class PublicImageUrlService {

	private static final Duration VALIDITY = Duration.ofMinutes(10);

	private final String bucket;
	private final S3Presigner presigner;

	public PublicImageUrlService(@Value("${storage.images.bucket:}") String bucket,
			@Value("${storage.images.region:ap-northeast-2}") String region) {
		this.bucket = bucket;
		this.presigner = S3Presigner.builder().region(Region.of(region)).build();
	}

	public String create(String imageKey) {
		if (bucket.isBlank()) {
			throw new IllegalStateException("IMAGE_BUCKET 설정이 필요합니다.");
		}
		if (!imageKey.startsWith("public/")) {
			throw new IllegalArgumentException("공개 이미지 객체 키가 아닙니다.");
		}
		GetObjectRequest objectRequest = GetObjectRequest.builder().bucket(bucket).key(imageKey).build();
		return presigner.presignGetObject(
				GetObjectPresignRequest.builder().signatureDuration(VALIDITY).getObjectRequest(objectRequest).build())
				.url().toExternalForm();
	}

	@PreDestroy
	void close() {
		presigner.close();
	}
}
