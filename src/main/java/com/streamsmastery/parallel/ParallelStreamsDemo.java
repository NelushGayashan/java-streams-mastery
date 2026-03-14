package com.streamsmastery.parallel;

import com.streamsmastery.model.Order;
import com.streamsmastery.model.OrderDataFactory;
import com.streamsmastery.model.OrderStatus;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SECTION 4: PARALLEL STREAMS — WHEN THEY HELP AND WHEN THEY HURT
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * Parallel streams split data into chunks, process each chunk on a
 * separate thread (via the ForkJoinPool.commonPool()), and combine
 * the results. Sounds perfect — but it's NOT always faster.
 *
 * The three questions to ask before using parallel():
 *   1. Is the dataset large enough? (< 10,000 elements: usually not worth it)
 *   2. Is each element's processing CPU-intensive? (trivial ops: overhead > gain)
 *   3. Is the operation thread-safe? (stateful ops + parallel = bugs)
 *
 * This class demonstrates:
 *   1. When parallel streams are genuinely faster (CPU-bound, large data)
 *   2. The classic parallel stream BUG: shared mutable state
 *   3. Thread-safe aggregation patterns
 *   4. Ordering guarantees (and when to use forEachOrdered)
 *   5. Custom ForkJoinPool for controlled parallelism
 */
public class ParallelStreamsDemo {

    private final List<Order> orders;

    public ParallelStreamsDemo() {
        this.orders = OrderDataFactory.createSampleOrders();
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 1: The Classic Bug — Shared Mutable State
    // ─────────────────────────────────────────────────────────

    /**
     * THE most common parallel stream mistake: mutating a shared object
     * from multiple threads without synchronization.
     *
     * ArrayList is NOT thread-safe. When parallel threads call add()
     * simultaneously, the result is unpredictable:
     *   - Elements may be lost (structural corruption)
     *   - The list may have fewer elements than expected
     *   - You may get ArrayIndexOutOfBoundsException
     *   - The bug is INTERMITTENT — it won't always reproduce
     *
     * This is a data race. The fix is never to "just add synchronized" —
     * that defeats the purpose of parallelism. The fix is to use
     * collect() with a proper Collector, which is designed for this.
     */
    public void demonstrateParallelBugAndFix() {
        System.out.println("\n═══ DEMO 1: The Shared Mutable State Bug ═══");

        // ❌ DANGEROUS: ArrayList is not thread-safe.
        // Run this 100 times and you'll eventually see incorrect results.
        List<String> unsafeList = new ArrayList<>();
        orders.parallelStream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .map(Order::id)
                .forEach(unsafeList::add); // Multiple threads calling add() simultaneously!

        System.out.printf("Unsafe result size: %d (expected: ~%d, may vary)%n",
                unsafeList.size(),
                orders.stream().filter(o -> o.status() == OrderStatus.DELIVERED).count());

        // ✅ CORRECT: Use collect() — it was designed for exactly this.
        // The Collector handles thread isolation and safe merging internally.
        List<String> safeList = orders.parallelStream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .map(Order::id)
                .collect(Collectors.toList()); // Thread-safe aggregation

        System.out.printf("Safe result size:   %d (correct)%n", safeList.size());

        // ✅ ALSO CORRECT: Use a thread-safe collection explicitly
        List<String> concurrentList = new CopyOnWriteArrayList<>();
        orders.parallelStream()
                .filter(o -> o.status() == OrderStatus.DELIVERED)
                .map(Order::id)
                .forEach(concurrentList::add); // CopyOnWriteArrayList is thread-safe

        System.out.printf("CopyOnWrite size:   %d (correct)%n", concurrentList.size());
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 2: Encounter Order — forEach vs forEachOrdered
    // ─────────────────────────────────────────────────────────

    /**
     * Parallel streams do NOT guarantee encounter order in forEach().
     *
     * forEach()         → elements processed in ANY order (parallel-friendly)
     * forEachOrdered()  → elements processed in encounter order (sequential-like)
     *
     * For output/side effects where ORDER MATTERS: use forEachOrdered()
     * For pure aggregation where order doesn't matter: use forEach()
     *
     * Note: forEachOrdered() in a parallel stream can be a bottleneck —
     * threads must synchronize to maintain order, reducing parallelism benefit.
     * Prefer collect() over forEachOrdered() when possible.
     */
    public void demonstrateOrderGuarantees() {
        System.out.println("\n═══ DEMO 2: Encounter Order ═══");

        List<String> orderedIds = orders.stream()
                .map(Order::id)
                .limit(5)
                .collect(Collectors.toList());

        System.out.println("Sequential order (reference): " + orderedIds);

        List<String> parallelUnordered = new ArrayList<>();
        orders.parallelStream()
                .map(Order::id)
                .limit(5)
                .forEach(parallelUnordered::add); // order is non-deterministic!

        System.out.println("Parallel forEach (may differ): " + parallelUnordered);

        // forEachOrdered guarantees encounter order even in parallel
        List<String> parallelOrdered = Collections.synchronizedList(new ArrayList<>());
        orders.parallelStream()
                .map(Order::id)
                .limit(5)
                .forEachOrdered(parallelOrdered::add); // preserves order

        System.out.println("Parallel forEachOrdered:       " + parallelOrdered);
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 3: CPU-Bound Workload — Measuring Real Speedup
    // ─────────────────────────────────────────────────────────

    /**
     * Parallel streams deliver measurable speedup when:
     *   1. The dataset is large (thousands of elements)
     *   2. Each element's processing is CPU-intensive
     *   3. Processing is independent (no shared state)
     *
     * We simulate this with a "computationally intensive" operation:
     * calculating a large sum using LongStream (common in analytics).
     *
     * On a machine with 4+ cores, the parallel version should be
     * noticeably faster for N = 10_000_000.
     */
    public void demonstrateActualSpeedup() {
        System.out.println("\n═══ DEMO 3: When Parallel Actually Helps ═══");

        long N = 5_000_000L;

        // Sequential: sum of squares from 1 to N
        long startSeq = System.currentTimeMillis();
        long seqSum = LongStream.rangeClosed(1, N)
                .map(x -> x * x)            // CPU-bound: square each number
                .filter(x -> x % 3 == 0)   // filter divisible by 3
                .sum();
        long seqTime = System.currentTimeMillis() - startSeq;

        // Parallel: same computation, but uses all available CPU cores
        long startPar = System.currentTimeMillis();
        long parSum = LongStream.rangeClosed(1, N)
                .parallel()                 // ← flip to parallel
                .map(x -> x * x)
                .filter(x -> x % 3 == 0)
                .sum();
        long parTime = System.currentTimeMillis() - startPar;

        System.out.printf("Sequential: %d ms | Result: %d%n", seqTime, seqSum);
        System.out.printf("Parallel:   %d ms | Result: %d%n", parTime, parSum);
        System.out.printf("Speedup:    %.2fx (on %d cores)%n",
                (double) seqTime / parTime,
                Runtime.getRuntime().availableProcessors());
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 4: Custom ForkJoinPool — Controlling Parallelism
    // ─────────────────────────────────────────────────────────

    /**
     * By default, parallel streams use ForkJoinPool.commonPool(), which
     * is shared across the entire JVM. This can be a problem:
     *   - Your parallel stream competes with other tasks
     *   - You can't control the thread count
     *   - In a web server, all requests share the same pool
     *
     * The solution: submit your parallel stream task to a CUSTOM
     * ForkJoinPool with a specific parallelism level.
     *
     * This pattern wraps the stream operation in a ForkJoinTask,
     * forcing the parallel stream to use the custom pool's threads.
     */
    public void demonstrateCustomForkJoinPool() throws ExecutionException, InterruptedException {
        System.out.println("\n═══ DEMO 4: Custom ForkJoinPool ═══");

        // Create a custom pool with 2 threads (not the default core count)
        // This is useful for rate-limiting IO-bound parallel work
        ForkJoinPool customPool = new ForkJoinPool(2);

        // Submit stream work to the custom pool
        // The lambda becomes a ForkJoinTask; parallel splits use the custom pool
        ForkJoinTask<Double> task = customPool.submit(() ->
                orders.parallelStream()
                        .mapToDouble(Order::totalRevenue)
                        .sum()
        );

        double totalRevenue = task.get(); // blocks until done
        customPool.shutdown();

        System.out.printf("Total revenue (custom pool, 2 threads): $%,.2f%n", totalRevenue);
        System.out.printf("Common pool parallelism: %d | Custom pool parallelism: 2%n",
                ForkJoinPool.commonPool().getParallelism());
    }

    // ─────────────────────────────────────────────────────────
    // DEMO 5: Parallel Reduction — reduce() and collect()
    // ─────────────────────────────────────────────────────────

    /**
     * Not all reductions are safe to parallelize. The reduce() operation
     * in parallel mode requires:
     *   1. The identity value must be a TRUE identity:
     *      accumulator(identity, x) == x for all x
     *   2. The accumulator must be associative:
     *      acc(acc(a,b),c) == acc(a, acc(b,c))
     *
     * STRING CONCATENATION with reduce() in parallel is WRONG:
     *   The identity "" seems correct, but the combiner creates
     *   extra "" + "" intermediate strings and the order of
     *   concatenation affects the result.
     *
     * USE Collectors.joining() instead — it's designed for this.
     */
    public void demonstrateSafeParallelReduction() {
        System.out.println("\n═══ DEMO 5: Safe Parallel Reduction ═══");

        // ✅ SAFE: sum is truly associative, identity is 0
        double totalRevenue = orders.parallelStream()
                .mapToDouble(Order::totalRevenue)
                .reduce(0.0, Double::sum); // 0.0 is true identity for addition

        System.out.printf("Total revenue (parallel reduce): $%,.2f%n", totalRevenue);

        // ✅ SAFE: Collectors.joining() is parallel-safe
        String orderIdsCsv = orders.parallelStream()
                .map(Order::id)
                .collect(Collectors.joining(", ")); // thread-safe joining

        System.out.printf("All order IDs: %s%n", orderIdsCsv);

        // ✅ SAFE: counting() is parallel-safe
        Map<OrderStatus, Long> countByStatus = orders.parallelStream()
                .collect(Collectors.groupingByConcurrent( // concurrent version of groupingBy
                        Order::status,
                        Collectors.counting()
                ));

        System.out.println("Orders by status (parallel grouping):");
        countByStatus.forEach((status, count) ->
                System.out.printf("  %-12s → %d%n", status, count));
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        var demo = new ParallelStreamsDemo();
        demo.demonstrateParallelBugAndFix();
        demo.demonstrateOrderGuarantees();
        demo.demonstrateActualSpeedup();
        demo.demonstrateCustomForkJoinPool();
        demo.demonstrateSafeParallelReduction();
    }
}
