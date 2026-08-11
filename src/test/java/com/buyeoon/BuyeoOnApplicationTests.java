package com.buyeoon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BuyeoOnApplicationTests {

	/** Spring Bean과 애플리케이션 설정이 충돌 없이 구성되어 서버를 기동할 수 있음을 보장한다. */
	@Test
	@DisplayName("Spring 애플리케이션 컨텍스트가 정상적으로 로드된다")
	void contextLoads() {
	}

}
