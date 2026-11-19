package com.ap.helpdesk.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot REST API and configures its database connection. */
@SpringBootApplication
public class HelpdeskServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelpdeskServerApplication.class, args);
	}

}
