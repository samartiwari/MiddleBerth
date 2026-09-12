package com.middleberth.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "middleberth.seat-counts")
public record SnapshotSettings(Duration snapshotInterval) {
}
