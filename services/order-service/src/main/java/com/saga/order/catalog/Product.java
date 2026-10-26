package com.saga.order.catalog;

import java.math.BigDecimal;

public record Product(String id, String name, BigDecimal price, boolean active, long version) {
}
