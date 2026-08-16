package com.buyeoon.place.repository;

import com.buyeoon.place.entity.SavedPlaceEntity;
import com.buyeoon.place.entity.SavedPlaceId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedPlaceRepository extends JpaRepository<SavedPlaceEntity, SavedPlaceId> {

	/** 조회한 페이지의 장소만 한 번에 확인해 장소 수만큼 질의가 늘어나지 않게 한다. */
	@Query("""
			SELECT s.id.placeId
			FROM SavedPlaceEntity s
			WHERE s.id.memberId = :memberId
			  AND s.id.placeId IN :placeIds
			""")
	List<UUID> findSavedPlaceIds(@Param("memberId") UUID memberId, @Param("placeIds") Collection<UUID> placeIds);
}
