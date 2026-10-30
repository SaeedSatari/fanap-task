package com.network.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "network")
public class NetworkProperties {

    private final Ping ping = new Ping();
    private final Worker worker = new Worker();
    private final Recovery recovery = new Recovery();
    private final Monitoring monitoring = new Monitoring();
    private final Clients clients = new Clients();
    private final Mock mock = new Mock();

    public Ping getPing() { return ping; }
    public Worker getWorker() { return worker; }
    public Recovery getRecovery() { return recovery; }
    public Monitoring getMonitoring() { return monitoring; }
    public Clients getClients() { return clients; }
    public Mock getMock() { return mock; }

    public static class Ping {
        private Duration timeout = Duration.ofSeconds(3);
        private int maxAttempts = 3;
        private Duration retryBackoff = Duration.ofSeconds(1);
        private Duration maxRetryBackoff = Duration.ofSeconds(30);
        private Duration leaseMargin = Duration.ofSeconds(5);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
        public Duration getMaxRetryBackoff() { return maxRetryBackoff; }
        public void setMaxRetryBackoff(Duration maxRetryBackoff) { this.maxRetryBackoff = maxRetryBackoff; }
        public Duration getLeaseMargin() { return leaseMargin; }
        public void setLeaseMargin(Duration leaseMargin) { this.leaseMargin = leaseMargin; }
    }

    public static class Worker {
        private int threads = 4;
        private int queueCapacity = 100;

        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Recovery {
        private boolean enabled = true;
        private long sweepIntervalMs = 1000;
        private boolean requeueRunningOnStartup = true;
        private int dispatchBatchSize = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getSweepIntervalMs() { return sweepIntervalMs; }
        public void setSweepIntervalMs(long sweepIntervalMs) { this.sweepIntervalMs = sweepIntervalMs; }
        public boolean isRequeueRunningOnStartup() { return requeueRunningOnStartup; }
        public void setRequeueRunningOnStartup(boolean v) { this.requeueRunningOnStartup = v; }
        public int getDispatchBatchSize() { return dispatchBatchSize; }
        public void setDispatchBatchSize(int dispatchBatchSize) { this.dispatchBatchSize = dispatchBatchSize; }
    }

    public static class Monitoring {
        private boolean enabled = true;
        private long intervalMs = 30000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class Clients {
        private int retries = 1;
        private ClientConfig ping = new ClientConfig();
        private ClientConfig monitor = new ClientConfig();

        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
        public ClientConfig getPing() { return ping; }
        public void setPing(ClientConfig ping) { this.ping = ping; }
        public ClientConfig getMonitor() { return monitor; }
        public void setMonitor(ClientConfig monitor) { this.monitor = monitor; }
    }

    public static class ClientConfig {
        private String baseUrl = "";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }

    public static class Mock {
        private double reachableProbability = 0.85;
        private long minLatencyMs = 20;
        private long maxLatencyMs = 250;
        private double transientErrorProbability = 0.05;

        public double getReachableProbability() { return reachableProbability; }
        public void setReachableProbability(double v) { this.reachableProbability = v; }
        public long getMinLatencyMs() { return minLatencyMs; }
        public void setMinLatencyMs(long minLatencyMs) { this.minLatencyMs = minLatencyMs; }
        public long getMaxLatencyMs() { return maxLatencyMs; }
        public void setMaxLatencyMs(long maxLatencyMs) { this.maxLatencyMs = maxLatencyMs; }
        public double getTransientErrorProbability() { return transientErrorProbability; }
        public void setTransientErrorProbability(double v) { this.transientErrorProbability = v; }
    }
}
