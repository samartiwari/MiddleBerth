package com.middleberth.notification.service;

public enum Sent {
    SENT,
    /** This exact message has gone out before. Messages arrive at least once. */
    ALREADY_SENT,
    /** Nobody to write to. */
    NO_ADDRESS
}
