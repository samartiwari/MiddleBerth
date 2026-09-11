package com.middleberth.booking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		//return postgres container for the tests with postgres:16-alpine image
		//called by TestBookingServiceApplication to run tests with a different db
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
				.withStartupTimeout(Duration.ofMinutes(3));
	}

	/**
	 * A fake payment-service for Pay Now to call. A bean here, rather than set up in
	 * one test class, so every test class shares it — a per-class setting would
	 * change the Spring context and start another set of containers.
	 */
	@Bean
	StubPaymentServer stubPaymentServer() {
		return StubPaymentServer.start();
	}

	@Bean
	DynamicPropertyRegistrar paymentUrl(StubPaymentServer stub) {
		return registry -> registry.add("middleberth.payment-url", stub::url);
	}
}
