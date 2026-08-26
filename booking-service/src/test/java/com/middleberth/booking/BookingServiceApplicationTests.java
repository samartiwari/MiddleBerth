package com.middleberth.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

//runs when we do ./mvnw test which calls TestContainersConfiguration to start a new postgres container for tests
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BookingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
