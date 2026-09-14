package com.middleberth.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NotificationServiceApplicationTests {

	@Autowired
	HealthEndpoint health;

	@Test
	void contextLoads() {
	}

	/**
	 * Whether mail is sent or only logged must not decide whether this service is
	 * alive. Spring Boot's mail health check logs in to the mail server on every
	 * probe, and with mail only being logged there are no credentials to log in
	 * with, so on the live server the service reported DOWN while it was delivering
	 * every note, and Docker marked it unhealthy. A mail server that cannot be
	 * reached shows up as retries and dead letters, not as this service being dead.
	 */
	@Test
	void the_service_is_healthy_without_a_mail_server_to_log_in_to() {
		assertThat(health.health().getStatus()).isEqualTo(Status.UP);
	}
}
