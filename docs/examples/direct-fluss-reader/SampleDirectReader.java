package com.example.reader;

/**
 * Experimental: Direct Fluss reader for trusted internal consumers.
 *
 * This bypasses the Knative Trigger/push model and reads events
 * directly from Fluss Log Tables using the Fluss Java client.
 *
 * Use case: High-throughput internal analytics consumers that
 * don't need the Trigger filter/DLQ semantics.
 *
 * NOTE: This is experimental and outside normal Knative Trigger semantics.
 *
 * Usage:
 *   Add fluss-client dependency and run against a live Fluss cluster.
 */
public class SampleDirectReader {

    public static void main(String[] args) {
        System.out.println("Direct Fluss Reader (Experimental)");
        System.out.println("==================================");
        System.out.println("This reader bypasses Knative Triggers and reads");
        System.out.println("directly from Fluss Log Tables.");
        System.out.println();
        System.out.println("Prerequisites:");
        System.out.println("  - Fluss cluster running at localhost:9123");
        System.out.println("  - Broker 'default' in namespace 'default'");
        System.out.println("  - fluss-client dependency added");
        System.out.println();
        System.out.println("Table path: knative_default.broker_default");
        System.out.println();
        System.out.println("To implement:");
        System.out.println("  1. Add fluss-client to dependencies");
        System.out.println("  2. Create FlussClient with cluster endpoint");
        System.out.println("  3. Call client.scan(tablePath, offset, batchSize)");
        System.out.println("  4. Process rows (Envelope schema)");
    }
}
