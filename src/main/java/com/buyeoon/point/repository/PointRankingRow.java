package com.buyeoon.point.repository;

import java.util.UUID;

/** 랭킹 페이지에 표시할 참여 회원의 읽기 projection이다. */
public record PointRankingRow(UUID memberId, long rank, String displayName, String characterImageKey,
		long cumulativeEarned) {
}
