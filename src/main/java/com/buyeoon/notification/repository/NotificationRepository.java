package com.buyeoon.notification.repository;

import com.buyeoon.notification.entity.NotificationEntity;
import com.buyeoon.notification.entity.NotificationType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

	Optional<NotificationEntity> findByIdAndMemberId(UUID id, UUID memberId);

	/** 쿨다운 판정에 사용한다. 회원에게 해당 유형의 알림이 기준 시각 이후로 발생했는지 확인한다. */
	boolean existsByMemberIdAndTypeAndOccurredAtAfter(UUID memberId, NotificationType type, Instant occurredAtAfter);

	/** 스페셜 퀴즈 근접 알림처럼 대상(미션)마다 독립적으로 쿨다운을 거는 알림의 재발송 여부 판정에 사용한다. */
	boolean existsByMemberIdAndTypeAndTargetIdAndOccurredAtAfter(UUID memberId, NotificationType type, UUID targetId,
			Instant occurredAtAfter);
}
