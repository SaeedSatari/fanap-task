package com.netmon.ping;

/** Abstraction over the real network ping so it can be mocked / replaced. */
public interface PingProbe {

    PingResult ping(String ipAddress) throws PingProbeException;
}
