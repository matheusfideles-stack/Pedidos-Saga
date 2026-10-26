package com.saga.common.kafka;

import com.saga.common.events.PoisonMessageException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Retry com backoff exponencial + jitter e Dead Letter Topic ({@code <topico>.DLT}).
 * O Spring Boot injeta este {@link CommonErrorHandler} em todos os listeners.
 */
@Configuration
@EnableConfigurationProperties(SagaKafkaProperties.class)
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template,
                                                SagaKafkaProperties props,
                                                MeterRegistry meters) {
        // particao -1: o produtor escolhe pela chave (orderId), mantendo a ordem por pedido na DLT
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));

        ConsumerRecordRecoverer recoverer = (rec, ex) -> {
            log.error("enviando para DLT topic={} partition={} offset={} key={} causa={}",
                    rec.topic(), rec.partition(), rec.offset(), rec.key(), ex.toString());
            meters.counter("saga.dlt.published", "topic", rec.topic() + ".DLT").increment();
            dlt.accept(rec, ex);
        };

        SagaKafkaProperties.Retry r = props.getRetry();
        JitteredExponentialBackOff backOff = new JitteredExponentialBackOff(r.getMaxRetries(),
                r.getInitialInterval().toMillis(), r.getMultiplier(), r.getMaxInterval().toMillis(),
                r.getJitter().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonMessageException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
