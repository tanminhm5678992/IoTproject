package com.example.smarthome.exception;

/** target/value của lệnh không hợp lệ - HTTP 400. */
public class InvalidCommandException extends IllegalArgumentException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
