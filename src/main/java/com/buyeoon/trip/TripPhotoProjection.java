package com.buyeoon.trip;

import com.buyeoon.mission.entity.MissionPhotoEntity;

/** 사진과 그 사진이 찍힌 미션이 연결된 장소명을 함께 담는 조회 전용 프로젝션이다. */
public record TripPhotoProjection(MissionPhotoEntity photo, String placeName) {
}
