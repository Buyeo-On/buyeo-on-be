package com.buyeoon.place.repository;

import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.SavedPlaceEntity;
import com.buyeoon.place.entity.SavedPlaceId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 카테고리 지정 여부, 커서 유무, 위치 전달 여부를 각각 다른 쿼리로 나눠 둔다. nullable 파라미터를
 * {@code IS NULL OR ...}로 합치면 PostgreSQL이 파라미터 타입을 정하지 못해 실패한다. 위치가 있어도 정렬은 항상
 * 저장 시각순이며 거리는 표시용으로만 SELECT에 추가된다.
 */
public interface SavedPlaceRepository extends JpaRepository<SavedPlaceEntity, SavedPlaceId> {

	@Modifying
	@Query(value = "INSERT INTO saved_places (member_id, place_id) VALUES (:memberId, :placeId) "
			+ "ON CONFLICT (member_id, place_id) DO NOTHING", nativeQuery = true)
	void insertIfAbsent(@Param("memberId") UUID memberId, @Param("placeId") UUID placeId);

	/** 조회한 페이지의 장소만 한 번에 확인해 장소 수만큼 질의가 늘어나지 않게 한다. */
	@Query("""
			SELECT s.id.placeId
			FROM SavedPlaceEntity s
			WHERE s.id.memberId = :memberId
			  AND s.id.placeId IN :placeIds
			""")
	List<UUID> findSavedPlaceIds(@Param("memberId") UUID memberId, @Param("placeIds") Collection<UUID> placeIds);

	default boolean existsByMemberIdAndPlaceId(UUID memberId, UUID placeId) {
		return existsById(new SavedPlaceId(memberId, placeId));
	}

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt)
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findFromStart(@Param("memberId") UUID memberId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt)
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND p.category = :category
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findFromStartByCategory(@Param("memberId") UUID memberId,
			@Param("category") PlaceCategory category, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt)
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND (s.savedAt < :savedAt
			       OR (s.savedAt = :savedAt AND p.id > :placeId))
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findAfter(@Param("memberId") UUID memberId, @Param("savedAt") Instant savedAt,
			@Param("placeId") UUID placeId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt)
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND p.category = :category
			  AND (s.savedAt < :savedAt
			       OR (s.savedAt = :savedAt AND p.id > :placeId))
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findAfterByCategory(@Param("memberId") UUID memberId,
			@Param("category") PlaceCategory category, @Param("savedAt") Instant savedAt,
			@Param("placeId") UUID placeId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findFromStartWithDistance(@Param("memberId") UUID memberId,
			@Param("latitude") double latitude, @Param("longitude") double longitude, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND p.category = :category
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findFromStartByCategoryWithDistance(@Param("memberId") UUID memberId,
			@Param("category") PlaceCategory category, @Param("latitude") double latitude,
			@Param("longitude") double longitude, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND (s.savedAt < :savedAt
			       OR (s.savedAt = :savedAt AND p.id > :placeId))
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findAfterWithDistance(@Param("memberId") UUID memberId,
			@Param("savedAt") Instant savedAt, @Param("placeId") UUID placeId, @Param("latitude") double latitude,
			@Param("longitude") double longitude, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.SavedPlaceProjection(p, s.savedAt,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM SavedPlaceEntity s
			JOIN PlaceEntity p ON p.id = s.id.placeId
			WHERE s.id.memberId = :memberId
			  AND p.category = :category
			  AND (s.savedAt < :savedAt
			       OR (s.savedAt = :savedAt AND p.id > :placeId))
			ORDER BY s.savedAt DESC, p.id ASC
			""")
	List<SavedPlaceProjection> findAfterByCategoryWithDistance(@Param("memberId") UUID memberId,
			@Param("category") PlaceCategory category, @Param("savedAt") Instant savedAt,
			@Param("placeId") UUID placeId, @Param("latitude") double latitude, @Param("longitude") double longitude,
			Pageable pageable);
}
