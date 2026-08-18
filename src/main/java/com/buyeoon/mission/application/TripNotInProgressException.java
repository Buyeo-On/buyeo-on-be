package com.buyeoon.mission.application;

public class TripNotInProgressException extends RuntimeException {

	public TripNotInProgressException() {
		super("진행 중인 여행에서만 요청할 수 있습니다.");
	}
}
