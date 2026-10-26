package com.saga.inventory.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consulta de estoque (somente leitura; mutacoes so acontecem via eventos da saga). */
@RestController
@RequestMapping("/inventory")
public class StockController {

    private final JdbcClient jdbc;

    public StockController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String productId) {
        return jdbc.sql("SELECT product_id, available, reserved FROM stock WHERE product_id = :p")
                .param("p", productId)
                .query((rs, n) -> Map.<String, Object>of("productId", rs.getString("product_id"),
                        "available", rs.getInt("available"), "reserved", rs.getInt("reserved")))
                .optional().map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
