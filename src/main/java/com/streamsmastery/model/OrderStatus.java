package com.streamsmastery.model;

/**
 * Represents the lifecycle states of an Order.
 *
 * Using an enum here (instead of a String) is intentional:
 * it makes our stream predicates compile-time safe and prevents
 * bugs from typos like "SHIPED" vs "SHIPPED".
 *
 * In stream pipelines, enums work naturally with == comparisons
 * and can be grouped with Collectors.groupingBy(Order::status).
 */
public enum OrderStatus {
    PENDING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
