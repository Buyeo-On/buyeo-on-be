package com.buyeoon.place.repository;

import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 카테고리 지정 여부와 커서 유무를 각각 다른 쿼리로 나눠 둔다. nullable 파라미터를 {@code IS NULL OR ...}로
 * 합치면 PostgreSQL이 파라미터 타입을 정하지 못해 실패한다.
 */
public interface PlaceQueryRepository extends JpaRepository<PlaceEntity, UUID> {

	@Query("""
			SELECT new com.buyeoon.place.repository.PlaceProjection(p,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM PlaceEntity p
			WHERE p.deletedAt IS NULL
			ORDER BY 2 ASC, p.id ASC
			""")
	List<PlaceProjection> findFromStart(@Param("latitude") double latitude, @Param("longitude") double longitude,
			Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.PlaceProjection(p,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM PlaceEntity p
			WHERE p.category = :category
			  AND p.deletedAt IS NULL
			ORDER BY 2 ASC, p.id ASC
			""")
	List<PlaceProjection> findFromStartByCategory(@Param("category") PlaceCategory category,
			@Param("latitude") double latitude, @Param("longitude") double longitude, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.PlaceProjection(p,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM PlaceEntity p
			WHERE p.deletedAt IS NULL
			  AND (ST_Distance(p.location,
			          ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326))
			      > :distanceMeters
			   OR (ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326))
			       >= :distanceMeters
			       AND p.id > :placeId))
			ORDER BY 2 ASC, p.id ASC
			""")
	List<PlaceProjection> findAfter(@Param("latitude") double latitude, @Param("longitude") double longitude,
			@Param("distanceMeters") double distanceMeters, @Param("placeId") UUID placeId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.PlaceProjection(p,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM PlaceEntity p
			WHERE p.category = :category
			  AND p.deletedAt IS NULL
			  AND (ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326))
			       > :distanceMeters
			       OR (ST_Distance(p.location,
			               ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326))
			           >= :distanceMeters
			           AND p.id > :placeId))
			ORDER BY 2 ASC, p.id ASC
			""")
	List<PlaceProjection> findAfterByCategory(@Param("category") PlaceCategory category,
			@Param("latitude") double latitude, @Param("longitude") double longitude,
			@Param("distanceMeters") double distanceMeters, @Param("placeId") UUID placeId, Pageable pageable);

	@Query("""
			SELECT new com.buyeoon.place.repository.PlaceProjection(p,
			       ST_Distance(p.location,
			           ST_SetSRID(ST_MakePoint(cast(:longitude as double), cast(:latitude as double)), 4326)))
			FROM PlaceEntity p
			WHERE p.id = :placeId
			""")
	Optional<PlaceProjection> findByIdWithDistance(@Param("placeId") UUID placeId, @Param("latitude") double latitude,
			@Param("longitude") double longitude);

	Optional<PlaceEntity> findBySourceNameAndExternalId(String sourceName, String externalId);

	Page<PlaceEntity> findByDeletedAtIsNull(Pageable pageable);

	Page<PlaceEntity> findByDeletedAtIsNullAndCategory(PlaceCategory category, Pageable pageable);
}
