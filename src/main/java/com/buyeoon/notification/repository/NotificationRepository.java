package com.buyeoon.notification.repository;

import com.buyeoon.notification.entity.NotificationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

	Optional<NotificationEntity> findByIdAndMemberId(UUID id, UUID memberId);
}
