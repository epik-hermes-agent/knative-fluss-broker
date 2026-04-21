package com.knative.fluss.broker.tui;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;

public class InsertTestData {
    public static void main(String[] args) throws Exception {
        var config = new FlussConfig("fluss://127.0.0.1:9123", 50, 100, 10000, 3, 100);
        var connMgr = new FlussConnectionManager(config);
        var tableMgr = new FlussTableManager(connMgr);
        var writer = new FlussEventWriter(connMgr, config);

        tableMgr.ensureDatabase("knative_default");
        var bp = FlussTablePath.brokerTable("knative_default", "default");
        var dp = FlussTablePath.dlqTable("knative_default", "default", "default");
        tableMgr.ensureBrokerTable(bp);
        tableMgr.ensureDlqTable(dp);

        String[][] evts = {
            {"evt-001","/orders/api","com.example.order.created","{\"orderId\":\"ORD-001\",\"amount\":99.99,\"customer\":\"alice\"}","1","1"},
            {"evt-002","/orders/api","com.example.order.created","{\"orderId\":\"ORD-002\",\"amount\":149.50,\"customer\":\"bob\"}","1","1"},
            {"evt-003","/payments/webhook","com.example.payment.processed","{\"paymentId\":\"PAY-001\",\"status\":\"success\",\"amount\":99.99}","2","1"},
            {"evt-004","/inventory/update","com.example.inventory.low","{\"sku\":\"WIDGET-42\",\"warehouse\":\"US-WEST\",\"qty\":3}","3","1"},
            {"evt-005","/orders/api","com.example.order.shipped","{\"orderId\":\"ORD-001\",\"tracking\":\"1Z999AA10123456784\"}","1","2"},
        };
        for (var e : evts) {
            writer.write(bp, Envelope.builder().eventId(e[0]).eventSource(e[1]).eventType(e[2])
                .contentType("application/json").data(e[3].getBytes())
                .schemaId(Integer.parseInt(e[4])).schemaVersion(Integer.parseInt(e[5])).build()).get();
            System.out.println("Written: " + e[0]);
        }

        writer.writeToDlq(dp, Envelope.builder().eventId("evt-fail-001").eventSource("/orders/api")
            .eventType("com.example.order.cancelled").contentType("application/json")
            .data("{\"orderId\":\"ORD-999\",\"reason\":\"out_of_stock\"}".getBytes())
            .schemaId(1).schemaVersion(1).build(),
            "retries_exhausted", 3, "Connection refused to http://sink:8080").get();
        System.out.println("Written DLQ: evt-fail-001");

        writer.writeToDlq(dp, Envelope.builder().eventId("evt-fail-002").eventSource("/payments/webhook")
            .eventType("com.example.payment.failed").contentType("application/json")
            .data("{\"paymentId\":\"PAY-FAIL\",\"status\":\"timeout\"}".getBytes())
            .schemaId(2).schemaVersion(1).build(),
            "non_retryable_client_error", 1, "HTTP 400 Bad Request").get();
        System.out.println("Written DLQ: evt-fail-002");

        System.out.println("\nDone! 5 events + 2 DLQ entries.");
        writer.close(); connMgr.close();
    }
}
