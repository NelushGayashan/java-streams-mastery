package com.streamsmastery.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Represents an e-commerce Order.
 *
 * This is the core domain object used throughout the article to demonstrate
 * real-world stream operations. By using a realistic model instead of simple
 * integers, we can show how streams handle complex business logic elegantly.
 *
 * Fields:
 *  - id          : Unique order identifier
 *  - customerId  : Who placed the order
 *  - region      : Geographic region (NORTH, SOUTH, EAST, WEST)
 *  - status      : Current order status (PENDING, SHIPPED, DELIVERED, CANCELLED)
 *  - items       : Line items in the order
 *  - orderDate   : When the order was placed
 */
public record Order(
        String id,
        String customerId,
        String region,
        OrderStatus status,
        List<OrderItem> items,
        LocalDate orderDate
) {

    /**
     * Calculates the total revenue for this order by summing
     * (price * quantity) across all line items.
     *
     * This method is used extensively in stream pipelines as a
     * method reference: Order::totalRevenue
     */
    public double totalRevenue() {
        return items.stream()
                .mapToDouble(item -> item.price() * item.quantity())
                .sum();
    }

    /**
     * Returns the total number of items (units) across all line items.
     * Useful for identifying bulk orders.
     */
    public int totalUnits() {
        return items.stream()
                .mapToInt(OrderItem::quantity)
                .sum();
    }

    /**
     * Determines whether this order qualifies as "high value".
     * Business rule: orders above $500 are considered high value.
     * Used as a Predicate in filter() operations.
     */
    public boolean isHighValue() {
        return totalRevenue() > 500.0;
    }
}
