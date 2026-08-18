package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionPhotoEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionPhotoRepository extends JpaRepository<MissionPhotoEntity, UUID> {
}
