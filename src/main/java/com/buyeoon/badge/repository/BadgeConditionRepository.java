package com.buyeoon.badge.repository;

import com.buyeoon.badge.entity.BadgeConditionEntity;
import com.buyeoon.badge.entity.BadgeConditionId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BadgeConditionRepository extends JpaRepository<BadgeConditionEntity, BadgeConditionId> {

	List<BadgeConditionEntity> findByIdBadgeIdIn(Collection<UUID> badgeIds);

	void deleteByIdBadgeId(UUID badgeId);
}
