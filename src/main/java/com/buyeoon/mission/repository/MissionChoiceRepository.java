package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionChoiceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionChoiceRepository extends JpaRepository<MissionChoiceEntity, UUID> {

	List<MissionChoiceEntity> findByMissionIdOrderBySortOrderAsc(UUID missionId);

	void deleteByMissionId(UUID missionId);
}
