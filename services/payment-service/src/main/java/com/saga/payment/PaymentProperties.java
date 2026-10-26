package com.saga.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    private final Gateway gateway = new Gateway();
    private final Reconciliation reconciliation = new Reconciliation();

    public Gateway getGateway() {
        return gateway;
    }

    public Reconciliation getReconciliation() {
        return reconciliation;
    }

    public static class Gateway {
        private String baseUrl = "";
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration readTimeout = Duration.ofSeconds(2);
        private final Sim sim = new Sim();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Sim getSim() {
            return sim;
        }
    }

    /** Comportamento do gateway simulado. */
    public static class Sim {
        private long latencyMs = 30;
        private long latencyJitterMs = 20;
        private double failureRate = 0.0;
        private long postCommitDelayMs = 0;
        private long declineAbove = 10_000;

        public long getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        public long getLatencyJitterMs() {
            return latencyJitterMs;
        }

        public void setLatencyJitterMs(long latencyJitterMs) {
            this.latencyJitterMs = latencyJitterMs;
        }

        public double getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(double failureRate) {
            this.failureRate = failureRate;
        }

        public long getPostCommitDelayMs() {
            return postCommitDelayMs;
        }

        public void setPostCommitDelayMs(long postCommitDelayMs) {
            this.postCommitDelayMs = postCommitDelayMs;
        }

        public long getDeclineAbove() {
            return declineAbove;
        }

        public void setDeclineAbove(long declineAbove) {
            this.declineAbove = declineAbove;
        }
    }

    public static class Reconciliation {
        private boolean enabled = true;
        private Duration interval = Duration.ofSeconds(30);
        private Duration staleAfter = Duration.ofSeconds(60);
        private Duration giveUpAfter = Duration.ofMinutes(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public Duration getGiveUpAfter() {
            return giveUpAfter;
        }

        public void setGiveUpAfter(Duration giveUpAfter) {
            this.giveUpAfter = giveUpAfter;
        }
    }
}
