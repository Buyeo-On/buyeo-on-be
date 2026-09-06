package com.buyeoon.mission.application;

public class PhotoMissionConsentRequiredException extends RuntimeException {

	public PhotoMissionConsentRequiredException() {
		super("사진 미션 이용에 먼저 동의해 주세요.");
	}
}
