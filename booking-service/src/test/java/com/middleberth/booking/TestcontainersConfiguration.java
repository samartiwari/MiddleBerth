package com.middleberth.booking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		//return postgres container for the tests with postgres:16-alpine image
		//called by TestBookingServiceApplication to run tests with a different db
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")); 
	}

}
