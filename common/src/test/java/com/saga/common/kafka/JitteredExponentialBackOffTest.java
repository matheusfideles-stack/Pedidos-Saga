package com.saga.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class JitteredExponentialBackOffTest {

    @Test
    void growsExponentiallyWithinJitterBoundsAndStopsAfterMaxRetries() {
        BackOffExecution ex = new JitteredExponentialBackOff(4, 100, 2.0, 500, 50).start();
        long[] base = {100, 200, 400, 500}; // o ultimo e limitado por maxInterval
        for (long b : base) {
            assertThat(ex.nextBackOff()).isBetween(b, b + 50);
        }
        assertThat(ex.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void jitterActuallyVariesBetweenExecutions() {
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> new JitteredExponentialBackOff(1, 100, 2.0, 500, 200).start().nextBackOff())
                .distinct().count();
        assertThat(distinct).isGreaterThan(5);
    }
}
