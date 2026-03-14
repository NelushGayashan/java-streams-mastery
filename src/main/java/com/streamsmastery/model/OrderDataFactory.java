package com.streamsmastery.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Factory class that generates a realistic dataset of Orders.
 *
 * In a production system, this data would come from a database or REST API.
 * For the purposes of this article, we generate a deterministic, varied
 * dataset that exercises every stream operation we demonstrate.
 *
 * Dataset characteristics:
 *  - 20 orders across 4 regions (NORTH, SOUTH, EAST, WEST)
 *  - Mix of all OrderStatus values
 *  - Varied revenue: from $29.99 to $1,249.99
 *  - Multiple product categories per order
 *  - Date range: last 90 days
 *
 * This variety ensures our groupingBy(), partitioningBy(), and
 * teeing() examples produce non-trivial, meaningful results.
 */
public class OrderDataFactory {

    public static List<Order> createSampleOrders() {
        return List.of(
            new Order("ORD-001", "CUST-A", "NORTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("LAPTOP-PRO", "ELECTRONICS", 999.99, 1),
                    new OrderItem("MOUSE-X", "ELECTRONICS", 49.99, 2)
                ), LocalDate.now().minusDays(5)),

            new Order("ORD-002", "CUST-B", "SOUTH", OrderStatus.SHIPPED,
                List.of(
                    new OrderItem("T-SHIRT-M", "CLOTHING", 29.99, 3),
                    new OrderItem("JEANS-L", "CLOTHING", 79.99, 1)
                ), LocalDate.now().minusDays(3)),

            new Order("ORD-003", "CUST-C", "EAST", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("JAVA-BOOK", "BOOKS", 59.99, 2),
                    new OrderItem("DESIGN-BOOK", "BOOKS", 49.99, 1)
                ), LocalDate.now().minusDays(10)),

            new Order("ORD-004", "CUST-A", "NORTH", OrderStatus.PENDING,
                List.of(
                    new OrderItem("PHONE-X", "ELECTRONICS", 799.99, 1),
                    new OrderItem("CASE-X", "ELECTRONICS", 19.99, 2)
                ), LocalDate.now().minusDays(1)),

            new Order("ORD-005", "CUST-D", "WEST", OrderStatus.CANCELLED,
                List.of(
                    new OrderItem("SOFA-L", "HOME", 649.99, 1)
                ), LocalDate.now().minusDays(15)),

            new Order("ORD-006", "CUST-E", "SOUTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("TENNIS-RACK", "SPORTS", 129.99, 2),
                    new OrderItem("BALLS-PKG", "SPORTS", 24.99, 4)
                ), LocalDate.now().minusDays(7)),

            new Order("ORD-007", "CUST-F", "EAST", OrderStatus.SHIPPED,
                List.of(
                    new OrderItem("MONITOR-4K", "ELECTRONICS", 449.99, 1),
                    new OrderItem("HDMI-CBL", "ELECTRONICS", 14.99, 2)
                ), LocalDate.now().minusDays(2)),

            new Order("ORD-008", "CUST-G", "NORTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("JACKET-XL", "CLOTHING", 189.99, 1),
                    new OrderItem("HAT-M", "CLOTHING", 39.99, 2)
                ), LocalDate.now().minusDays(20)),

            new Order("ORD-009", "CUST-H", "WEST", OrderStatus.PENDING,
                List.of(
                    new OrderItem("DESK-STANDING", "HOME", 349.99, 1),
                    new OrderItem("MAT-ANTI-FATIGUE", "HOME", 89.99, 1)
                ), LocalDate.now().minusDays(4)),

            new Order("ORD-010", "CUST-I", "SOUTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("TABLET-S", "ELECTRONICS", 499.99, 1),
                    new OrderItem("STYLUS", "ELECTRONICS", 89.99, 1),
                    new OrderItem("COVER", "ELECTRONICS", 39.99, 1)
                ), LocalDate.now().minusDays(8)),

            new Order("ORD-011", "CUST-J", "EAST", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("YOGA-MAT", "SPORTS", 59.99, 1),
                    new OrderItem("WEIGHTS-SET", "SPORTS", 129.99, 1)
                ), LocalDate.now().minusDays(12)),

            new Order("ORD-012", "CUST-K", "NORTH", OrderStatus.SHIPPED,
                List.of(
                    new OrderItem("COOKBOOK", "BOOKS", 34.99, 2),
                    new OrderItem("NOVEL-BEST", "BOOKS", 19.99, 3)
                ), LocalDate.now().minusDays(6)),

            new Order("ORD-013", "CUST-L", "WEST", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("GAMING-PC", "ELECTRONICS", 1249.99, 1),
                    new OrderItem("KEYBOARD-MECH", "ELECTRONICS", 149.99, 1),
                    new OrderItem("HEADSET-PRO", "ELECTRONICS", 199.99, 1)
                ), LocalDate.now().minusDays(25)),

            new Order("ORD-014", "CUST-M", "SOUTH", OrderStatus.CANCELLED,
                List.of(
                    new OrderItem("BIKE-MOUNTAIN", "SPORTS", 599.99, 1)
                ), LocalDate.now().minusDays(18)),

            new Order("ORD-015", "CUST-N", "EAST", OrderStatus.PENDING,
                List.of(
                    new OrderItem("LAMP-SMART", "HOME", 79.99, 2),
                    new OrderItem("BULB-LED-4PK", "HOME", 24.99, 3)
                ), LocalDate.now().minusDays(2)),

            new Order("ORD-016", "CUST-O", "NORTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("SNEAKERS-RUN", "CLOTHING", 119.99, 1),
                    new OrderItem("SOCKS-6PK", "CLOTHING", 19.99, 2)
                ), LocalDate.now().minusDays(30)),

            new Order("ORD-017", "CUST-P", "WEST", OrderStatus.SHIPPED,
                List.of(
                    new OrderItem("COFFEE-MAKER", "HOME", 249.99, 1),
                    new OrderItem("GRINDER", "HOME", 89.99, 1),
                    new OrderItem("BEANS-1KG", "HOME", 29.99, 2)
                ), LocalDate.now().minusDays(9)),

            new Order("ORD-018", "CUST-Q", "SOUTH", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("EARBUDS-NOISE", "ELECTRONICS", 279.99, 1),
                    new OrderItem("CHARGER-FAST", "ELECTRONICS", 49.99, 1)
                ), LocalDate.now().minusDays(14)),

            new Order("ORD-019", "CUST-R", "EAST", OrderStatus.DELIVERED,
                List.of(
                    new OrderItem("FITNESS-TRACKER", "SPORTS", 199.99, 1),
                    new OrderItem("BAND-EXTRA", "SPORTS", 29.99, 3)
                ), LocalDate.now().minusDays(11)),

            new Order("ORD-020", "CUST-S", "NORTH", OrderStatus.PENDING,
                List.of(
                    new OrderItem("SMART-WATCH", "ELECTRONICS", 399.99, 1),
                    new OrderItem("WATCH-BAND", "ELECTRONICS", 39.99, 2)
                ), LocalDate.now().minusDays(3))
        );
    }
}
