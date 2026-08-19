package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionSubmissionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmissionEntity, UUID> {
}
