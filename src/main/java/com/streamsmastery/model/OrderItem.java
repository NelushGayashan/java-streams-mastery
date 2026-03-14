package com.streamsmastery.model;

/**
 * Represents a single line item within an Order.
 *
 * Using Java Records here keeps the model concise and immutable —
 * which is exactly what stream pipelines prefer. Mutable state
 * inside stream operations is a common source of bugs, especially
 * in parallel pipelines.
 *
 * Fields:
 *  - productId : The product being purchased
 *  - category  : Product category (ELECTRONICS, CLOTHING, BOOKS, HOME, SPORTS)
 *  - price     : Unit price of the product
 *  - quantity  : Number of units ordered
 */
public record OrderItem(
        String productId,
        String category,
        double price,
        int quantity
) {
    /**
     * Calculates the subtotal for this line item.
     * Used in downstream collectors and mapToDouble() operations.
     */
    public double subtotal() {
        return price * quantity;
    }
}
