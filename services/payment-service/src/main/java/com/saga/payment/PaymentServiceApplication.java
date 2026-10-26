package com.saga.payment;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.saga.payment", "com.saga.common"})
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentServiceApplication {

    public static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(PaymentServiceApplication.class)
                .properties("spring.config.name=payment-service");
    }

    public static void main(String[] args) {
        application().run(args);
    }
}
