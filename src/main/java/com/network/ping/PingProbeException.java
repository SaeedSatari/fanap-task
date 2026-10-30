package com.network.ping;

public class PingProbeException extends Exception {

    public PingProbeException(String message) {
        super(message);
    }

    public PingProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
