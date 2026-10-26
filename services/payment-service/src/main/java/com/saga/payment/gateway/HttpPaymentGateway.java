package com.saga.payment.gateway;

import com.saga.payment.PaymentProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Cliente HTTP do gateway, com timeouts explicitos de conexao e leitura. Sem resiliencia: ver a versao Resilient. */
@Component
public class HttpPaymentGateway {

    private final RestClient client;
    private final PaymentProperties props;
    private final Environment env;

    public HttpPaymentGateway(PaymentProperties props, Environment env, RestClient.Builder builder) {
        this.props = props;
        this.env = env;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getGateway().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getGateway().getReadTimeout().toMillis());
        this.client = builder.requestFactory(factory).build(); // builder do Boot: com observabilidade (traces/metricas HTTP)
    }

    private String baseUrl() {
        String configured = props.getGateway().getBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "http://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "8083"));
    }

    public PaymentGateway.GatewayResult charge(String key, String customerId, BigDecimal amount) {
        return call(() -> client.post().uri(baseUrl() + "/sim-gateway/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("idempotencyKey", key, "customerId", customerId, "amount", amount))
                .retrieve().body(PaymentGateway.GatewayResult.class));
    }

    public Optional<PaymentGateway.GatewayResult> lookup(String key) {
        try {
            return Optional.ofNullable(call(() -> client.get().uri(baseUrl() + "/sim-gateway/charges/{k}", key)
                    .retrieve().body(PaymentGateway.GatewayResult.class)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public PaymentGateway.GatewayResult voidCharge(String key) {
        return call(() -> client.post().uri(baseUrl() + "/sim-gateway/charges/{k}/void", key)
                .retrieve().body(PaymentGateway.GatewayResult.class));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new TransientGatewayException("gateway indisponivel: " + e.getMessage(), e);
        }
        // 4xx (exceto 404 tratado em lookup) propaga como erro permanente: sem retry
    }
}
