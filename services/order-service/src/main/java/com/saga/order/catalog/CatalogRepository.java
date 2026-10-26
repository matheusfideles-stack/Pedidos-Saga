package com.saga.order.catalog;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Product> findById(String id) {
        return jdbc.sql("SELECT id, name, price, active, version FROM products WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Product(rs.getString("id"), rs.getString("name"), rs.getBigDecimal("price"),
                        rs.getBoolean("active"), rs.getLong("version")))
                .optional();
    }

    /** Upsert; incrementa a versao a cada alteracao. */
    public Product save(String id, String name, BigDecimal price, boolean active) {
        return jdbc.sql("""
                        INSERT INTO products (id, name, price, active) VALUES (:id, :name, :price, :active)
                        ON CONFLICT (id) DO UPDATE
                          SET name = EXCLUDED.name, price = EXCLUDED.price, active = EXCLUDED.active,
                              version = products.version + 1, updated_at = now()
                        RETURNING id, name, price, active, version
                        """)
                .param("id", id).param("name", name).param("price", price).param("active", active)
                .query((rs, n) -> new Product(rs.getString("id"), rs.getString("name"), rs.getBigDecimal("price"),
                        rs.getBoolean("active"), rs.getLong("version")))
                .single();
    }
}
