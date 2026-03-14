package com.streamsmastery.collectors;

import com.streamsmastery.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SECTION 2: ADVANCED COLLECTORS — THE POWER ENGINE
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * The Collectors utility class is the most underused part of
 * the Stream API. Most developers know toList() and maybe
 * groupingBy(), but the full toolkit is far more powerful.
 *
 * In this class we cover:
 *  1. groupingBy()           — The SQL GROUP BY of Java
 *  2. groupingBy() + downstream collectors — Multi-level aggregation
 *  3. partitioningBy()       — Splitting into exactly two groups
 *  4. toMap() with merge     — Building maps with collision handling
 *  5. Collectors.teeing()    — Two collectors, one pass (Java 12+)
 *  6. Custom Collector       — Building from scratch with Collector.of()
 *
 * Each demo uses our Order dataset to answer a real business question.
 */
public class AdvancedCollectorsDemo {

    private final List<Order> orders;

    public AdvancedCollectorsDemo() {
        this.orders = OrderDataFactory.createSampleOrders();
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 1: groupingBy() — The SQL GROUP BY of Java
    // ─────────────────────────────────────────────────────────

    /**
     * Collectors.groupingBy() partitions a stream into a Map where:
     *   - Keys are the result of a classifier function
     *   - Values are Lists of elements sharing the same key
     *
     * Business Question: "How many orders do we have per region?"
     *
     * SQL equivalent:
     *   SELECT region, COUNT(*) FROM orders GROUP BY region;
     *
     * Three overloads of groupingBy():
     *   groupingBy(classifier)
     *     → Map<K, List<T>>
     *
     *   groupingBy(classifier, downstream)
     *     → Map<K, D>  where D is the result of a downstream collector
     *
     *   groupingBy(classifier, mapFactory, downstream)
     *     → Specified map type with downstream collector
     */
    public void demonstrateGroupingBy() {
        System.out.println("\n═══ DEMO 1: groupingBy() ═══");

        // Basic groupingBy: group orders by region
        // Result: Map<String, List<Order>>
        Map<String, List<Order>> ordersByRegion = orders.stream()
                .collect(Collectors.groupingBy(Order::region));

        System.out.println("Orders per region:");
        ordersByRegion.forEach((region, regionOrders) ->
                System.out.printf("  %-10s → %d orders%n", region, regionOrders.size()));

        // ── Downstream Collector: counting() ──────────────────
        // Instead of a List, get the count directly.
        // SQL: SELECT region, COUNT(*) FROM orders GROUP BY region
        Map<String, Long> countByRegion = orders.stream()
                .collect(Collectors.groupingBy(
                        Order::region,
                        Collectors.counting()   // ← downstream collector
                ));

        System.out.println("\nOrder count per region:");
        countByRegion.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-10s → %d%n", e.getKey(), e.getValue()));

        // ── Downstream Collector: summingDouble() ─────────────
        // SQL: SELECT region, SUM(revenue) FROM orders GROUP BY region
        Map<String, Double> revenueByRegion = orders.stream()
                .collect(Collectors.groupingBy(
                        Order::region,
                        Collectors.summingDouble(Order::totalRevenue) // sum of revenues per group
                ));

        System.out.println("\nRevenue per region:");
        revenueByRegion.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-10s → $%,.2f%n", e.getKey(), e.getValue()));
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 2: Multi-Level groupingBy() — Nested Aggregation
    // ─────────────────────────────────────────────────────────

    /**
     * groupingBy() can be nested: group by one key, then within
     * each group, group by another key.
     *
     * Business Question: "What is the revenue per category per region?"
     *
     * SQL equivalent:
     *   SELECT region, category, SUM(revenue)
     *   FROM order_items
     *   GROUP BY region, category;
     *
     * The key insight: the "downstream" collector in groupingBy() can
     * itself be another groupingBy() — creating a Map<K1, Map<K2, V>>.
     */
    public void demonstrateMultiLevelGrouping() {
        System.out.println("\n═══ DEMO 2: Multi-Level groupingBy() ═══");

        // Flatten orders → items, keeping region context via a temp record
        record ItemWithRegion(String region, String category, double revenue) {}

        Map<String, Map<String, Double>> revenueByCategoryPerRegion = orders.stream()
                .flatMap(order -> order.items().stream()
                        // Attach region from the parent Order to each item
                        .map(item -> new ItemWithRegion(
                                order.region(),
                                item.category(),
                                item.subtotal()
                        ))
                )
                // First level: group by region
                .collect(Collectors.groupingBy(
                        ItemWithRegion::region,
                        // Second level (downstream): group by category, sum revenue
                        Collectors.groupingBy(
                                ItemWithRegion::category,
                                Collectors.summingDouble(ItemWithRegion::revenue)
                        )
                ));

        System.out.println("Revenue by Category per Region:");
        revenueByCategoryPerRegion.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(regionEntry -> {
                    System.out.printf("  [%s]%n", regionEntry.getKey());
                    regionEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                            .forEach(catEntry ->
                                    System.out.printf("    %-15s $%,.2f%n",
                                            catEntry.getKey(), catEntry.getValue()));
                });
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 3: partitioningBy() — Boolean Splits
    // ─────────────────────────────────────────────────────────

    /**
     * Collectors.partitioningBy() is a specialized form of groupingBy()
     * that splits a stream into exactly TWO groups based on a Predicate.
     * The result is always a Map<Boolean, List<T>>.
     *
     * Use partitioningBy() when:
     *   - You need exactly two groups (true/false)
     *   - The split is based on a boolean condition
     *   - You need access to BOTH sides of the partition
     *
     * Vs groupingBy():
     *   - groupingBy() for 2+ distinct categories
     *   - partitioningBy() for binary true/false splits
     *
     * Business Question: "Separate high-value orders from standard orders"
     */
    public void demonstratePartitioningBy() {
        System.out.println("\n═══ DEMO 3: partitioningBy() ═══");

        // Split ALL orders into high-value vs standard
        // Order::isHighValue is our predicate: totalRevenue > 500
        Map<Boolean, List<Order>> partitioned = orders.stream()
                .collect(Collectors.partitioningBy(Order::isHighValue));

        List<Order> highValueOrders   = partitioned.get(true);   // revenue > $500
        List<Order> standardOrders    = partitioned.get(false);  // revenue ≤ $500

        System.out.printf("High-value orders (>$500): %d%n", highValueOrders.size());
        highValueOrders.stream()
                .sorted(Comparator.comparingDouble(Order::totalRevenue).reversed())
                .forEach(o -> System.out.printf("  %s  $%,.2f  [%s]%n",
                        o.id(), o.totalRevenue(), o.status()));

        System.out.printf("%nStandard orders (≤$500): %d%n", standardOrders.size());

        // With a downstream collector — count per partition
        Map<Boolean, Long> countByValueTier = orders.stream()
                .collect(Collectors.partitioningBy(
                        Order::isHighValue,
                        Collectors.counting() // ← downstream: count instead of list
                ));

        System.out.printf("%nPartition counts: high-value=%d, standard=%d%n",
                countByValueTier.get(true), countByValueTier.get(false));
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 4: toMap() with Merge Function
    // ─────────────────────────────────────────────────────────

    /**
     * Collectors.toMap() builds a Map from stream elements.
     * The three-argument overload is the one you actually need in production.
     *
     * toMap(keyMapper, valueMapper)
     *   → Throws IllegalStateException if duplicate keys exist
     *
     * toMap(keyMapper, valueMapper, mergeFunction)
     *   → mergeFunction resolves key collisions
     *   → This is the safe version you should default to
     *
     * Business Question: "Get the latest order per customer"
     * Collision case: Customer CUST-A has two orders. We keep the newer one.
     */
    public void demonstrateToMap() {
        System.out.println("\n═══ DEMO 4: toMap() with Merge Function ═══");

        // ❌ DANGEROUS — will throw if a customer has >1 order
        // Map<String, Order> latestByCustomer = orders.stream()
        //     .collect(Collectors.toMap(Order::customerId, o -> o)); // Throws!

        // ✅ SAFE — merge function picks the more recent order on collision
        // The merge function (o1, o2) -> ... is called whenever two entries
        // share the same key. We return the one with the later orderDate.
        Map<String, Order> latestOrderByCustomer = orders.stream()
                .collect(Collectors.toMap(
                        Order::customerId,                 // key: customer ID
                        order -> order,                    // value: the order itself
                        (existing, replacement) ->         // merge: keep the newer order
                                existing.orderDate().isAfter(replacement.orderDate())
                                        ? existing : replacement
                ));

        System.out.printf("Latest orders per customer (%d unique customers):%n",
                latestOrderByCustomer.size());
        latestOrderByCustomer.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(5) // Show first 5 for brevity
                .forEach(e -> System.out.printf("  %-10s → %s (%s)%n",
                        e.getKey(), e.getValue().id(), e.getValue().orderDate()));

        // toMap for revenue lookup: productId → total revenue across all orders
        Map<String, Double> revenuePerProduct = orders.stream()
                .flatMap(o -> o.items().stream())
                .collect(Collectors.toMap(
                        item -> item.productId(),
                        item -> item.subtotal(),
                        Double::sum             // merge: add revenues for same product
                ));

        System.out.printf("%nUnique products tracked: %d%n", revenuePerProduct.size());
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 5: Collectors.teeing() — Two Collectors, One Pass
    // ─────────────────────────────────────────────────────────

    /**
     * Collectors.teeing() is the most powerful and least-known collector.
     * Added in Java 12, it solves a problem that used to require two separate
     * stream passes.
     *
     * teeing(downstream1, downstream2, merger)
     *   - Sends every element to BOTH downstream1 AND downstream2
     *   - Applies merger to combine both results into a final output
     *
     * The name comes from a "tee" fitting in plumbing — one input, two outputs.
     *
     * WHY IT MATTERS:
     * Before teeing(), calculating both min AND max of a stream required
     * either storing the list or making two passes. With teeing(), one pass
     * feeds both collectors simultaneously. This is significant for large
     * datasets or IO-backed streams that can only be consumed once.
     *
     * Business Question:
     * "In a single pass, compute: total revenue AND count of high-value orders"
     */
    public void demonstrateTeeing() {
        System.out.println("\n═══ DEMO 5: Collectors.teeing() (Java 12+) ═══");

        // ── Simple teeing: min AND max revenue in one pass ─────────────
        // Before Java 12, this required two streams or storing the list.
        // Now: one pass, two results, merged into a record.
        record RevenueRange(double min, double max) {}

        RevenueRange range = orders.stream()
                .collect(Collectors.teeing(
                        // Collector 1: find the minimum revenue order
                        Collectors.minBy(Comparator.comparingDouble(Order::totalRevenue)),
                        // Collector 2: find the maximum revenue order
                        Collectors.maxBy(Comparator.comparingDouble(Order::totalRevenue)),
                        // Merger: combine both Optional<Order> results
                        (minOpt, maxOpt) -> new RevenueRange(
                                minOpt.map(Order::totalRevenue).orElse(0.0),
                                maxOpt.map(Order::totalRevenue).orElse(0.0)
                        )
                ));

        System.out.printf("Revenue range: $%.2f → $%.2f%n", range.min(), range.max());

        // ── Advanced teeing: compute summary stats in ONE pass ─────────
        // This answers: "What is the total revenue AND the average order value?"
        // Both require a single traversal of the orders collection.
        record TotalAndAverage(double total, double average) {}

        TotalAndAverage stats = orders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .collect(Collectors.teeing(
                        // Collector 1: sum all revenues
                        Collectors.summingDouble(Order::totalRevenue),
                        // Collector 2: average all revenues
                        Collectors.averagingDouble(Order::totalRevenue),
                        // Merger: combine sum + average into our record
                        TotalAndAverage::new
                ));

        System.out.printf("DELIVERED orders: total=$%,.2f | avg=$%.2f%n",
                stats.total(), stats.average());

        // ── Production teeing: partition AND count simultaneously ───────
        // "How many orders are above $500 AND how many are below, in one pass?"
        record PartitionSummary(long highCount, long standardCount) {}

        PartitionSummary summary = orders.stream()
                .collect(Collectors.teeing(
                        // Collector 1: count high-value orders
                        Collectors.filtering(Order::isHighValue, Collectors.counting()),
                        // Collector 2: count standard orders
                        Collectors.filtering(o -> !o.isHighValue(), Collectors.counting()),
                        PartitionSummary::new
                ));

        System.out.printf("High-value: %d | Standard: %d%n",
                summary.highCount(), summary.standardCount());
    }

    public static void main(String[] args) {
        var demo = new AdvancedCollectorsDemo();
        demo.demonstrateGroupingBy();
        demo.demonstrateMultiLevelGrouping();
        demo.demonstratePartitioningBy();
        demo.demonstrateToMap();
        demo.demonstrateTeeing();
    }
}
