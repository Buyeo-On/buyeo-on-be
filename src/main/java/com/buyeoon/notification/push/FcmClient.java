package com.buyeoon.notification.push;

import java.util.List;

/** FCM 발송을 추상화한 seam이다. 활성화 여부에 따라 실제 Firebase 구현이나 no-op 구현으로 대체된다. */
public interface FcmClient {

	/** 주어진 등록 토큰 전체에 같은 메시지를 발송한다. 호출자가 토큰을 최대 500개 단위로 나눠 호출한다. */
	void sendMulticast(List<String> registrationTokens, PushMessage message);
}
