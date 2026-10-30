package com.network.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@ConfigurationProperties(prefix = "network")
public class NetworkProperties {

    private final Ping ping = new Ping();
    private final Worker worker = new Worker();
    private final Recovery recovery = new Recovery();
    private final Monitoring monitoring = new Monitoring();
    private final Clients clients = new Clients();
    private final Mock mock = new Mock();

    @Getter
    @Setter
    public static class Ping {
        private Duration timeout = Duration.ofSeconds(3);
        private int maxAttempts = 3;
        private Duration retryBackoff = Duration.ofSeconds(1);
        private Duration maxRetryBackoff = Duration.ofSeconds(30);
        private Duration leaseMargin = Duration.ofSeconds(5);
    }

    @Getter
    @Setter
    public static class Worker {
        private int threads = 4;
        private int queueCapacity = 100;
    }

    @Getter
    @Setter
    public static class Recovery {
        private boolean enabled = true;
        private long sweepIntervalMs = 1000;
        private boolean requeueRunningOnStartup = true;
        private int dispatchBatchSize = 50;
    }

    @Getter
    @Setter
    public static class Monitoring {
        private boolean enabled = true;
        private long intervalMs = 30000;
    }

    @Getter
    @Setter
    public static class Clients {
        private int retries = 1;
        private ClientConfig ping = new ClientConfig();
        private ClientConfig monitor = new ClientConfig();
    }

    @Getter
    @Setter
    public static class ClientConfig {
        private String baseUrl = "";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);
    }

    @Getter
    @Setter
    public static class Mock {
        private double reachableProbability = 0.85;
        private long minLatencyMs = 20;
        private long maxLatencyMs = 250;
        private double transientErrorProbability = 0.05;
    }
}