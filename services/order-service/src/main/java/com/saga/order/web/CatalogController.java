package com.saga.order.web;

import com.saga.order.catalog.CatalogService;
import com.saga.order.catalog.Product;
import com.saga.order.catalog.ProductCache;
import java.util.Map;
import com.saga.order.web.OrderDtos.ProductRequest;
import com.saga.order.web.OrderDtos.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/products")
public class CatalogController {

    private final CatalogService catalog;
    private final ProductCache cache;

    public CatalogController(CatalogService catalog, ProductCache cache) {
        this.cache = cache;
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> get(@PathVariable String id) {
        return catalog.find(id).map(CatalogController::toResponse)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Hit ratio e contadores de cada camada (L1 Caffeine, L2 Redis). */
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        ProductCache.Stats s = cache.stats();
        return Map.of("l1Hits", s.l1Hits(), "l1Misses", s.l1Misses(), "l1HitRatio", s.l1HitRatio(),
                "l2Hits", s.l2Hits(), "l2Misses", s.l2Misses(), "l2HitRatio", s.l2HitRatio(),
                "dbLoads", s.dbLoads(), "redisErrors", s.redisErrors());
    }

    @PutMapping("/{id}")
    public ProductResponse upsert(@PathVariable String id, @Valid @RequestBody ProductRequest body) {
        return toResponse(catalog.upsert(id, body.name(), body.price(), body.active() == null || body.active()));
    }

    private static ProductResponse toResponse(Product p) {
        return new ProductResponse(p.id(), p.name(), p.price(), p.active(), p.version());
    }
}
