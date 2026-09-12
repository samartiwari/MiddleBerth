package com.middleberth.notification.send;

/** A finished message. Who writes it and who sends it are kept apart on purpose. */
public record Mail(String to, String subject, String body) {
}
