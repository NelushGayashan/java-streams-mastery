# Java Streams Mastery 🚀

> Companion repository for the Medium article:
> **"Java Streams: From `forEach` to Production-Grade Pipelines"**

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## What's in this repo?

A production-inspired e-commerce order analytics system built entirely around Java Streams.
Every class maps to a section in the article and is **heavily commented** to explain the theory
**and** how it's implemented in the code.

---

## Project Structure

```
src/main/java/com/streamsmastery/
│
├── model/
│   ├── Order.java               # Core domain record
│   ├── OrderItem.java           # Line item record
│   ├── OrderStatus.java         # PENDING / SHIPPED / DELIVERED / CANCELLED
│   ├── OrderSummary.java        # Result DTO for reports
│   └── OrderDataFactory.java    # 20-order test dataset
│
├── pipeline/
│   └── StreamPipelineFundamentals.java
│       ├── Demo 1: Lazy evaluation (proven with a counter)
│       ├── Demo 2: Operation order and performance impact
│       ├── Demo 3: flatMap for nested collections
│       └── Demo 4: Method references vs lambdas
│
├── collectors/
│   ├── AdvancedCollectorsDemo.java
│   │   ├── Demo 1: groupingBy() — the SQL GROUP BY of Java
│   │   ├── Demo 2: Multi-level groupingBy() (nested maps)
│   │   ├── Demo 3: partitioningBy() — boolean splits
│   │   ├── Demo 4: toMap() with merge function
│   │   └── Demo 5: Collectors.teeing() (Java 12+)
│   │
│   └── OrderSummaryCollector.java
│       └── Full custom Collector<Order, Accumulator, OrderSummary>
│           with supplier / accumulator / combiner / finisher / characteristics
│
├── parallel/
│   └── ParallelStreamsDemo.java
│       ├── Demo 1: The shared mutable state bug (and fix)
│       ├── Demo 2: forEach vs forEachOrdered
│       ├── Demo 3: Measuring actual CPU-bound speedup
│       ├── Demo 4: Custom ForkJoinPool
│       └── Demo 5: Safe parallel reduction patterns
│
└── realworld/
    └── OrderReportEngine.java
        ├── Report 1: Executive dashboard (custom collector)
        ├── Report 2: Regional performance matrix
        ├── Report 3: Category revenue breakdown (flatMap + groupingBy)
        ├── Report 4: Customer Lifetime Value (toMap + merge)
        ├── Report 5: Fulfillment rate by region (teeing as downstream)
        ├── Report 6: Recent high-value order alerts (parallel)
        └── Report 7: Pending inventory demand (flatMap + toMap)
```

---

## Running the Project

### Prerequisites
- Java 17+
- Maven 3.8+

### Run all demos

```bash
# Run the complete pipeline fundamentals demo
mvn exec:java -Dexec.mainClass="com.streamsmastery.pipeline.StreamPipelineFundamentals"

# Run the advanced collectors demo
mvn exec:java -Dexec.mainClass="com.streamsmastery.collectors.AdvancedCollectorsDemo"

# Run the custom collector demo
mvn exec:java -Dexec.mainClass="com.streamsmastery.collectors.OrderSummaryCollector"

# Run the parallel streams demo
mvn exec:java -Dexec.mainClass="com.streamsmastery.parallel.ParallelStreamsDemo"

# Run the full report engine (recommended starting point)
mvn exec:java -Dexec.mainClass="com.streamsmastery.realworld.OrderReportEngine"
```

### Run the tests

```bash
mvn test
```

---

## Key Concepts by Class

| Concept | Where to look |
|---|---|
| Lazy evaluation (proven) | `StreamPipelineFundamentals.demonstrateLazyEvaluation()` |
| Operation order performance | `StreamPipelineFundamentals.demonstrateOperationOrder()` |
| flatMap mental model | `StreamPipelineFundamentals.demonstrateFlatMap()` |
| groupingBy + downstream | `AdvancedCollectorsDemo.demonstrateGroupingBy()` |
| Multi-level grouping | `AdvancedCollectorsDemo.demonstrateMultiLevelGrouping()` |
| teeing() | `AdvancedCollectorsDemo.demonstrateTeeing()` |
| Custom Collector (all 5 components) | `OrderSummaryCollector.java` |
| Parallel stream bug | `ParallelStreamsDemo.demonstrateParallelBugAndFix()` |
| Custom ForkJoinPool | `ParallelStreamsDemo.demonstrateCustomForkJoinPool()` |
| Production pipeline composition | `OrderReportEngine.java` |

---

## Article

Read the full article on Medium:
[Java Streams: From forEach to Production-Grade Pipelines](https://medium.com/@yourhandle/java-streams-production-guide)

---

## License

MIT — use freely, attribution appreciated.
