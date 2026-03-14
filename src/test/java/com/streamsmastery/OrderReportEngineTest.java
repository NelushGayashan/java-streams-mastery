package com.streamsmastery;

import com.streamsmastery.collectors.OrderSummaryCollector;
import com.streamsmastery.model.*;
import com.streamsmastery.realworld.OrderReportEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OrderReportEngine and OrderSummaryCollector.
 *
 * These tests verify that our stream pipelines produce correct results.
 * Testing streams is no different from testing any other code —
 * the key is to test the OUTPUT, not the internal stream mechanics.
 *
 * Test strategy:
 *   - Use the full sample dataset for integration-style tests
 *   - Use small, controlled datasets for precise unit tests
 */
@DisplayName("Order Report Engine Tests")
class OrderReportEngineTest {

    private List<Order> sampleOrders;
    private OrderReportEngine engine;

    @BeforeEach
    void setUp() {
        sampleOrders = OrderDataFactory.createSampleOrders();
        engine = new OrderReportEngine(sampleOrders);
    }

    @Test
    @DisplayName("Executive summary excludes cancelled orders")
    void executiveSummaryExcludesCancelledOrders() {
        OrderSummary summary = engine.generateExecutiveSummary();

        // Count non-cancelled orders manually to verify
        long expectedCount = sampleOrders.stream()
                .filter(o -> o.status() != OrderStatus.CANCELLED)
                .count();

        assertEquals(expectedCount, summary.totalOrders(),
                "Summary should only count non-cancelled orders");
    }

    @Test
    @DisplayName("Total revenue is positive and non-zero")
    void totalRevenueIsPositive() {
        OrderSummary summary = engine.generateExecutiveSummary();
        assertTrue(summary.totalRevenue() > 0,
                "Total revenue should be positive");
    }

    @Test
    @DisplayName("Average order value equals total revenue / total orders")
    void averageOrderValueCalculation() {
        OrderSummary summary = engine.generateExecutiveSummary();
        double expectedAvg = summary.totalRevenue() / summary.totalOrders();

        assertEquals(expectedAvg, summary.averageOrderValue(), 0.01,
                "Average order value should equal totalRevenue / totalOrders");
    }

    @Test
    @DisplayName("Regional report contains all 4 regions")
    void regionalReportContainsAllRegions() {
        Map<String, OrderSummary> regional = engine.generateRegionalReport();

        assertTrue(regional.containsKey("NORTH"), "Should contain NORTH");
        assertTrue(regional.containsKey("SOUTH"), "Should contain SOUTH");
        assertTrue(regional.containsKey("EAST"),  "Should contain EAST");
        assertTrue(regional.containsKey("WEST"),  "Should contain WEST");
    }

    @Test
    @DisplayName("Regional revenue sums match overall total")
    void regionalRevenueSumsMatchTotal() {
        // All regional revenues combined should equal the overall total
        Map<String, OrderSummary> regional = engine.generateRegionalReport();
        OrderSummary overall = engine.generateExecutiveSummary();

        double regionSum = regional.values().stream()
                .mapToDouble(OrderSummary::totalRevenue)
                .sum();

        assertEquals(overall.totalRevenue(), regionSum, 0.01,
                "Sum of regional revenues should equal overall total");
    }

    @Test
    @DisplayName("Category revenue report only includes DELIVERED orders")
    void categoryRevenueOnlyIncludesDeliveredOrders() {
        Map<String, Double> categoryRevenue = engine.generateCategoryRevenueReport();

        // Manually compute expected total from DELIVERED orders
        double expectedTotal = sampleOrders.stream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .flatMap(o -> o.items().stream())
                .mapToDouble(OrderItem::subtotal)
                .sum();

        double actualTotal = categoryRevenue.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();

        assertEquals(expectedTotal, actualTotal, 0.01,
                "Category revenue should only include delivered order items");
    }

    @Test
    @DisplayName("High-value orders threshold is $500")
    void highValueThresholdIsCorrect() {
        OrderSummary summary = engine.generateExecutiveSummary();

        long expectedHighValue = sampleOrders.stream()
                .filter(o -> o.status() != OrderStatus.CANCELLED)
                .filter(Order::isHighValue)
                .count();

        assertEquals(expectedHighValue, summary.highValueCount(),
                "High-value count should match orders with revenue > $500");
    }

    @Test
    @DisplayName("Recent high-value orders are sorted by revenue descending")
    void recentHighValueOrdersSortedDescending() {
        List<Order> highValueOrders = engine.findRecentHighValueOrders(90);

        assertFalse(highValueOrders.isEmpty(), "Should find some high-value orders in 90 days");

        // Verify descending sort
        for (int i = 0; i < highValueOrders.size() - 1; i++) {
            assertTrue(
                    highValueOrders.get(i).totalRevenue() >= highValueOrders.get(i + 1).totalRevenue(),
                    "Orders should be sorted by revenue descending"
            );
        }
    }

    @Test
    @DisplayName("Pending inventory demand aggregates units correctly")
    void pendingInventoryAggregatesUnits() {
        Map<String, Integer> demand = engine.getPendingInventoryDemand();

        // SMART-WATCH appears in ORD-020 (PENDING) with qty 1
        // WATCH-BAND appears in ORD-020 (PENDING) with qty 2
        assertTrue(demand.containsKey("SMART-WATCH"),
                "SMART-WATCH should appear in pending demand");
        assertEquals(1, demand.get("SMART-WATCH"),
                "SMART-WATCH should have 1 unit pending");
    }

    @Test
    @DisplayName("Custom collector is reusable as downstream in groupingBy")
    void customCollectorWorksAsDownstream() {
        // The custom collector should work as a downstream collector
        // This verifies the Collector contract is correctly implemented
        Map<OrderStatus, OrderSummary> byStatus = sampleOrders.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Order::status,
                        new OrderSummaryCollector()
                ));

        assertTrue(byStatus.containsKey(OrderStatus.DELIVERED),
                "Should have summary for DELIVERED status");
        assertTrue(byStatus.get(OrderStatus.DELIVERED).totalOrders() > 0,
                "DELIVERED summary should have orders");
    }

    @Test
    @DisplayName("Order.isHighValue() correctly identifies orders above $500")
    void orderHighValueThreshold() {
        // ORD-013 has GAMING-PC ($1249.99) + others — clearly high value
        Order highValueOrder = sampleOrders.stream()
                .filter(o -> o.id().equals("ORD-013"))
                .findFirst()
                .orElseThrow();

        assertTrue(highValueOrder.isHighValue(),
                "ORD-013 (GAMING-PC) should be high-value");

        // ORD-003 has JAVA-BOOK ($59.99 x2) + DESIGN-BOOK ($49.99) = $169.97
        Order standardOrder = sampleOrders.stream()
                .filter(o -> o.id().equals("ORD-003"))
                .findFirst()
                .orElseThrow();

        assertFalse(standardOrder.isHighValue(),
                "ORD-003 (books only) should NOT be high-value");
    }
}
