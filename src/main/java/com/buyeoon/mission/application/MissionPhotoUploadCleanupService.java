package com.buyeoon.mission.application;

import com.buyeoon.common.storage.PrivateImageObjectStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class MissionPhotoUploadCleanupService {

	private static final Logger LOGGER = LoggerFactory.getLogger(MissionPhotoUploadCleanupService.class);

	private final JdbcOperations jdbcOperations;
	private final PrivateImageObjectStore objectStore;
	private final TransactionTemplate transactions;
	private final int batchSize;

	public MissionPhotoUploadCleanupService(JdbcOperations jdbcOperations, PrivateImageObjectStore objectStore,
			PlatformTransactionManager transactionManager,
			@Value("${mission.photo-upload-cleanup.batch-size:100}") int batchSize) {
		if (batchSize < 1 || batchSize > 1_000) {
			throw new IllegalArgumentException("미제출 사진 파기 배치 크기는 1~1000이어야 합니다.");
		}
		this.jdbcOperations = jdbcOperations;
		this.objectStore = objectStore;
		this.transactions = new TransactionTemplate(transactionManager);
		this.batchSize = batchSize;
	}

	public int purgeDueUploads() {
		int purged = 0;
		List<UUID> duePhotoIds = jdbcOperations.query("""
				SELECT photo_id
				FROM mission_photo_upload_reservations
				WHERE cleanup_due_at <= CURRENT_TIMESTAMP
				ORDER BY cleanup_due_at, photo_id
				LIMIT ?
				""", (resultSet, rowNumber) -> resultSet.getObject("photo_id", UUID.class), batchSize);
		for (UUID photoId : duePhotoIds) {
			try {
				if (Boolean.TRUE.equals(transactions.execute(status -> purgeOne(photoId)))) {
					purged++;
				}
			} catch (RuntimeException exception) {
				LOGGER.warn("미제출 미션 사진 파기에 실패했습니다. photoId={}", photoId, exception);
			}
		}
		return purged;
	}

	private boolean purgeOne(UUID photoId) {
		List<String> objectKeys = jdbcOperations.query("""
				SELECT object_key
				FROM mission_photo_upload_reservations
				WHERE photo_id = ?
				  AND cleanup_due_at <= CURRENT_TIMESTAMP
				FOR UPDATE SKIP LOCKED
				""", (resultSet, rowNumber) -> resultSet.getString("object_key"), photoId);
		if (objectKeys.isEmpty()) {
			return false;
		}
		String objectKey = Objects.requireNonNull(objectKeys.getFirst());
		objectStore.delete(objectKey);
		jdbcOperations.update("DELETE FROM mission_photo_upload_reservations WHERE object_key = ?", objectKey);
		return true;
	}
}
