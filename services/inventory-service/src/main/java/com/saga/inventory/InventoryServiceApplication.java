package com.saga.inventory;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.saga.inventory", "com.saga.common"})
@EnableScheduling
public class InventoryServiceApplication {

    public static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(InventoryServiceApplication.class)
                .properties("spring.config.name=inventory-service");
    }

    public static void main(String[] args) {
        application().run(args);
    }
}
