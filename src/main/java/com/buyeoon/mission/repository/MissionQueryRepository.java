package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 미션 위치는 미션 자신의 {@code missions.location}을 사용한다. 미션과 장소는 별도 도메인 테이블이지만 매핑된
 * 연관관계가 없으므로 JPQL ad-hoc join으로 연결한다.
 */
public interface MissionQueryRepository extends JpaRepository<MissionEntity, UUID> {

	@Query("""
			SELECT new com.buyeoon.mission.repository.NearbyMissionProjection(m, p,
			       ST_Distance(m.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)),
			       participation)
			FROM MissionEntity m
			JOIN PlaceEntity p ON p.id = m.placeId
			LEFT JOIN MissionParticipationEntity participation
			    ON participation.missionId = m.id AND participation.tripId = :tripId
			WHERE m.deletedAt IS NULL
			  AND p.deletedAt IS NULL
			  AND ST_Distance(m.location,
			          ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)) <= 500
			ORDER BY 3 ASC, m.id ASC
			""")
	List<NearbyMissionProjection> findNearby(@Param("tripId") UUID tripId, @Param("latitude") double latitude,
			@Param("longitude") double longitude);

	@Query("""
			SELECT new com.buyeoon.mission.repository.NearbyMissionProjection(m, p,
			       ST_Distance(m.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)),
			       participation)
			FROM MissionEntity m
			JOIN PlaceEntity p ON p.id = m.placeId
			LEFT JOIN MissionParticipationEntity participation
			    ON participation.missionId = m.id AND participation.tripId = :tripId
			WHERE m.id = :missionId
			  AND m.deletedAt IS NULL
			  AND p.deletedAt IS NULL
			""")
	Optional<NearbyMissionProjection> findDetail(@Param("missionId") UUID missionId, @Param("tripId") UUID tripId,
			@Param("latitude") double latitude, @Param("longitude") double longitude);

	@Query("""
			SELECT new com.buyeoon.mission.repository.MissionPlaceDistanceProjection(m, p,
			       ST_Distance(m.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM MissionEntity m
			JOIN PlaceEntity p ON p.id = m.placeId
			WHERE m.id = :missionId
			  AND m.deletedAt IS NULL
			  AND p.deletedAt IS NULL
			""")
	Optional<MissionPlaceDistanceProjection> findWithDistance(@Param("missionId") UUID missionId,
			@Param("latitude") double latitude, @Param("longitude") double longitude);

	/** 500m 반경 제한 없이 스페셜 퀴즈 지오펜스 등록에 필요한 미션·참여 상태만 조회한다. 거리 계산은 필요 없다. */
	@Query("""
			SELECT new com.buyeoon.mission.repository.SpecialQuizGeofenceProjection(m, participation)
			FROM MissionEntity m
			JOIN PlaceEntity p ON p.id = m.placeId
			LEFT JOIN MissionParticipationEntity participation
			    ON participation.missionId = m.id AND participation.tripId = :tripId
			WHERE m.deletedAt IS NULL
			  AND p.deletedAt IS NULL
			  AND m.maxAttempts IS NOT NULL
			ORDER BY m.id ASC
			""")
	List<SpecialQuizGeofenceProjection> findSpecialQuizzes(@Param("tripId") UUID tripId);

	Page<MissionEntity> findByPlaceId(UUID placeId, Pageable pageable);

	List<MissionEntity> findByPlaceIdAndDeletedAtIsNull(UUID placeId);
}
