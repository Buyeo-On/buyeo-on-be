package com.buyeoon.mission.repository;

import com.buyeoon.mission.entity.MissionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
			WHERE ST_Distance(m.location,
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
			""")
	Optional<MissionPlaceDistanceProjection> findWithDistance(@Param("missionId") UUID missionId,
			@Param("latitude") double latitude, @Param("longitude") double longitude);
}
