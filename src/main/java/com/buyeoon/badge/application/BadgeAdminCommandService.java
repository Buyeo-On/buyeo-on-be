package com.buyeoon.badge.application;

import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.badge.api.BadgeAdminCreateRequest;
import com.buyeoon.badge.api.BadgeAdminUpdateRequest;
import com.buyeoon.badge.api.BadgeConditionRequest;
import com.buyeoon.badge.api.InvalidBadgeAdminRequestException;
import com.buyeoon.badge.entity.BadgeCategory;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.common.storage.PublicImageObjectStore;
import com.buyeoon.common.storage.PublicImageObjectStore.PublicImageObject;
import com.buyeoon.common.storage.PublicImageUploadPresigner;
import com.buyeoon.common.storage.PublicImageUploadPresigner.PublicImageUploadTarget;
import com.buyeoon.common.storage.PublicImageUploadUrlView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BadgeAdminCommandService {

	private static final String IMAGE_KEY_PREFIX = "public/";
	private static final String UPLOAD_KEY_PREFIX = "public/badges/";
	private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png",
			"image/webp", "webp");

	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final PublicImageUploadPresigner publicImageUploadPresigner;
	private final PublicImageObjectStore publicImageObjectStore;
	private final long maxUploadBytes;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public BadgeAdminCommandService(BadgeRepository badgeRepository, BadgeConditionRepository badgeConditionRepository,
			PublicImageUploadPresigner publicImageUploadPresigner, PublicImageObjectStore publicImageObjectStore,
			@Value("${storage.images.max-upload-bytes:10485760}") long maxUploadBytes) {
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.publicImageUploadPresigner = publicImageUploadPresigner;
		this.publicImageObjectStore = publicImageObjectStore;
		this.maxUploadBytes = maxUploadBytes;
	}

	@Transactional
	public UUID create(BadgeAdminCreateRequest request) {
		BadgeCategory category = category(request.category());
		String name = requiredText(request.name());
		String description = requiredText(request.description());
		String conditionText = requiredText(request.conditionText());
		String imageKey = imageKey(request.imageKey());
		List<BadgeConditionRequest> conditions = validatedConditions(request.conditions());

		if (request.active() && conditions.isEmpty()) {
			throw new InvalidBadgeAdminRequestException();
		}

		BadgeEntity badge = BadgeEntity.create(category, name, description, imageKey, conditionText);
		if (!request.active()) {
			badge.retire();
		}
		badgeRepository.save(badge);
		saveConditions(badge.getId(), conditions);
		return badge.getId();
	}

	@Transactional
	public void update(UUID badgeId, BadgeAdminUpdateRequest request) {
		BadgeEntity badge = badgeRepository.findById(badgeId).orElseThrow(BadgeNotFoundException::new);
		BadgeCategory category = category(request.category());
		String name = requiredText(request.name());
		String description = requiredText(request.description());
		String conditionText = requiredText(request.conditionText());
		String imageKey = imageKey(request.imageKey());
		List<BadgeConditionRequest> conditions = validatedConditions(request.conditions());

		if (badge.getRetiredAt() == null && conditions.isEmpty()) {
			throw new InvalidBadgeAdminRequestException();
		}

		badge.updateMetadata(category, name, description, imageKey, conditionText);
		badgeConditionRepository.deleteByIdBadgeId(badgeId);
		badgeConditionRepository.flush();
		saveConditions(badgeId, conditions);
	}

	@Transactional
	public void retire(UUID badgeId) {
		BadgeEntity badge = badgeRepository.findById(badgeId).orElseThrow(BadgeNotFoundException::new);
		badge.retire();
	}

	@Transactional
	public void activate(UUID badgeId) {
		BadgeEntity badge = badgeRepository.findById(badgeId).orElseThrow(BadgeNotFoundException::new);
		List<BadgeConditionEntity> conditions = badgeConditionRepository.findByIdBadgeIdIn(List.of(badgeId));
		if (conditions.isEmpty()) {
			throw new InvalidBadgeAdminRequestException();
		}
		badge.activate();
	}

	public PublicImageUploadUrlView createImageUploadUrl(String contentType, long fileSizeBytes) {
		if (contentType == null || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
			throw new InvalidBadgeAdminRequestException();
		}
		if (fileSizeBytes <= 0 || fileSizeBytes > maxUploadBytes) {
			throw new InvalidBadgeAdminRequestException();
		}
		String key = UPLOAD_KEY_PREFIX + UUID.randomUUID() + "." + CONTENT_TYPE_EXTENSIONS.get(contentType);
		PublicImageUploadTarget target = publicImageUploadPresigner.presign(key, contentType, fileSizeBytes);
		return new PublicImageUploadUrlView(key, target.uploadUrl(), "PUT", target.headers(), 200,
				target.expiresAt());
	}

	private void saveConditions(UUID badgeId, List<BadgeConditionRequest> conditions) {
		for (BadgeConditionRequest condition : conditions) {
			badgeConditionRepository.save(BadgeConditionEntity.create(badgeId, condition.metricKey(),
					condition.threshold()));
		}
	}

	private List<BadgeConditionRequest> validatedConditions(List<BadgeConditionRequest> conditions) {
		if (conditions == null) {
			return List.of();
		}
		for (BadgeConditionRequest condition : conditions) {
			metric(condition.metricKey());
			if (condition.threshold() <= 0) {
				throw new InvalidBadgeAdminRequestException();
			}
		}
		return conditions;
	}

	private BadgeMetric metric(String value) {
		if (value == null) {
			throw new InvalidBadgeAdminRequestException();
		}
		try {
			return BadgeMetric.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new InvalidBadgeAdminRequestException();
		}
	}

	private BadgeCategory category(String value) {
		if (value == null) {
			throw new InvalidBadgeAdminRequestException();
		}
		try {
			return BadgeCategory.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new InvalidBadgeAdminRequestException();
		}
	}

	private String requiredText(String value) {
		if (value == null || value.isBlank()) {
			throw new InvalidBadgeAdminRequestException();
		}
		return value;
	}

	private String imageKey(String value) {
		if (value == null) {
			return null;
		}
		if (!value.startsWith(IMAGE_KEY_PREFIX)) {
			throw new InvalidBadgeAdminRequestException();
		}
		PublicImageObject object = publicImageObjectStore.head(value)
				.orElseThrow(InvalidBadgeAdminRequestException::new);
		if (object.fileSizeBytes() != object.declaredFileSizeBytes()
				|| !object.contentType().equals(object.declaredContentType())
				|| !ALLOWED_IMAGE_CONTENT_TYPES.contains(object.contentType())) {
			throw new InvalidBadgeAdminRequestException();
		}
		return value;
	}
}
