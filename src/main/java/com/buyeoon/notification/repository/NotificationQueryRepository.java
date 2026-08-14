package com.buyeoon.notification.repository;

import com.buyeoon.notification.entity.NotificationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationQueryRepository extends JpaRepository<NotificationEntity, UUID> {

	@Query("""
			SELECT new com.buyeoon.notification.repository.NotificationProjection(
			       n.id, n.type, n.title, n.body, (n.readAt IS NOT NULL),
			       n.occurredAt, n.targetType, n.targetId)
			FROM NotificationEntity n
			WHERE n.memberId = :memberId
			ORDER BY n.occurredAt DESC, n.id DESC
			""")
	List<NotificationProjection> findFromStart(@Param("memberId") UUID memberId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.notification.repository.NotificationProjection(
			       n.id, n.type, n.title, n.body, (n.readAt IS NOT NULL),
			       n.occurredAt, n.targetType, n.targetId)
			FROM NotificationEntity n
			WHERE n.memberId = :memberId
			  AND (n.occurredAt < :occurredAt OR (n.occurredAt = :occurredAt AND n.id < :notificationId))
			ORDER BY n.occurredAt DESC, n.id DESC
			""")
	List<NotificationProjection> findAfter(@Param("memberId") UUID memberId, @Param("occurredAt") Instant occurredAt,
			@Param("notificationId") UUID notificationId, Pageable pageable);
}
