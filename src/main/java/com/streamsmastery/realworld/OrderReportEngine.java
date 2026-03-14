package com.streamsmastery.realworld;

import com.streamsmastery.collectors.OrderSummaryCollector;
import com.streamsmastery.model.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SECTION 5: PUTTING IT ALL TOGETHER — THE ORDER REPORT ENGINE
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * This is the capstone of the article — a realistic reporting service
 * that uses every technique we've covered:
 *
 *   ✓ filter + map chains (basics, done right)
 *   ✓ flatMap for nested order items
 *   ✓ groupingBy with downstream collectors
 *   ✓ teeing() for multi-aggregate single-pass computation
 *   ✓ Custom OrderSummaryCollector
 *   ✓ toMap() with merge function
 *   ✓ partitioningBy()
 *   ✓ Parallel streams where appropriate
 *
 * This is the class you'd actually find in a production codebase.
 * Each method answers a concrete business question.
 */
public class OrderReportEngine {

    private final List<Order> orders;

    public OrderReportEngine(List<Order> orders) {
        this.orders = orders;
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 1: Executive Dashboard
    // ─────────────────────────────────────────────────────────

    /**
     * Generates the top-level summary used on the executive dashboard.
     *
     * Uses our custom OrderSummaryCollector to compute all KPIs
     * in a single pass over the data — no multiple iterations.
     *
     * Applied patterns:
     *   • Custom Collector (OrderSummaryCollector)
     *   • filter() before heavy operations
     */
    public OrderSummary generateExecutiveSummary() {
        return orders.stream()
                // Only count revenue from completed orders (business rule)
                .filter(o -> o.status() != OrderStatus.CANCELLED)
                .collect(new OrderSummaryCollector());
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 2: Regional Performance Matrix
    // ─────────────────────────────────────────────────────────

    /**
     * Produces a Map<Region, OrderSummary> — one summary per region.
     *
     * This is where custom collectors REALLY prove their value:
     * by using OrderSummaryCollector as a DOWNSTREAM collector inside
     * groupingBy(), we get per-region summaries in one elegant pipeline.
     *
     * Applied patterns:
     *   • groupingBy() with custom downstream Collector
     *   • TreeMap for sorted output (3-arg groupingBy overload)
     */
    public Map<String, OrderSummary> generateRegionalReport() {
        return orders.stream()
                .filter(o -> o.status() != OrderStatus.CANCELLED)
                .collect(Collectors.groupingBy(
                        Order::region,
                        TreeMap::new,                    // 3rd arg: use sorted TreeMap
                        new OrderSummaryCollector()      // custom downstream collector
                ));
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 3: Category Revenue Breakdown
    // ─────────────────────────────────────────────────────────

    /**
     * Computes revenue per product category across all delivered orders.
     *
     * The key challenge: revenue is at the OrderItem level, but we
     * need to filter at the Order level (DELIVERED status).
     * flatMap bridges this gap naturally.
     *
     * Applied patterns:
     *   • flatMap to unwrap nested collections
     *   • groupingBy + summingDouble
     *   • toMap on the sorted stream
     */
    public Map<String, Double> generateCategoryRevenueReport() {
        return orders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                // Flatten: each Order becomes its List<OrderItem>
                .flatMap(order -> order.items().stream())
                .collect(Collectors.groupingBy(
                        OrderItem::category,
                        Collectors.summingDouble(OrderItem::subtotal)
                ))
                .entrySet().stream()
                // Sort by revenue descending for readability
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                // Collect into LinkedHashMap to preserve sorted order
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 4: Customer Lifetime Value (CLV)
    // ─────────────────────────────────────────────────────────

    /**
     * Computes the total spend per customer (Customer Lifetime Value).
     * Returns customers sorted by CLV, descending.
     *
     * toMap() with a merge function handles the case where one
     * customer has multiple orders — their revenues are summed.
     *
     * Applied patterns:
     *   • toMap() with merge function
     *   • Sorting on the resulting map's entrySet
     */
    public LinkedHashMap<String, Double> generateCustomerLifetimeValue() {
        return orders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .collect(Collectors.toMap(
                        Order::customerId,
                        Order::totalRevenue,
                        Double::sum              // merge: sum revenues for repeat customers
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new       // preserves insertion (sorted) order
                ));
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 5: Fulfillment Rate Analysis
    // ─────────────────────────────────────────────────────────

    /**
     * Computes order fulfillment rate per region.
     * Fulfillment rate = DELIVERED / (total non-cancelled orders)
     *
     * teeing() lets us compute both the count of DELIVERED orders
     * AND total active orders in a single pass per region.
     *
     * Applied patterns:
     *   • groupingBy + teeing() as downstream collector
     *   • Collectors.filtering() (Java 9+)
     */
    public Map<String, Double> generateFulfillmentRateByRegion() {
        record FulfillmentData(long delivered, long total) {
            double rate() {
                return total == 0 ? 0.0 : (double) delivered / total * 100;
            }
        }

        return orders.stream()
                .filter(o -> o.status() != OrderStatus.CANCELLED) // exclude cancelled
                .collect(Collectors.groupingBy(
                        Order::region,
                        // teeing() as downstream: computes delivered count AND total count
                        Collectors.teeing(
                                // Collector 1: count only DELIVERED
                                Collectors.filtering(
                                        o -> o.status() == OrderStatus.DELIVERED,
                                        Collectors.counting()
                                ),
                                // Collector 2: count all non-cancelled
                                Collectors.counting(),
                                // Merge: create a FulfillmentData record
                                FulfillmentData::new
                        )
                ))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().rate()
                ));
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 6: Recent High-Value Orders Alert (Parallel)
    // ─────────────────────────────────────────────────────────

    /**
     * Finds all high-value orders placed in the last 7 days.
     * This is a candidate for parallel processing in production:
     *   - Query runs frequently (every 5 minutes)
     *   - Dataset could be large (millions of orders in prod)
     *   - Processing is CPU-bound and independent per order
     *
     * Applied patterns:
     *   • parallel() for large-dataset performance
     *   • Multiple filter conditions
     *   • sorted() + collect()
     */
    public List<Order> findRecentHighValueOrders(int daysBack) {
        LocalDate cutoff = LocalDate.now().minusDays(daysBack);

        return orders.parallelStream()              // parallel: suitable for large datasets
                .filter(o -> o.status() != OrderStatus.CANCELLED)
                .filter(Order::isHighValue)         // business rule: > $500
                .filter(o -> o.orderDate().isAfter(cutoff))
                // Back to sequential for the final sort (order guarantee needed)
                .sequential()
                .sorted(Comparator.comparingDouble(Order::totalRevenue).reversed())
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────
    // REPORT 7: Inventory Risk — Items Across Pending Orders
    // ─────────────────────────────────────────────────────────

    /**
     * Aggregates pending order items to identify inventory demand.
     * Returns a map of productId → total units committed to pending orders.
     *
     * This is a classic flatMap + toMap use case: we need to "unpack"
     * the nested structure (Order → List<OrderItem>) and then aggregate
     * by product.
     *
     * Applied patterns:
     *   • flatMap with record to carry parent Order context
     *   • toMap with merge function
     */
    public Map<String, Integer> getPendingInventoryDemand() {
        // Local record to carry item + its parent order's status context
        record ItemContext(String productId, String category, int quantity) {}

        return orders.stream()
                .filter(o -> o.status() == OrderStatus.PENDING)
                .flatMap(order -> order.items().stream()
                        .map(item -> new ItemContext(
                                item.productId(),
                                item.category(),
                                item.quantity()
                        ))
                )
                .collect(Collectors.toMap(
                        ItemContext::productId,
                        ItemContext::quantity,
                        Integer::sum            // merge: sum quantities for same product
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    // ─────────────────────────────────────────────────────────
    // RUNNER: Print All Reports
    // ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        List<Order> orders = OrderDataFactory.createSampleOrders();
        OrderReportEngine engine = new OrderReportEngine(orders);

        // ── Report 1: Executive Summary ──────────────────────
        System.out.println("━━━ REPORT 1: EXECUTIVE SUMMARY ━━━");
        System.out.println(engine.generateExecutiveSummary());

        // ── Report 2: Regional Performance ───────────────────
        System.out.println("━━━ REPORT 2: REGIONAL PERFORMANCE ━━━");
        engine.generateRegionalReport().forEach((region, summary) ->
                System.out.printf("  %-8s Orders: %2d | Revenue: $%,8.2f | Avg: $%.2f%n",
                        region, summary.totalOrders(), summary.totalRevenue(), summary.averageOrderValue()));

        // ── Report 3: Category Revenue ────────────────────────
        System.out.println("\n━━━ REPORT 3: CATEGORY REVENUE (DELIVERED) ━━━");
        engine.generateCategoryRevenueReport().forEach((cat, rev) ->
                System.out.printf("  %-15s $%,.2f%n", cat, rev));

        // ── Report 4: Customer Lifetime Value ─────────────────
        System.out.println("\n━━━ REPORT 4: CUSTOMER LIFETIME VALUE ━━━");
        engine.generateCustomerLifetimeValue().entrySet().stream()
                .limit(5)
                .forEach(e -> System.out.printf("  %-12s $%,.2f%n", e.getKey(), e.getValue()));

        // ── Report 5: Fulfillment Rate ────────────────────────
        System.out.println("\n━━━ REPORT 5: FULFILLMENT RATE BY REGION ━━━");
        engine.generateFulfillmentRateByRegion().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-8s %.1f%%%n", e.getKey(), e.getValue()));

        // ── Report 6: High-Value Alerts ───────────────────────
        System.out.println("\n━━━ REPORT 6: RECENT HIGH-VALUE ORDERS (30 days) ━━━");
        engine.findRecentHighValueOrders(30).forEach(o ->
                System.out.printf("  %s  $%,.2f  [%s]  %s%n",
                        o.id(), o.totalRevenue(), o.region(), o.orderDate()));

        // ── Report 7: Pending Inventory Demand ───────────────
        System.out.println("\n━━━ REPORT 7: PENDING INVENTORY DEMAND ━━━");
        engine.getPendingInventoryDemand().forEach((product, qty) ->
                System.out.printf("  %-25s %d units%n", product, qty));
    }
}
