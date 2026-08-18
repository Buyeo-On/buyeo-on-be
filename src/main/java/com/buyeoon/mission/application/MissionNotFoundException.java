package com.buyeoon.mission.application;

public class MissionNotFoundException extends RuntimeException {

	public MissionNotFoundException() {
		super("존재하지 않는 미션입니다.");
	}
}
