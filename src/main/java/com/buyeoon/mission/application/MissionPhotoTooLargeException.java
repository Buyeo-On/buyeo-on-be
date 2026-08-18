package com.buyeoon.mission.application;

public class MissionPhotoTooLargeException extends RuntimeException {

	public MissionPhotoTooLargeException() {
		super("서버에 설정된 최대 업로드 크기를 초과했습니다.");
	}
}
