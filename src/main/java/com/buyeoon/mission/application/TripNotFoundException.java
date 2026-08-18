package com.buyeoon.mission.application;

public class TripNotFoundException extends RuntimeException {

	public TripNotFoundException() {
		super("존재하지 않거나 본인 소유가 아닌 여행입니다.");
	}
}
