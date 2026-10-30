package com.network.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(NetworkProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Bounded pool that runs ping operations. When full, work simply stays PENDING in the DB and is picked up later. */
    @Bean(name = "pingWorkerExecutor", destroyMethod = "shutdown")
    public ExecutorService pingWorkerExecutor(NetworkProperties props) {
        int threads = Math.max(1, props.getWorker().getThreads());
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, props.getWorker().getQueueCapacity())),
                named("ping-worker-"));
    }

    /** Pool that actually runs the probe so that the worker thread can enforce a timeout on it. */
    @Bean(name = "pingProbeExecutor", destroyMethod = "shutdownNow")
    public ExecutorService pingProbeExecutor(NetworkProperties props) {
        int threads = Math.max(1, props.getWorker().getThreads()) * 2;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("ping-probe-"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = "pingRestTemplate")
    public RestTemplate pingRestTemplate(NetworkProperties props, ObjectMapper objectMapper) {
        return buildRestTemplate(props.getClients().getPing(), objectMapper);
    }

    @Bean(name = "monitorRestTemplate")
    public RestTemplate monitorRestTemplate(NetworkProperties props, ObjectMapper objectMapper) {
        return buildRestTemplate(props.getClients().getMonitor(), objectMapper);
    }

    private static RestTemplate buildRestTemplate(NetworkProperties.ClientConfig config, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) config.getReadTimeout().toMillis());
        RestTemplate restTemplate = new RestTemplate(factory);

        List<HttpMessageConverter<?>> converters = new ArrayList<>(restTemplate.getMessageConverters());
        converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
        restTemplate.setMessageConverters(converters);
        return restTemplate;
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
