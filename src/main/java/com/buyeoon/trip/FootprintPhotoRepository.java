package com.buyeoon.trip;

import com.buyeoon.mission.entity.MissionPhotoEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 발자취 조회와 여행 사진 조회가 요청자 소유 여행의 미션 사진을 읽기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. 사진과
 * 장소는 별도 도메인 테이블이지만 매핑된 연관관계가 없으므로 JPQL ad-hoc join으로 연결한다.
 */
public interface FootprintPhotoRepository extends JpaRepository<MissionPhotoEntity, UUID> {

	/** 업로드 시각 오름차순으로 요청자 소유 여행의 사진만 조회한다. */
	List<MissionPhotoEntity> findByMemberIdAndTripIdOrderByUploadedAtAsc(UUID memberId, UUID tripId);

	/** 업로드 시각 오름차순으로 요청자 소유 여행의 사진을 촬영 미션이 연결된 장소명과 함께 조회한다. */
	@Query("""
			SELECT new com.buyeoon.trip.TripPhotoProjection(photo, place.name)
			FROM MissionPhotoEntity photo
			JOIN MissionEntity mission ON mission.id = photo.missionId
			JOIN PlaceEntity place ON place.id = mission.placeId
			WHERE photo.memberId = :memberId AND photo.tripId = :tripId
			ORDER BY photo.uploadedAt ASC
			""")
	List<TripPhotoProjection> findWithPlaceNameByMemberIdAndTripId(@Param("memberId") UUID memberId,
			@Param("tripId") UUID tripId);
}
