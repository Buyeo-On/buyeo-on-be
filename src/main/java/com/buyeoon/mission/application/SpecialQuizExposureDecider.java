package com.buyeoon.mission.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 스페셜 퀴즈(최대 도전 횟수가 있는 객관식·OX 미션)를 여행·KST 날짜·미션 ID로 정해지는 시드로 하루
 * {@value #EXPOSURE_THRESHOLD_PERCENT}%만 노출할지 결정한다.
 */
@Component
public class SpecialQuizExposureDecider {

	private static final int EXPOSURE_THRESHOLD_PERCENT = 20;
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	public boolean isExposedToday(UUID tripId, UUID missionId) {
		String seed = tripId + "|" + LocalDate.now(ASIA_SEOUL) + "|" + missionId;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
			int bucket = (ByteBuffer.wrap(hash, 0, 4).getInt() & 0x7fffffff) % 100;
			return bucket < EXPOSURE_THRESHOLD_PERCENT;
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}
}
