package com.middleberth.booking;

import org.springframework.boot.SpringApplication;


//run this to start a test version of the app(the db is different which is the testcontainers db)
public class TestBookingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(BookingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
