package com.saga.order.catalog;

import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.common.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CatalogRepository repository;
    private final ProductCache cache;
    private final OutboxWriter outbox;

    public CatalogService(CatalogRepository repository, ProductCache cache, OutboxWriter outbox) {
        this.repository = repository;
        this.cache = cache;
        this.outbox = outbox;
    }

    /** Leitura usada na criacao do pedido: cache-aside (L1 Caffeine -> L2 Redis -> PostgreSQL). */
    public Optional<Product> find(String productId) {
        return cache.get(productId);
    }

    /**
     * Altera o catalogo e publica ProductChanged (outbox + CDC). As instancias invalidam o cache ao receber
     * o evento, DEPOIS do commit: invalidar antes permitiria recachear o valor antigo.
     */
    @Transactional
    public Product upsert(String id, String name, BigDecimal price, boolean active) {
        Product saved = repository.save(id, name, price, active);
        outbox.write(Topics.AGG_CATALOG, saved.id(), Events.PRODUCT_CHANGED, Events.V1,
                new Events.ProductChanged(saved.id(), saved.name(), saved.price(), saved.active(), saved.version()));
        return saved;
    }
}
