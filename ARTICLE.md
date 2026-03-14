# Java Streams: From `forEach` to Production-Grade Pipelines

### filter().map().collect() is 20% of what streams can do. Here's the other 80%.

---

> **Companion repo:** [github.com/NelushGayashan/java-streams-mastery](https://github.com/NelushGayashan/java-streams-mastery)
> All code in this article is in that repo, fully runnable, with extensive inline comments.

---

I've reviewed a lot of Java code over the years. And the pattern I see most consistently is this: developers discover streams, fall in love with `filter().map().collect()`, and then stop exploring. The Stream API becomes a fancy for-loop replacement instead of the declarative data processing engine it actually is.

This article is a correction to that. We're going to start at the pipeline internals — things most tutorials get wrong — and build all the way up to a production reporting system that uses `groupingBy()` with custom downstream collectors, `Collectors.teeing()`, a custom `Collector<T,A,R>` built from scratch, and parallel streams with the pitfalls clearly labeled.

Everything is grounded in a realistic project: an **order analytics system** for an e-commerce platform. Real domain model. Real business questions. Real code you'd write at work.

Let's go.

---

## The Project: E-Commerce Order Analytics

Before code, context. Our domain model:

```
Order
  ├── id, customerId, region, status, orderDate
  └── List<OrderItem>
        └── productId, category, price, quantity
```

**`Order`** is a Java `record` — immutable by default, which is exactly what stream pipelines prefer. Mutable objects inside stream operations, especially parallel ones, are a common source of subtle bugs.

```java
// model/Order.java
public record Order(
        String id,
        String customerId,
        String region,
        OrderStatus status,
        List<OrderItem> items,
        LocalDate orderDate
) {
    // Business logic lives on the model, not in pipeline lambdas
    public double totalRevenue() {
        return items.stream()
                .mapToDouble(item -> item.price() * item.quantity())
                .sum();
    }

    public boolean isHighValue() {
        return totalRevenue() > 500.0;
    }
}
```

Two things worth noting here. First, `totalRevenue()` uses a stream internally — streams compose naturally. Second, `isHighValue()` is a method on the model, not a lambda in a pipeline. This is intentional. Methods with names are:

- Reusable across multiple pipelines
- Testable in isolation
- Readable as method references: `filter(Order::isHighValue)`

Keep your business logic on your domain objects. Your pipelines become self-documenting.

---

## Part 1: The Anatomy of a Stream Pipeline (And What Everyone Gets Wrong)

Every stream pipeline has three parts:

```
SOURCE → INTERMEDIATE OPERATIONS → TERMINAL OPERATION
```

Simple enough. But the single most important property of streams is one that developers frequently forget, or know theoretically but don't internalize practically:

**Intermediate operations are lazy. Nothing executes until a terminal operation is called.**

This is not a footnote. It is the entire design philosophy, and it enables significant optimizations. Let me prove it empirically rather than just asserting it.

### Proving Lazy Evaluation With a Counter

```java
// pipeline/StreamPipelineFundamentals.java

int[] filterCallCount = {0};

// Build the pipeline — NOTHING executes here yet.
// This is just a description of what to do, not the doing of it.
var pipeline = orders.stream()
        .filter(order -> {
            filterCallCount[0]++;  // This line does NOT run yet
            return order.status() == OrderStatus.DELIVERED;
        })
        .map(Order::id);

System.out.printf("After building pipeline: filter called %d times%n",
        filterCallCount[0]);
// Output: "After building pipeline: filter called 0 times"

// NOW the terminal operation triggers execution.
// But findFirst() short-circuits — it stops at the first match.
Optional<String> firstDelivered = pipeline.findFirst();

System.out.printf("After findFirst(): filter called %d times%n",
        filterCallCount[0]);
// Output: "After findFirst(): filter called 1 or 2 times"
// NOT 20 times — it stopped as soon as it found one DELIVERED order.
```

The implications are real:

1. **Building a stream pipeline has no cost.** You can build complex pipelines and pass them around as values.
2. **Short-circuit operations** (`findFirst`, `anyMatch`, `limit`) may process far fewer elements than the source contains.
3. **Unused pipelines don't execute.** If you build a stream but never call a terminal operation, zero work is done.

### Operation Order Is a Performance Decision

Consider these two pipelines that produce identical results:

```java
// ❌ INEFFICIENT: map() before filter()
// The expensive transformation runs for ALL 20 orders.
// We then throw most results away.
List<String> result1 = orders.stream()
        .map(order -> "[%s] %s → $%.2f".formatted(
                order.region(), order.id(), order.totalRevenue()))
        .filter(s -> s.contains("NORTH"))
        .collect(Collectors.toList());

// ✅ EFFICIENT: filter() before map()
// Now the expensive transformation only runs for
// the 5 NORTH orders that survived the filter.
List<String> result2 = orders.stream()
        .filter(order -> order.region().equals("NORTH"))
        .map(order -> "[%s] %s → $%.2f".formatted(
                order.region(), order.id(), order.totalRevenue()))
        .collect(Collectors.toList());
```

The rule: **place size-reducing operations (`filter`, `skip`, `limit`, `distinct`) before operations that process every element (`map`, `peek`, `sorted`).** This isn't premature optimization — it's writing correct pipelines.

### flatMap: The Most Misunderstood Operation

Here's the mental model, stated precisely:

```
map()     : one element in → one element out     (1:1)
flatMap() : one element in → zero or more out    (1:N)
```

`flatMap` takes a function that returns a `Stream` for each element, then *flattens* all those streams into one.

Our domain problem: each `Order` contains a `List<OrderItem>`. If we want to analyze individual items across all orders, we need `flatMap` to unwrap the nesting.

```java
// WITHOUT flatMap — we get Stream<List<OrderItem>> — nested, hard to work with
// Stream<List<OrderItem>> wrong = orders.stream().map(Order::items);

// WITH flatMap — we get Stream<OrderItem> — flat, workable
Map<String, Double> topProductsByRevenue = orders.stream()
        .filter(o -> o.status() == OrderStatus.DELIVERED)
        .flatMap(order -> order.items().stream())  // Order → [Item, Item, Item...]
        .collect(Collectors.groupingBy(
                item -> item.productId(),
                Collectors.summingDouble(item -> item.price() * item.quantity())
        ))
        .entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .limit(5)
        .collect(Collectors.toList());
```

`flatMap(Collection::stream)` is the canonical idiom for flattening nested collections. Memorize it.

---

## Part 2: The Collectors API — The Power Engine You're Not Using

`Collectors.toList()` is to the Collectors API what `System.out.println` is to I/O — the first thing you learn, not the last.

Let's cover what's actually in there.

### `groupingBy()` — The SQL GROUP BY of Java

```java
// Business question: "How many orders do we have per region?"
// SQL: SELECT region, COUNT(*) FROM orders GROUP BY region;

// Basic groupingBy — returns Map<String, List<Order>>
Map<String, List<Order>> byRegion = orders.stream()
        .collect(Collectors.groupingBy(Order::region));

// With a downstream collector — returns Map<String, Long>
Map<String, Long> countByRegion = orders.stream()
        .collect(Collectors.groupingBy(
                Order::region,
                Collectors.counting()
        ));

// Revenue per region — Map<String, Double>
// SQL: SELECT region, SUM(revenue) FROM orders GROUP BY region
Map<String, Double> revenueByRegion = orders.stream()
        .collect(Collectors.groupingBy(
                Order::region,
                Collectors.summingDouble(Order::totalRevenue)
        ));
```

`groupingBy` has three overloads. The one most developers miss is the three-argument version:

```java
// Three-argument groupingBy: specify the Map implementation
// Use TreeMap to get a sorted result map
Map<String, OrderSummary> sortedReport = orders.stream()
        .collect(Collectors.groupingBy(
                Order::region,
                TreeMap::new,              // ← map factory: controls Map type
                new OrderSummaryCollector() // ← downstream collector (we'll build this)
        ));
```

### Multi-Level `groupingBy()` — Nested Aggregation

The downstream collector in `groupingBy` can itself be another `groupingBy`. This produces `Map<K1, Map<K2, V>>` — a two-dimensional aggregation in one pipeline.

```java
// Business question: "Revenue per category, broken down by region"
// SQL: SELECT region, category, SUM(revenue) GROUP BY region, category

record ItemWithRegion(String region, String category, double revenue) {}

Map<String, Map<String, Double>> revenueByCategoryPerRegion = orders.stream()
        .flatMap(order -> order.items().stream()
                .map(item -> new ItemWithRegion(
                        order.region(),
                        item.category(),
                        item.subtotal()
                ))
        )
        .collect(Collectors.groupingBy(
                ItemWithRegion::region,            // First level: by region
                Collectors.groupingBy(
                        ItemWithRegion::category,  // Second level: by category
                        Collectors.summingDouble(ItemWithRegion::revenue)
                )
        ));
```

Local records (introduced in Java 16) are the cleanest way to carry context across a `flatMap`. Notice how `ItemWithRegion` solves the "I need data from both the parent Order and the child OrderItem" problem — without creating a separate top-level class.

### `partitioningBy()` — Binary Splits

When you need exactly two groups based on a boolean condition, `partitioningBy` is cleaner than `groupingBy` with a boolean classifier:

```java
// Separate high-value orders from standard orders
Map<Boolean, List<Order>> partitioned = orders.stream()
        .collect(Collectors.partitioningBy(Order::isHighValue));

List<Order> highValueOrders = partitioned.get(true);   // revenue > $500
List<Order> standardOrders  = partitioned.get(false);  // revenue ≤ $500

// With downstream collector — count per partition
Map<Boolean, Long> countByTier = orders.stream()
        .collect(Collectors.partitioningBy(
                Order::isHighValue,
                Collectors.counting()
        ));
```

The key advantage over `groupingBy`: you always get both partitions in the result map, even if one is empty. With `groupingBy(o -> o.isHighValue() ? "HIGH" : "STANDARD")`, an empty partition simply won't appear in the map.

### `toMap()` — And Why You Need the Three-Argument Version

The two-argument `toMap()` throws `IllegalStateException` on duplicate keys. In production data, you will have duplicate keys. Always use the three-argument version:

```java
// ❌ DANGEROUS — throws if any customer has more than one order
Map<String, Order> broken = orders.stream()
        .collect(Collectors.toMap(Order::customerId, o -> o)); // 💥

// ✅ SAFE — merge function picks the more recent order on collision
Map<String, Order> latestByCustomer = orders.stream()
        .collect(Collectors.toMap(
                Order::customerId,           // key mapper
                order -> order,              // value mapper
                (existing, replacement) ->   // merge function: called on key collision
                        existing.orderDate().isAfter(replacement.orderDate())
                                ? existing : replacement
        ));
```

The merge function `(existing, replacement) -> ...` is called whenever two elements produce the same key. You decide what to keep. Common merge functions:

```java
// Keep the higher value
(a, b) -> a > b ? a : b

// Sum numeric values
Double::sum

// Concatenate into a list
(list1, list2) -> { list1.addAll(list2); return list1; }
```

### `Collectors.teeing()` — Two Collectors, One Pass (Java 12+)

This is the one that still surprises most Java developers. `teeing()` sends every element to two downstream collectors simultaneously, then merges their results.

```
teeing(downstream1, downstream2, merger)
       ↓               ↓
  [collector1]    [collector2]
       ↓               ↓
       └──── merger ────┘
                ↓
           final result
```

The name comes from a plumbing "tee" fitting — one input, two outputs.

**Before Java 12**, calculating both the min and max of a stream in a single pass required either storing the list or two separate stream traversals. `teeing()` solves this:

```java
// Single pass computes both min and max revenue
record RevenueRange(double min, double max) {}

RevenueRange range = orders.stream()
        .collect(Collectors.teeing(
                Collectors.minBy(Comparator.comparingDouble(Order::totalRevenue)),
                Collectors.maxBy(Comparator.comparingDouble(Order::totalRevenue)),
                (minOpt, maxOpt) -> new RevenueRange(
                        minOpt.map(Order::totalRevenue).orElse(0.0),
                        maxOpt.map(Order::totalRevenue).orElse(0.0)
                )
        ));
// Output: RevenueRange[min=89.96, max=1599.97]
```

Where `teeing()` truly shines is as a **downstream collector inside `groupingBy()`**. In our report engine, we compute the fulfillment rate per region:

```java
// Business question: "What % of orders are DELIVERED per region?"
// Requires: counting DELIVERED orders AND counting all active orders
// Per region, in one pass.

record FulfillmentData(long delivered, long total) {
    double rate() { return total == 0 ? 0.0 : (double) delivered / total * 100; }
}

Map<String, Double> fulfillmentRateByRegion = orders.stream()
        .filter(o -> o.status() != OrderStatus.CANCELLED)
        .collect(Collectors.groupingBy(
                Order::region,
                Collectors.teeing(
                        // Collector 1: count only DELIVERED
                        Collectors.filtering(
                                o -> o.status() == OrderStatus.DELIVERED,
                                Collectors.counting()
                        ),
                        // Collector 2: count all non-cancelled
                        Collectors.counting(),
                        // Merge into our FulfillmentData record
                        FulfillmentData::new
                )
        ))
        .entrySet().stream()
        .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().rate()
        ));
```

This is a genuinely elegant pipeline. Per region, `teeing()` computes two counts simultaneously — one filtered, one total — and the merge function turns them into a rate. No mutable accumulators, no separate passes, no intermediate storage.

---

## Part 3: Building a Custom Collector From Scratch

Sometimes the built-in collectors aren't enough. The `Collector<T, A, R>` interface lets you define exactly how elements are accumulated.

The three type parameters:

- **`T`** — the type of element (our `Order`)
- **`A`** — the mutable accumulator type (our intermediate container)
- **`R`** — the result type (our `OrderSummary`)

A collector is defined by **five components**:

| Component | Type | Purpose |
|---|---|---|
| `supplier()` | `Supplier<A>` | Creates a new, empty accumulator |
| `accumulator()` | `BiConsumer<A, T>` | Folds one element into the accumulator |
| `combiner()` | `BinaryOperator<A>` | Merges two accumulators (parallel mode) |
| `finisher()` | `Function<A, R>` | Transforms accumulator into final result |
| `characteristics()` | `Set<Characteristics>` | Hints to runtime about this collector's behavior |

### Our Accumulator

```java
// collectors/OrderSummaryCollector.java

static class Accumulator {
    long totalOrders = 0;
    double totalRevenue = 0.0;
    long highValueCount = 0;
    // Track revenue per region so finisher can find the top one
    Map<String, Double> revenueByRegion = new HashMap<>();

    // Called by combiner() during parallel execution
    void merge(Accumulator other) {
        this.totalOrders    += other.totalOrders;
        this.totalRevenue   += other.totalRevenue;
        this.highValueCount += other.highValueCount;
        other.revenueByRegion.forEach((region, revenue) ->
                this.revenueByRegion.merge(region, revenue, Double::sum));
    }
}
```

The accumulator is mutable (we update it element by element), but self-contained (no external dependencies). The `merge()` method is essential for parallel correctness.

### The Five Components

```java
@Override
public Supplier<Accumulator> supplier() {
    // Called once at start (sequential) or once per thread (parallel)
    return Accumulator::new;
}

@Override
public BiConsumer<Accumulator, Order> accumulator() {
    // Called once per element — keep this lean, no unnecessary allocations
    return (acc, order) -> {
        double revenue = order.totalRevenue();

        acc.totalOrders++;
        acc.totalRevenue += revenue;

        if (order.isHighValue()) {
            acc.highValueCount++;
        }

        // Map.merge() is cleaner than getOrDefault + put
        acc.revenueByRegion.merge(order.region(), revenue, Double::sum);
    };
}

@Override
public BinaryOperator<Accumulator> combiner() {
    // Only called during parallel execution — merges partial accumulators
    return (left, right) -> {
        left.merge(right);
        return left;
    };
}

@Override
public Function<Accumulator, OrderSummary> finisher() {
    // Called once at the end — does any computation that requires full data
    return acc -> {
        double average = acc.totalOrders > 0
                ? acc.totalRevenue / acc.totalOrders : 0.0;

        // Finding the top region requires seeing all region data — hence finisher
        String topRegion = acc.revenueByRegion.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("N/A");

        return new OrderSummary(
                acc.totalOrders, acc.totalRevenue,
                average, acc.highValueCount, topRegion
        );
    };
}

@Override
public Set<Characteristics> characteristics() {
    return Set.of(Characteristics.UNORDERED);
    // NOT IDENTITY_FINISH — our finisher does real work
    // NOT CONCURRENT — HashMap is not thread-safe
}
```

### Why the Characteristics Matter

`Characteristics.IDENTITY_FINISH` tells the runtime to skip calling `finisher()` entirely — only valid when `A == R`. Since our accumulator type is `Accumulator` and our result type is `OrderSummary`, this does not apply.

`Characteristics.CONCURRENT` tells the runtime that multiple threads can call `accumulator()` on the *same* accumulator instance concurrently. We can't declare this because `HashMap` is not thread-safe. If we needed concurrent accumulation, we'd use `ConcurrentHashMap` and declare this characteristic.

`Characteristics.UNORDERED` is safe to declare — our sum/count/average operations are all commutative. The result doesn't depend on encounter order.

### The Payoff: Composability

The real value of a custom collector is that it **composes cleanly** with the rest of the API:

```java
// Use as a standalone collector
OrderSummary allOrders = orders.stream()
        .collect(new OrderSummaryCollector());

// Use as a downstream collector inside groupingBy — clean composition
Map<String, OrderSummary> byRegion = orders.stream()
        .collect(Collectors.groupingBy(
                Order::region,
                new OrderSummaryCollector()  // ← custom collector as downstream
        ));
```

The second form — a custom collector as a downstream inside `groupingBy()` — is where this pattern pays off in production code. You've computed a complex summary for every region, in one pass, with no hand-written loops anywhere.

---

## Part 4: Parallel Streams — When They Help and When They Hurt

Parallel streams split data across `ForkJoinPool.commonPool()` threads. Sounds like an easy win. It's often not.

The three questions to ask before using `.parallel()`:

1. **Is the dataset large enough?** For fewer than ~10,000 elements, thread management overhead typically exceeds any parallel gain.
2. **Is each element's processing CPU-intensive?** Trivial operations (string concatenation, field extraction) — sequential is faster.
3. **Is the operation thread-safe?** This is where parallel streams bite developers.

### The Bug Most Teams Hit At Least Once

```java
// ❌ DANGEROUS: ArrayList is not thread-safe.
// Multiple threads calling add() simultaneously causes data races.
// This bug is intermittent — it won't always reproduce in tests.
List<String> unsafeList = new ArrayList<>();
orders.parallelStream()
        .filter(o -> o.status() == OrderStatus.DELIVERED)
        .map(Order::id)
        .forEach(unsafeList::add); // Multiple threads → corrupted list

// ✅ CORRECT: collect() was designed for exactly this.
// Internally, each thread has its own partial result list;
// they are merged safely after all threads complete.
List<String> safeList = orders.parallelStream()
        .filter(o -> o.status() == OrderStatus.DELIVERED)
        .map(Order::id)
        .collect(Collectors.toList()); // thread-safe, always correct
```

The fix is not to "just add `synchronized`" — that serializes the critical section and defeats the purpose of parallelism. The fix is to use `collect()` with a proper collector, which provides thread-isolated accumulators.

### When Parallel Genuinely Helps

Parallel streams deliver real speedup for CPU-bound work on large datasets with independent elements:

```java
// CPU-bound: summing squares over 5 million numbers
long N = 5_000_000L;

// Sequential
long seqSum = LongStream.rangeClosed(1, N)
        .map(x -> x * x)
        .filter(x -> x % 3 == 0)
        .sum();
// Time: ~120ms

// Parallel — uses all available CPU cores
long parSum = LongStream.rangeClosed(1, N)
        .parallel()
        .map(x -> x * x)
        .filter(x -> x % 3 == 0)
        .sum();
// Time: ~35ms on 4 cores — ~3.4x speedup
```

The result is identical; the runtime is not.

### `forEach` vs `forEachOrdered`

Parallel streams do not guarantee encounter order in `forEach()`:

```java
// Parallel forEach — elements processed in ANY order
orders.parallelStream()
        .map(Order::id)
        .forEach(System.out::println); // non-deterministic order

// forEachOrdered — preserves source order even in parallel
// (but this forces synchronization, reducing parallelism benefit)
orders.parallelStream()
        .map(Order::id)
        .forEachOrdered(System.out::println); // always original order
```

Prefer `collect()` over `forEachOrdered()`. If you need ordered output, collect to a list and then iterate it sequentially.

### Custom ForkJoinPool for Controlled Parallelism

In server applications, `ForkJoinPool.commonPool()` is shared across all threads — your parallel stream competes with everyone else's work. Use a custom pool when you need isolation:

```java
ForkJoinPool customPool = new ForkJoinPool(2); // exactly 2 threads

ForkJoinTask<Double> task = customPool.submit(() ->
        orders.parallelStream()
                .mapToDouble(Order::totalRevenue)
                .sum()
);

double totalRevenue = task.get(); // blocks until done
customPool.shutdown();
```

This is particularly important in batch processing jobs, where you want to limit resource consumption to avoid impacting online traffic.

---

## Part 5: The Report Engine — Putting It All Together

Here's how all of these techniques combine in a real service class. Each method in `OrderReportEngine` answers a concrete business question and is annotated with the patterns it uses.

### Report 1: Executive Dashboard (Custom Collector)

```java
// realworld/OrderReportEngine.java

public OrderSummary generateExecutiveSummary() {
    return orders.stream()
            .filter(o -> o.status() != OrderStatus.CANCELLED)
            .collect(new OrderSummaryCollector());
    // Single pass computes: count, total revenue, average, high-value count, top region
}
```

### Report 2: Regional Performance Matrix (groupingBy + Custom Downstream)

```java
public Map<String, OrderSummary> generateRegionalReport() {
    return orders.stream()
            .filter(o -> o.status() != OrderStatus.CANCELLED)
            .collect(Collectors.groupingBy(
                    Order::region,
                    TreeMap::new,                // sorted output
                    new OrderSummaryCollector()  // full summary per region
            ));
}
```

### Report 3: Category Revenue (flatMap + groupingBy)

```java
public Map<String, Double> generateCategoryRevenueReport() {
    return orders.stream()
            .filter(o -> o.status() == OrderStatus.DELIVERED)
            .flatMap(order -> order.items().stream()) // unwrap items from orders
            .collect(Collectors.groupingBy(
                    OrderItem::category,
                    Collectors.summingDouble(OrderItem::subtotal)
            ))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (a, b) -> a,
                    LinkedHashMap::new // preserves sorted insertion order
            ));
}
```

### Report 4: Customer Lifetime Value (toMap with merge)

```java
public LinkedHashMap<String, Double> generateCustomerLifetimeValue() {
    return orders.stream()
            .filter(o -> o.status() == OrderStatus.DELIVERED)
            .collect(Collectors.toMap(
                    Order::customerId,
                    Order::totalRevenue,
                    Double::sum       // merge: sum revenues for repeat customers
            ))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (a, b) -> a,
                    LinkedHashMap::new
            ));
}
```

### Report 5: Fulfillment Rate (teeing as downstream inside groupingBy)

```java
// As shown in the teeing section — this computes delivered/total per region
// in one pass, using teeing() as a downstream collector inside groupingBy()
public Map<String, Double> generateFulfillmentRateByRegion() { /* ... */ }
```

### Report 6: High-Value Order Alerts (Parallel streams for large datasets)

```java
public List<Order> findRecentHighValueOrders(int daysBack) {
    LocalDate cutoff = LocalDate.now().minusDays(daysBack);

    return orders.parallelStream()        // parallel: suitable for large datasets
            .filter(o -> o.status() != OrderStatus.CANCELLED)
            .filter(Order::isHighValue)
            .filter(o -> o.orderDate().isAfter(cutoff))
            .sequential()                 // back to sequential before sort
            .sorted(Comparator.comparingDouble(Order::totalRevenue).reversed())
            .collect(Collectors.toList());
}
```

Note `.sequential()` before `.sorted()`. Sorting requires seeing all elements before producing any output — it can't be parallelized meaningfully. Switching back to sequential at this stage avoids the overhead of parallel sorting with its associated merge costs.

---

## The Testing Strategy

Streams are ordinary code. Test the output, not the implementation.

```java
@Test
@DisplayName("Regional revenue sums match overall total")
void regionalRevenueSumsMatchTotal() {
    Map<String, OrderSummary> regional = engine.generateRegionalReport();
    OrderSummary overall = engine.generateExecutiveSummary();

    // Sum of all regional revenues must equal the overall total
    double regionSum = regional.values().stream()
            .mapToDouble(OrderSummary::totalRevenue)
            .sum();

    assertEquals(overall.totalRevenue(), regionSum, 0.01,
            "Sum of regional revenues should equal overall total");
}
```

This test validates a mathematical invariant: if our `groupingBy` + custom collector is correct, the parts should sum to the whole. This kind of invariant-based testing is more robust than testing specific hardcoded values that change with the dataset.

---

## Common Mistakes, Summarized

**1. Using `forEach` to populate external collections from parallel streams.**
Use `collect()`. Always. `forEach` on a parallel stream with a shared mutable collection is a data race.

**2. Reusing streams.**
A stream can be consumed exactly once. After a terminal operation, the stream is exhausted. Calling another terminal operation throws `IllegalStateException`. Collect to a `List` if you need to traverse multiple times.

**3. Two-argument `toMap()` in production code.**
Production data has duplicates. The two-argument version throws on collision. Default to the three-argument version.

**4. Not knowing the characteristics of your Collector.**
If your collector's result doesn't depend on order, declare `UNORDERED`. The runtime can use this to optimize parallel execution. If your accumulator is thread-safe, declare `CONCURRENT` and the runtime can avoid creating per-thread copies.

**5. Using parallel streams on small datasets or I/O-bound work.**
Thread pool management overhead is significant. For I/O-bound operations (network calls, database queries), parallel streams are actively harmful — threads block waiting for I/O, starving the common pool. Use `CompletableFuture` for I/O concurrency.

**6. Ignoring `Collectors.teeing()`.**
If you find yourself making two passes over the same data to compute two different aggregates, check whether `teeing()` solves it in one.

---

## Where to Go Next

The repo at [github.com/yourhandle/java-streams-mastery](https://github.com/yourhandle/java-streams-mastery) has all of this code running, with comments that go deeper than I could in the article. Every class has a `main()` method you can run directly.

The topics I deliberately left for a follow-up:

- **`Stream.of()`, `Stream.generate()`, `Stream.iterate()`** — creating streams from scratch, including infinite streams with `limit()`
- **`IntStream`, `LongStream`, `DoubleStream`** — primitive specializations and why they matter for performance
- **`Spliterator`** — the low-level API for parallel stream data splitting; useful when you're implementing your own data source
- **`Collectors.summarizingInt/Long/Double`** — getting count, sum, min, max, and average in one shot
- **`Stream.concat()`** — merging two streams, and when to prefer `flatMap` instead

Streams are one of the best-designed APIs in the Java standard library. The more you internalize the mental model — lazy evaluation, operation ordering, the five collector components — the more naturally complex data transformations become.

---

*All code available at [github.com/NelushGayashan/java-streams-mastery](https://github.com/NelushGayashan/java-streams-mastery). Java 17+ required.*

*If this was useful, a clap or comment helps others find it.*
