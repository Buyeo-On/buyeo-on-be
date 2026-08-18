package com.buyeoon.mission.application;

public class OutsideParticipationRadiusException extends RuntimeException {

	public OutsideParticipationRadiusException() {
		super("위치 인증에 실패했습니다.");
	}
}
