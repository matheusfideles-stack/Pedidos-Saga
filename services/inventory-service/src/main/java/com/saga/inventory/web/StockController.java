package com.saga.inventory.web;

import java.util.List;
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

    /** Quantidade disponivel e reservada de um produto. */
    public record StockLevel(String productId, int available, int reserved) {
    }

    private final JdbcClient jdbc;

    public StockController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Estoque de todos os produtos, em ordem de codigo. */
    @GetMapping
    public List<StockLevel> list() {
        return jdbc.sql("SELECT product_id, available, reserved FROM stock ORDER BY product_id")
                .query(StockController::map)
                .list();
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockLevel> get(@PathVariable String productId) {
        return jdbc.sql("SELECT product_id, available, reserved FROM stock WHERE product_id = :p")
                .param("p", productId)
                .query(StockController::map)
                .optional().map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    private static StockLevel map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StockLevel(rs.getString("product_id"), rs.getInt("available"), rs.getInt("reserved"));
    }
}
