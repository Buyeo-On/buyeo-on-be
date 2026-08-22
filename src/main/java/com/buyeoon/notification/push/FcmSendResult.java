package com.buyeoon.notification.push;

import java.util.List;

/**
 * 한 batch 발송 결과다. {@code acceptedCount}는 FCM이 접수한 토큰 수이며 기기 전달 완료를 의미하지 않는다.
 * {@code unregisteredTokens}는 {@code UNREGISTERED} 오류가 반환돼 삭제 대상인 등록 토큰이다.
 */
public record FcmSendResult(int acceptedCount, int failedCount, List<String> unregisteredTokens) {

	public FcmSendResult {
		unregisteredTokens = List.copyOf(unregisteredTokens);
	}
}
