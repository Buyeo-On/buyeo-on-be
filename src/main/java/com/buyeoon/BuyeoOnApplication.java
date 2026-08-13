package com.buyeoon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BuyeoOnApplication {

	public static void main(String[] args) {
		SpringApplication.run(BuyeoOnApplication.class, args);
	}

}
