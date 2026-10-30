package com.netmon.ping;

import com.netmon.config.NetmonProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Simulated ping. Deterministic behaviour for documentation ranges (RFC 5737) so every
 * failure path can be demonstrated on demand:
 * <ul>
 *   <li>192.0.2.x    - host unreachable (conclusive DOWN)</li>
 *   <li>198.51.100.x - probe hangs (triggers the timeout path)</li>
 *   <li>203.0.113.x  - probe always errors (triggers retry, then FAILED)</li>
 *   <li>anything else - random latency, small chance of DOWN / transient error</li>
 * </ul>
 */
@Component
public class MockPingProbe implements PingProbe {

    private final NetmonProperties props;

    public MockPingProbe(NetmonProperties props) {
        this.props = props;
    }

    @Override
    public PingResult ping(String ipAddress) throws PingProbeException {
        NetmonProperties.Mock mock = props.getMock();

        if (ipAddress.startsWith("198.51.100.")) {
            sleep(10 * 60 * 1000L); // effectively "never answers"; interrupted by the worker's timeout
            return new PingResult(false, null);
        }
        if (ipAddress.startsWith("203.0.113.")) {
            sleep(randomLatency(mock));
            throw new PingProbeException("Simulated network error while probing " + ipAddress);
        }
        if (ipAddress.startsWith("192.0.2.")) {
            sleep(randomLatency(mock));
            return new PingResult(false, null);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long latency = randomLatency(mock);
        sleep(latency);
        if (random.nextDouble() < mock.getTransientErrorProbability()) {
            throw new PingProbeException("Simulated transient network error while probing " + ipAddress);
        }
        if (random.nextDouble() < mock.getReachableProbability()) {
            return new PingResult(true, latency);
        }
        return new PingResult(false, null);
    }

    private static long randomLatency(NetmonProperties.Mock mock) {
        long min = Math.max(0, mock.getMinLatencyMs());
        long max = Math.max(min, mock.getMaxLatencyMs());
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private static void sleep(long millis) throws PingProbeException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PingProbeException("Ping interrupted", e);
        }
    }
}
