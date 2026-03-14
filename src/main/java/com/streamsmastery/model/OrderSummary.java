package com.streamsmastery.model;

/**
 * A summary record produced by our custom Collector and teeing() examples.
 *
 * This DTO (Data Transfer Object) represents the aggregated result of
 * analyzing a collection of orders. It's the *output* of our most advanced
 * stream pipelines.
 *
 * Why a separate summary class?
 * Stream terminal operations often need to produce a structured result
 * that doesn't map 1-to-1 to any single domain object. Rather than
 * returning a raw Map<String, Object>, a typed record makes the
 * result clear and IDE-friendly.
 *
 * Fields:
 *  - totalOrders       : How many orders were analyzed
 *  - totalRevenue      : Sum of all order revenues
 *  - averageOrderValue : Mean revenue per order
 *  - highValueCount    : Orders above $500 threshold
 *  - topRegion         : Region with highest total revenue
 */
public record OrderSummary(
        long totalOrders,
        double totalRevenue,
        double averageOrderValue,
        long highValueCount,
        String topRegion
) {
    @Override
    public String toString() {
        return String.format(
            """
            ╔══════════════════════════════════════╗
            ║         ORDER SUMMARY REPORT         ║
            ╠══════════════════════════════════════╣
            ║ Total Orders      : %,6d            ║
            ║ Total Revenue     : $%,.2f       ║
            ║ Avg Order Value   : $%,.2f          ║
            ║ High-Value Orders : %,6d            ║
            ║ Top Region        : %-10s          ║
            ╚══════════════════════════════════════╝
            """,
            totalOrders, totalRevenue, averageOrderValue,
            highValueCount, topRegion
        );
    }
}
