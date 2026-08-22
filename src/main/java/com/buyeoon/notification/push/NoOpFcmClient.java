package com.buyeoon.notification.push;

import java.util.List;

/** {@code fcm.enabled=false}일 때 사용하는 비활성 구현이다. 어떤 발송 요청도 실제로 전달하지 않는다. */
public class NoOpFcmClient implements FcmClient {

	@Override
	public void sendMulticast(List<String> registrationTokens, PushMessage message) {
		// FCM이 비활성화된 구성이므로 의도적으로 아무 것도 하지 않는다.
	}
}
