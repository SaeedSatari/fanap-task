package com.network.ping;

public interface PingProbe {

    PingResult ping(String ipAddress) throws PingProbeException;
}
