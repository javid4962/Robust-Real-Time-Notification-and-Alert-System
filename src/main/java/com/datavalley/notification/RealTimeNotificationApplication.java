package com.datavalley.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RealTimeNotificationApplication {

	public static void main(String[] args) {
		SpringApplication.run(RealTimeNotificationApplication.class, args);
	}

}
