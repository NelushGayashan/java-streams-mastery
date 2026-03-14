package com.streamsmastery.collectors;

import com.streamsmastery.model.Order;
import com.streamsmastery.model.OrderDataFactory;
import com.streamsmastery.model.OrderSummary;
import com.streamsmastery.model.OrderStatus;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SECTION 3: BUILDING A CUSTOM COLLECTOR FROM SCRATCH
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * The Collector<T, A, R> interface has three type parameters:
 *   T — the type of element being collected (Order, in our case)
 *   A — the mutable accumulator type (our intermediate container)
 *   R — the result type returned after collection (OrderSummary)
 *
 * A Collector is defined by 5 components:
 *
 *  1. supplier()     → creates a new, empty accumulator
 *  2. accumulator()  → folds one element into the accumulator
 *  3. combiner()     → merges two accumulators (used in parallel streams)
 *  4. finisher()     → transforms the accumulator into the final result R
 *  5. characteristics() → hints to the runtime (IDENTITY_FINISH, CONCURRENT, UNORDERED)
 *
 * WHEN should you build a custom Collector?
 *   - When Collector.of() in a single expression becomes hard to read
 *   - When the accumulation logic is complex or stateful
 *   - When you need the same aggregation in multiple places (reusability)
 *   - When you need to compute multiple aggregates in a single pass
 *     without using teeing() (e.g., 4+ aggregates simultaneously)
 *
 * Our custom collector computes an OrderSummary which requires:
 *   • total order count
 *   • total revenue (sum)
 *   • average order value
 *   • count of high-value orders (>$500)
 *   • top region by revenue
 *
 * All in ONE PASS over the dataset.
 */
public class OrderSummaryCollector implements Collector<Order, OrderSummaryCollector.Accumulator, OrderSummary> {

    // ─────────────────────────────────────────────────────────
    // THE ACCUMULATOR: our mutable intermediate container
    // ─────────────────────────────────────────────────────────

    /**
     * The Accumulator is the "work-in-progress" state as we process
     * each Order element. It tracks everything we need to produce
     * our final OrderSummary.
     *
     * It must be mutable (so we can update it element by element)
     * but should be self-contained (no external state dependencies).
     *
     * Note: We track revenueByRegion as a Map<String, Double> so we
     * can determine the top region in the finisher step without a
     * second pass over the data.
     */
    static class Accumulator {
        long totalOrders = 0;
        double totalRevenue = 0.0;
        long highValueCount = 0;
        // Track revenue per region so finisher can find the top one
        Map<String, Double> revenueByRegion = new HashMap<>();

        /**
         * Merges another accumulator into this one.
         * Called by the combiner() during parallel stream execution.
         * Each parallel thread has its own Accumulator; this method
         * merges the partial result from one thread into another.
         */
        void merge(Accumulator other) {
            this.totalOrders    += other.totalOrders;
            this.totalRevenue   += other.totalRevenue;
            this.highValueCount += other.highValueCount;
            // Merge the region maps: sum revenues for matching regions
            other.revenueByRegion.forEach((region, revenue) ->
                    this.revenueByRegion.merge(region, revenue, Double::sum));
        }
    }

    // ─────────────────────────────────────────────────────────
    // THE FIVE COLLECTOR COMPONENTS
    // ─────────────────────────────────────────────────────────

    /**
     * Component 1: supplier()
     *
     * Returns a Supplier that creates a brand-new, empty Accumulator.
     * This is called:
     *   - Once at the start of sequential collection
     *   - Once per parallel thread in parallel collection
     */
    @Override
    public Supplier<Accumulator> supplier() {
        return Accumulator::new; // equivalent to: () -> new Accumulator()
    }

    /**
     * Component 2: accumulator()
     *
     * Returns a BiConsumer that folds one Order element into the
     * Accumulator. This is the core logic — called once per element.
     *
     * This runs in the "hot path" of the collector, so keep it lean.
     * No allocations, no streams-within-streams here.
     */
    @Override
    public BiConsumer<Accumulator, Order> accumulator() {
        return (acc, order) -> {
            double revenue = order.totalRevenue();

            acc.totalOrders++;
            acc.totalRevenue += revenue;

            // Tally high-value orders (our business rule: >$500)
            if (order.isHighValue()) {
                acc.highValueCount++;
            }

            // Accumulate revenue per region using Map.merge()
            // Map.merge(key, value, remappingFunction):
            //   - If key absent: puts value directly
            //   - If key present: applies remappingFunction(existingValue, value)
            // This is cleaner than the old: getOrDefault + put pattern
            acc.revenueByRegion.merge(order.region(), revenue, Double::sum);
        };
    }

    /**
     * Component 3: combiner()
     *
     * Returns a BinaryOperator that merges two partial Accumulators
     * into one. Only called during PARALLEL stream execution.
     *
     * In sequential streams, this function is never invoked.
     * In parallel streams, the data is split into chunks, each chunk
     * gets its own Accumulator, and combiner() reassembles the parts.
     *
     * If your Accumulator is not safely mergeable, you should add
     * Characteristics.CONCURRENT to characteristics() instead.
     */
    @Override
    public BinaryOperator<Accumulator> combiner() {
        return (left, right) -> {
            left.merge(right); // merge right into left
            return left;       // return the merged result
        };
    }

    /**
     * Component 4: finisher()
     *
     * Transforms the final Accumulator into the desired result type R.
     * Called exactly once, after all elements have been accumulated
     * (and combined, in parallel mode).
     *
     * This is where we do the final computation that requires seeing
     * all accumulated data — like finding the top region.
     */
    @Override
    public Function<Accumulator, OrderSummary> finisher() {
        return acc -> {
            // Compute average from totals (no need to track a running average)
            double average = acc.totalOrders > 0
                    ? acc.totalRevenue / acc.totalOrders
                    : 0.0;

            // Find the top region: the one with the highest total revenue
            // This requires seeing ALL region data, hence it's done in finisher
            String topRegion = acc.revenueByRegion.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("N/A");

            return new OrderSummary(
                    acc.totalOrders,
                    acc.totalRevenue,
                    average,
                    acc.highValueCount,
                    topRegion
            );
        };
    }

    /**
     * Component 5: characteristics()
     *
     * Hints to the stream runtime about this collector's behavior.
     *
     * IDENTITY_FINISH: finisher() is identity — skip the finisher call
     *                  (only applicable when A == R, not our case)
     *
     * UNORDERED: result doesn't depend on encounter order
     *            (safe to declare here — our aggregation is order-agnostic)
     *
     * CONCURRENT: accumulator can be called concurrently without synchronization
     *             (we do NOT declare this — our Accumulator is NOT thread-safe)
     */
    @Override
    public Set<Characteristics> characteristics() {
        return Set.of(Characteristics.UNORDERED);
        // Note: NOT IDENTITY_FINISH because our finisher does real work
        // Note: NOT CONCURRENT because HashMap is not thread-safe
    }

    // ─────────────────────────────────────────────────────────
    // USAGE DEMO
    // ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        List<Order> orders = OrderDataFactory.createSampleOrders();

        System.out.println("=== Custom OrderSummaryCollector Demo ===\n");

        // ── Usage 1: Collect ALL orders ─────────────────────
        OrderSummary allOrdersSummary = orders.stream()
                .collect(new OrderSummaryCollector());

        System.out.println("ALL ORDERS:");
        System.out.println(allOrdersSummary);

        // ── Usage 2: Collect only DELIVERED orders ──────────
        OrderSummary deliveredSummary = orders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .collect(new OrderSummaryCollector());

        System.out.println("DELIVERED ORDERS ONLY:");
        System.out.println(deliveredSummary);

        // ── Usage 3: As a downstream collector in groupingBy ─
        // This is where custom collectors really shine — they compose
        // cleanly with the rest of the Collectors API.
        Map<String, OrderSummary> summaryByRegion = orders.stream()
                .collect(Collectors.groupingBy(
                        Order::region,
                        new OrderSummaryCollector() // ← custom collector as downstream
                ));

        System.out.println("SUMMARY BY REGION:");
        summaryByRegion.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf(
                        "  [%s] Orders: %d | Revenue: $%,.2f | Top Region: %s%n",
                        e.getKey(),
                        e.getValue().totalOrders(),
                        e.getValue().totalRevenue(),
                        e.getValue().topRegion()
                ));
    }
}
