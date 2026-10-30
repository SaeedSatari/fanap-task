package com.network.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI netmonOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Network API")
                .version("1.0.0")
                .description("Network device management and asynchronous ping monitoring"));
    }
}