package com.saga.order;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.saga.order", "com.saga.common"})
@EnableScheduling
@EnableConfigurationProperties(com.saga.order.catalog.CacheProperties.class)
public class OrderServiceApplication {

    /**
     * Cada servico tem seu proprio arquivo {@code <servico>.yml}, assim os tres podem rodar
     * no mesmo classpath (testes de integracao em-processo) sem colidir em application.yml.
     */
    public static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(OrderServiceApplication.class)
                .properties("spring.config.name=order-service");
    }

    public static void main(String[] args) {
        application().run(args);
    }
}
