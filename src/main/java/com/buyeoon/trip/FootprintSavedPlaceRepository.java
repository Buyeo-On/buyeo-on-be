package com.buyeoon.trip;

import com.buyeoon.place.entity.SavedPlaceEntity;
import com.buyeoon.place.entity.SavedPlaceId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 발자취 조회가 방문 장소의 저장 여부를 읽기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface FootprintSavedPlaceRepository extends JpaRepository<SavedPlaceEntity, SavedPlaceId> {

	/** 조회한 방문 장소만 한 번에 확인해 장소 수만큼 질의가 늘어나지 않게 한다. */
	@Query("""
			SELECT s.id.placeId
			FROM SavedPlaceEntity s
			WHERE s.id.memberId = :memberId
			  AND s.id.placeId IN :placeIds
			""")
	List<UUID> findSavedPlaceIds(@Param("memberId") UUID memberId, @Param("placeIds") Collection<UUID> placeIds);
}
