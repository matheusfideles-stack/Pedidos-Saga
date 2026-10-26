package com.saga.common.kafka;

import com.saga.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Todos os servicos declaram TODOS os topicos da saga (e as DLTs). A criacao e idempotente.
 * Motivo: um consumidor que sobe antes do produtor so descobriria um topico inexistente no proximo refresh de
 * metadata (ate 5 minutos), deixando a saga parada. Com isso a ordem de partida dos servicos nao importa
 * (docker compose, Kubernetes e testes sobem tudo ao mesmo tempo).
 */
@Configuration
public class SagaTopicsConfig {

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic orderEventsTopic() {
        return topic(Topics.ORDER_EVENTS);
    }

    @Bean
    NewTopic inventoryEventsTopic() {
        return topic(Topics.INVENTORY_EVENTS);
    }

    @Bean
    NewTopic paymentEventsTopic() {
        return topic(Topics.PAYMENT_EVENTS);
    }

    @Bean
    NewTopic catalogEventsTopic() {
        return topic(Topics.CATALOG_EVENTS);
    }

    @Bean
    NewTopic orderEventsDltTopic() {
        return topic(Topics.dlt(Topics.ORDER_EVENTS));
    }

    @Bean
    NewTopic inventoryEventsDltTopic() {
        return topic(Topics.dlt(Topics.INVENTORY_EVENTS));
    }

    @Bean
    NewTopic paymentEventsDltTopic() {
        return topic(Topics.dlt(Topics.PAYMENT_EVENTS));
    }
}
