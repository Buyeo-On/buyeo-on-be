package com.buyeoon.point.repository;

/** 전체 참여자 수와 현재 회원의 랭킹 정보를 함께 담는 읽기 projection이다. */
public record PointRankingSummary(long totalParticipants, Long rank, String displayName, String characterImageKey,
		long cumulativeEarned) {

	/** 현재 회원이 참여 자격을 갖는지 순위 존재 여부로 판단한다. */
	public boolean eligible() {
		return rank != null;
	}
}
