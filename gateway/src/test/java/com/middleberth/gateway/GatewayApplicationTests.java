package com.middleberth.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GatewayApplicationTests {

	static final StubBackend BACKEND = StubBackend.start();

	@DynamicPropertySource
	static void routes(DynamicPropertyRegistry r) {
		r.add("middleberth.booking-url", BACKEND::url);
		r.add("middleberth.search-url", BACKEND::url);
	}

	@Test
	void contextLoads() {
	}
}
