package com.buyeoon.member.application;

public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("멱등성 키가 다른 요청에 사용되었습니다.");
	}
}
