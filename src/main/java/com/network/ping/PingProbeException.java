package com.network.ping;

/** The probe could not determine reachability (as opposed to "device is down"). */
public class PingProbeException extends Exception {

    public PingProbeException(String message) {
        super(message);
    }

    public PingProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
