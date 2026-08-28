package com.buyeoon.badge.application;

import com.buyeoon.badge.api.BadgeAdminListView;
import com.buyeoon.badge.api.BadgeAdminView;
import com.buyeoon.badge.api.BadgeAdminView.BadgeAdminConditionView;
import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeEntity;
import com.buyeoon.badge.repository.BadgeConditionRepository;
import com.buyeoon.badge.repository.BadgeRepository;
import com.buyeoon.common.storage.PublicImageUrlService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BadgeAdminQueryService {

	private final BadgeRepository badgeRepository;
	private final BadgeConditionRepository badgeConditionRepository;
	private final PublicImageUrlService imageUrls;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public BadgeAdminQueryService(BadgeRepository badgeRepository, BadgeConditionRepository badgeConditionRepository,
			PublicImageUrlService imageUrls) {
		this.badgeRepository = badgeRepository;
		this.badgeConditionRepository = badgeConditionRepository;
		this.imageUrls = imageUrls;
	}

	public BadgeAdminListView list(int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<BadgeEntity> result = badgeRepository.findAll(pageRequest);
		List<UUID> badgeIds = result.getContent().stream().map(BadgeEntity::getId).toList();
		Map<UUID, List<BadgeConditionEntity>> conditionsByBadgeId = badgeConditionRepository.findByIdBadgeIdIn(badgeIds)
				.stream().collect(Collectors.groupingBy(condition -> condition.getId().badgeId()));
		List<BadgeAdminView> items = result.getContent().stream()
				.map(badge -> toView(badge, conditionsByBadgeId.getOrDefault(badge.getId(), List.of()))).toList();
		return new BadgeAdminListView(items, page, size, result.getTotalElements(), result.getTotalPages());
	}

	public BadgeAdminView get(UUID badgeId) {
		BadgeEntity badge = badgeRepository.findById(badgeId).orElseThrow(BadgeNotFoundException::new);
		List<BadgeConditionEntity> conditions = badgeConditionRepository.findByIdBadgeIdIn(List.of(badgeId));
		return toView(badge, conditions);
	}

	private BadgeAdminView toView(BadgeEntity badge, List<BadgeConditionEntity> conditions) {
		List<BadgeAdminConditionView> conditionViews = conditions.stream()
				.map(condition -> new BadgeAdminConditionView(condition.getId().metricKey(), condition.getThreshold()))
				.toList();
		String imageUrl = badge.getImageKey() != null ? imageUrls.create(badge.getImageKey()) : null;
		return new BadgeAdminView(badge.getId(), badge.getCategory(), badge.getName(), badge.getDescription(),
				imageUrl, badge.getConditionText(), badge.getRetiredAt() != null, conditionViews);
	}
}
