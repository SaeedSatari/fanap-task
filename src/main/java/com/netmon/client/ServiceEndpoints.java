package com.netmon.client;

import com.netmon.config.NetmonProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves the base URL of each service. Blank => this very application (monolith deployment).
 * Setting {@code netmon.clients.ping.base-url} / {@code netmon.clients.monitor.base-url} is all it
 * takes to move a service into its own process.
 */
@Component
public class ServiceEndpoints {

    private final NetmonProperties props;
    private final Environment environment;

    public ServiceEndpoints(NetmonProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    public String pingBaseUrl() {
        return resolve(props.getClients().getPing().getBaseUrl());
    }

    public String monitorBaseUrl() {
        return resolve(props.getClients().getMonitor().getBaseUrl());
    }

    private String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            port = environment.getProperty("server.port", "8080");
        }
        return "http://localhost:" + port;
    }
}
