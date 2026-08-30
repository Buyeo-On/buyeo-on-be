package com.buyeoon.mission.application;

public class MissionChoiceInUseException extends RuntimeException {

	public MissionChoiceInUseException() {
		super("이미 제출 기록이 있는 보기는 수정할 수 없습니다.");
	}
}
