package com.buyeoon.mission.application;

public class MissionPhotoNotFoundException extends RuntimeException {

	public MissionPhotoNotFoundException() {
		super("존재하지 않거나 본인이 발급받지 않은 사진입니다.");
	}
}
