package com.ozansoyak.cargo_process_tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CargoProcessTrackingApplication {

	public static void main(String[] args) {
		SpringApplication.run(CargoProcessTrackingApplication.class, args);
	}

}
