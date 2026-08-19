package com.buyeoon.mission.application;

public class InvalidMissionSubmissionException extends RuntimeException {

	public InvalidMissionSubmissionException() {
		super("요청 값이 올바르지 않습니다.");
	}
}
