package com.buyeoon.member.application;

public class TermVersionOutdatedException extends RuntimeException {

	public TermVersionOutdatedException() {
		super("현재 약관 버전과 일치하지 않습니다.");
	}
}
