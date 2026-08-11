package com.buyeoon.member.application;

public class MemberWithdrawnException extends RuntimeException {

	public MemberWithdrawnException() {
		super("탈퇴한 회원은 로그인할 수 없습니다.");
	}
}
