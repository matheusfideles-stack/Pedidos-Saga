package com.saga.common.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.kafka")
public class SagaKafkaProperties {

    private Retry retry = new Retry();

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public static class Retry {
        private int maxRetries = 5;
        private Duration initialInterval = Duration.ofMillis(500);
        private double multiplier = 2.0;
        private Duration maxInterval = Duration.ofSeconds(10);
        private Duration jitter = Duration.ofMillis(250);

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public Duration getJitter() {
            return jitter;
        }

        public void setJitter(Duration jitter) {
            this.jitter = jitter;
        }
    }
}
