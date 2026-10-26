package com.saga.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.common.outbox.OutboxWriter;
import com.saga.order.catalog.CatalogService;
import com.saga.order.catalog.Product;
import com.saga.order.domain.Order;
import com.saga.order.domain.SagaTrigger;
import com.saga.order.persistence.OrderRepository;
import com.saga.order.service.ApiExceptions.OrderNotFoundException;
import com.saga.order.service.ApiExceptions.UnknownProductException;
import com.saga.order.web.OrderDtos.CreateOrderRequest;
import com.saga.order.web.OrderDtos.ItemRequest;
import com.saga.order.web.OrderDtos.OrderResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    public static final String CURRENCY = "BRL";

    /** @param replayed true quando a resposta veio do registro de Idempotency-Key. */
    public record CreateResult(OrderResponse order, boolean replayed) {
    }

    private final OrderRepository orders;
    private final CatalogService catalog;
    private final OutboxWriter outbox;
    private final IdempotencyService idempotency;
    private final OrderSagaService saga;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public OrderService(OrderRepository orders, CatalogService catalog, OutboxWriter outbox,
                        IdempotencyService idempotency, OrderSagaService saga, ObjectMapper json,
                        MeterRegistry meters) {
        this.orders = orders;
        this.catalog = catalog;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.saga = saga;
        this.json = json;
        this.meters = meters;
    }

    /**
     * Criacao sincrona: valida, grava pedido PENDING + outbox(OrderCreated) numa unica transacao.
     * O restante da saga acontece de forma assincrona.
     */
    @Transactional
    public CreateResult create(CreateOrderRequest request, String idempotencyKey) {
        Map<String, Integer> quantities = merge(request.items());
        String hash = requestHash(request.customerId(), quantities);

        if (idempotencyKey != null) {
            Optional<IdempotencyService.StoredResponse> stored = idempotency.begin(idempotencyKey, hash);
            if (stored.isPresent()) {
                meters.counter("orders.idempotent.replays").increment();
                return new CreateResult(read(stored.get().body()), true);
            }
        }

        List<Events.Item> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> e : quantities.entrySet()) {
            Product p = catalog.find(e.getKey()).filter(Product::active)
                    .orElseThrow(() -> new UnknownProductException(e.getKey()));
            items.add(new Events.Item(p.id(), e.getValue(), p.price()));
            total = total.add(p.price().multiply(BigDecimal.valueOf(e.getValue())));
        }

        UUID id = UUID.randomUUID();
        orders.insert(id, request.customerId(), total, CURRENCY, items);
        outbox.write(Topics.AGG_ORDER, id.toString(), Events.ORDER_CREATED, Events.V1,
                new Events.OrderCreated(id, request.customerId(), items, total, CURRENCY));
        meters.counter("orders.created").increment();

        OrderResponse response = OrderResponse.from(orders.find(id).orElseThrow());
        if (idempotencyKey != null) {
            idempotency.complete(idempotencyKey, 201, write(response));
        }
        return new CreateResult(response, false);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return orders.find(id).map(OrderResponse::from).orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** Cancelamento pelo cliente: dispara OrderCancelled, que aciona as compensacoes. */
    @Transactional
    public OrderResponse cancel(UUID id) {
        orders.find(id).orElseThrow(() -> new OrderNotFoundException(id));
        saga.apply(id, SagaTrigger.CUSTOMER_CANCEL, "customer_request");
        return OrderResponse.from(orders.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Order load(UUID id) {
        return orders.find(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** Itens repetidos viram um so (soma); ordenado por produto => hash canonico e ordem de lock estavel. */
    private static Map<String, Integer> merge(List<ItemRequest> items) {
        Map<String, Integer> merged = new TreeMap<>();
        for (ItemRequest i : items) {
            merged.merge(i.productId(), i.quantity(), Integer::sum);
        }
        return merged;
    }

    private static String requestHash(String customerId, Map<String, Integer> quantities) {
        StringBuilder canonical = new StringBuilder(customerId);
        quantities.forEach((p, q) -> canonical.append('|').append(p).append(':').append(q));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(OrderResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderResponse read(String body) {
        try {
            return json.readValue(body, OrderResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
