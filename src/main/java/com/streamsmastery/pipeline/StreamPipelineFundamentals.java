package com.streamsmastery.pipeline;

import com.streamsmastery.model.Order;
import com.streamsmastery.model.OrderDataFactory;
import com.streamsmastery.model.OrderStatus;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SECTION 1: THE ANATOMY OF A STREAM PIPELINE
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * A stream pipeline has exactly three parts:
 *
 *   SOURCE → INTERMEDIATE OPERATIONS → TERMINAL OPERATION
 *
 * The most important thing to understand about streams:
 * INTERMEDIATE OPERATIONS ARE LAZY. No work happens until a
 * terminal operation is called. This is not a detail — it is
 * the entire design philosophy, and it enables huge optimizations.
 *
 * In this class we demonstrate:
 *  1. The lazy evaluation trap: what developers get wrong
 *  2. Operation ordering and its performance impact
 *  3. Short-circuit operations: anyMatch, findFirst, limit
 *  4. The flatMap operation: the most misunderstood intermediate op
 *  5. Method references vs lambdas: when each is appropriate
 */
public class StreamPipelineFundamentals {

    private final List<Order> orders;

    public StreamPipelineFundamentals() {
        this.orders = OrderDataFactory.createSampleOrders();
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 1: Lazy Evaluation — Proven with a Counter
    // ─────────────────────────────────────────────────────────

    /**
     * Demonstrates that intermediate operations are NOT executed
     * until a terminal operation triggers the pipeline.
     *
     * We use a counter to prove this: the counter only increments
     * when filter() actually processes an element.
     *
     * KEY INSIGHT: In the "no terminal" case, the counter stays at 0.
     * In the "with terminal" case, thanks to short-circuiting with
     * findFirst(), the entire list is NOT necessarily scanned.
     * The pipeline stops as soon as one match is found.
     */
    public void demonstrateLazyEvaluation() {
        System.out.println("\n═══ DEMO 1: Lazy Evaluation ═══");

        int[] filterCallCount = {0}; // Using array to allow mutation inside lambda

        // Build the pipeline — NOTHING executes here yet.
        // This is just a description of what to do, not the doing of it.
        var pipeline = orders.stream()
                .filter(order -> {
                    filterCallCount[0]++;  // This line does NOT run yet
                    return order.status() == OrderStatus.DELIVERED;
                })
                .map(Order::id);

        System.out.printf("After building pipeline: filter called %d times%n",
                filterCallCount[0]); // Prints: 0

        // NOW the terminal operation triggers execution.
        // But because findFirst() short-circuits, it stops after
        // finding the first match — not after scanning all 20 orders.
        Optional<String> firstDelivered = pipeline.findFirst();

        System.out.printf("After findFirst(): filter called %d times%n",
                filterCallCount[0]); // Prints: 1 or 2 (stops at first match)

        System.out.printf("First delivered order: %s%n",
                firstDelivered.orElse("None"));
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 2: Operation Order Matters — A Performance Lesson
    // ─────────────────────────────────────────────────────────

    /**
     * Demonstrates how the ORDER of intermediate operations directly
     * impacts performance.
     *
     * WRONG ORDER: map() then filter()
     *   - map() transforms ALL 20 orders (expensive operation runs 20x)
     *   - filter() then discards most results
     *   → We did expensive work on elements we were going to throw away
     *
     * RIGHT ORDER: filter() then map()
     *   - filter() first reduces from 20 to ~6 DELIVERED orders
     *   - map() only transforms those 6 (expensive operation runs 6x)
     *   → We only do expensive work on elements that survive the filter
     *
     * RULE OF THUMB: Put filters before transformations.
     */
    public void demonstrateOperationOrder() {
        System.out.println("\n═══ DEMO 2: Operation Order Performance ═══");

        int[] mapCallCount = {0};

        // ❌ INEFFICIENT: map() before filter()
        // The expensive toString() call runs for ALL 20 orders
        mapCallCount[0] = 0;
        List<String> inefficientResult = orders.stream()
                .map(order -> {
                    mapCallCount[0]++;
                    // Simulate an "expensive" transformation
                    return "[%s] %s → $%.2f".formatted(
                            order.region(), order.id(), order.totalRevenue());
                })
                .filter(s -> s.contains("NORTH"))  // Filter AFTER expensive map
                .collect(Collectors.toList());

        System.out.printf("INEFFICIENT: map() called %d times for %d results%n",
                mapCallCount[0], inefficientResult.size());

        // ✅ EFFICIENT: filter() before map()
        // Now map() only runs for orders that survive the filter
        mapCallCount[0] = 0;
        List<String> efficientResult = orders.stream()
                .filter(order -> order.region().equals("NORTH")) // Filter FIRST
                .map(order -> {
                    mapCallCount[0]++;
                    return "[%s] %s → $%.2f".formatted(
                            order.region(), order.id(), order.totalRevenue());
                })
                .collect(Collectors.toList());

        System.out.printf("EFFICIENT:   map() called %d times for %d results%n",
                mapCallCount[0], efficientResult.size());
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 3: flatMap — Flattening Nested Structures
    // ─────────────────────────────────────────────────────────

    /**
     * flatMap is arguably the most misunderstood stream operation.
     * Here's the mental model:
     *
     *  map()     : takes one element, returns ONE element        (1 → 1)
     *  flatMap() : takes one element, returns a STREAM of zero
     *              or more elements, then FLATTENS the results   (1 → N)
     *
     * Our use case: Each Order contains a List<OrderItem>. If we
     * want to work with ALL items across ALL orders, we need flatMap
     * to "unwrap" the nested structure.
     *
     * Without flatMap:  Stream<Order>  → Stream<List<OrderItem>>   (nested!)
     * With flatMap:     Stream<Order>  → Stream<OrderItem>          (flat!)
     *
     * In our project this enables questions like:
     *  "What are the top 5 products by revenue across all orders?"
     */
    public void demonstrateFlatMap() {
        System.out.println("\n═══ DEMO 3: flatMap — Flattening Nested Data ═══");

        // Without flatMap — we get a stream of LISTS, which is hard to work with
        // Stream<List<OrderItem>> nestedStream = orders.stream()
        //         .map(Order::items);  ← This gives us Stream<List<OrderItem>>

        // With flatMap — we get a single flat stream of OrderItems
        // flatMap(Collection::stream) is the canonical way to flatten nested collections
        var topProductsByRevenue = orders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED) // Only completed orders
                .flatMap(order -> order.items().stream())           // Unwrap: Order → [Items]
                .collect(Collectors.groupingBy(
                        item -> item.productId(),
                        Collectors.summingDouble(item -> item.price() * item.quantity())
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)                                           // Top 5 only
                .collect(Collectors.toList());

        System.out.println("Top 5 Products by Revenue (Delivered Orders):");
        topProductsByRevenue.forEach(entry ->
                System.out.printf("  %-25s $%,.2f%n", entry.getKey(), entry.getValue()));
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 4: Chaining with Method References
    // ─────────────────────────────────────────────────────────

    /**
     * Method references are syntactic sugar for simple lambdas.
     * They improve readability significantly in stream pipelines.
     *
     * Four types:
     *   ClassName::staticMethod     e.g. String::valueOf
     *   instance::instanceMethod    e.g. System.out::println
     *   ClassName::instanceMethod   e.g. Order::id, String::toUpperCase
     *   ClassName::new              e.g. ArrayList::new
     *
     * Use method references when the lambda body is JUST a method call
     * with no additional logic. If you need conditional logic or
     * multiple operations, a lambda is clearer.
     */
    public void demonstrateMethodReferences() {
        System.out.println("\n═══ DEMO 4: Method References in Pipelines ═══");

        // ClassName::instanceMethod — used with map() to extract a field
        List<String> orderIds = orders.stream()
                .filter(o -> o.status() != OrderStatus.CANCELLED) // Lambda: needs condition
                .map(Order::id)                 // Method ref: ClassName::instanceMethod
                .sorted(String::compareTo)      // Method ref: ClassName::instanceMethod
                .collect(Collectors.toList());

        System.out.printf("Active order IDs: %s%n", orderIds);

        // instance::instanceMethod — common for output
        System.out.println("High-value orders:");
        orders.stream()
                .filter(Order::isHighValue)                  // Method ref: our custom predicate
                .map(o -> "%s ($%.2f)".formatted(o.id(), o.totalRevenue()))
                .forEach(System.out::println);               // instance::instanceMethod
    }

    public static void main(String[] args) {
        var demo = new StreamPipelineFundamentals();
        demo.demonstrateLazyEvaluation();
        demo.demonstrateOperationOrder();
        demo.demonstrateFlatMap();
        demo.demonstrateMethodReferences();
    }
}
