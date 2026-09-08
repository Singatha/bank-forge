package com.bankcore.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI bankCoreOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BankCore API")
                .version("v1")
                .description("API for a simulated educational banking backend. No real banking services are provided."));
    }
}

