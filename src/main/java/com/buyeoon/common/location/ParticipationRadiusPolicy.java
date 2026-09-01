package com.buyeoon.common.location;

/** 미션 참여·근접 알림에 공통으로 쓰는 위치 인증 반경 정책이다. */
public final class ParticipationRadiusPolicy {

	/** 미션 참여와 스페셜 퀴즈 근접 알림을 허용하는 고정 반경(m). 경계를 포함한다. */
	public static final int PARTICIPATION_RADIUS_METERS = 30;

	private ParticipationRadiusPolicy() {
	}
}
