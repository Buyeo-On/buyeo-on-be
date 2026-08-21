package com.buyeoon.trip;

import com.buyeoon.place.entity.PlaceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 발자취 조회가 방문 기록의 장소 정보를 읽기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface FootprintPlaceRepository extends JpaRepository<PlaceEntity, UUID> {
}
