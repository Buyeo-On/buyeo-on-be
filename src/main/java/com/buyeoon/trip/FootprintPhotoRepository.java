package com.buyeoon.trip;

import com.buyeoon.mission.entity.MissionPhotoEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 발자취 조회와 여행 사진 조회가 요청자 소유 여행의 미션 사진을 읽기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface FootprintPhotoRepository extends JpaRepository<MissionPhotoEntity, UUID> {

	/** 업로드 시각 오름차순으로 요청자 소유 여행의 사진만 조회한다. */
	List<MissionPhotoEntity> findByMemberIdAndTripIdOrderByUploadedAtAsc(UUID memberId, UUID tripId);
}
