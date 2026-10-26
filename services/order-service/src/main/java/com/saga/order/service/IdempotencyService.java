package com.saga.order.service;

import com.saga.order.service.ApiExceptions.IdempotencyConflictException;
import com.saga.order.service.ApiExceptions.IdempotencyInProgressException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key do POST /orders. A chave e reservada na mesma transacao que cria o pedido:
 * ou ambos existem, ou nenhum. Duas requisicoes concorrentes com a mesma chave se serializam
 * no indice unico (a segunda espera o commit da primeira e entao le a resposta gravada).
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int statusCode, String body) {
    }

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return vazio se a chave e nova (prossiga); resposta gravada se for repeticao valida. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredResponse> begin(String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (idem_key, request_hash) VALUES (:k, :h)
                        ON CONFLICT (idem_key) DO NOTHING
                        """)
                .param("k", key).param("h", requestHash).update();
        if (inserted == 1) {
            return Optional.empty();
        }
        record Row(String hash, Integer status, String body) {
        }
        Row row = jdbc.sql("SELECT request_hash, status_code, response_body FROM idempotency_keys WHERE idem_key = :k")
                .param("k", key)
                .query((rs, n) -> new Row(rs.getString("request_hash").trim(), (Integer) rs.getObject("status_code"),
                        rs.getString("response_body")))
                .single();
        if (!row.hash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        if (row.status() == null || row.body() == null) {
            throw new IdempotencyInProgressException(key);
        }
        return Optional.of(new StoredResponse(row.status(), row.body()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String key, int statusCode, String body) {
        jdbc.sql("UPDATE idempotency_keys SET status_code = :s, response_body = :b WHERE idem_key = :k")
                .param("s", statusCode).param("b", body).param("k", key).update();
    }
}
