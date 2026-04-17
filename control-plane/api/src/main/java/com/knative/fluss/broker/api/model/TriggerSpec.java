package com.knative.fluss.broker.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Specification for a Trigger CRD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriggerSpec {
    private String broker;
    private TriggerFilter filter;
    private SubscriberSpec subscriber;

    public String getBroker() { return broker; }
    public void setBroker(String broker) { this.broker = broker; }
    public TriggerFilter getFilter() { return filter; }
    public void setFilter(TriggerFilter filter) { this.filter = filter; }
    public SubscriberSpec getSubscriber() { return subscriber; }
    public void setSubscriber(SubscriberSpec subscriber) { this.subscriber = subscriber; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TriggerFilter {
        private Map<String, String> attributes;
        public Map<String, String> getAttributes() { return attributes; }
        public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubscriberSpec {
        private String uri;
        private DeliverySpec delivery;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public DeliverySpec getDelivery() { return delivery; }
        public void setDelivery(DeliverySpec delivery) { this.delivery = delivery; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeliverySpec {
        private int retry = 5;
        private String backoffPolicy = "exponential";
        private String backoffDelay = "PT1S";
        private DeadLetterSink deadLetterSink;

        public int getRetry() { return retry; }
        public void setRetry(int retry) { this.retry = retry; }
        public String getBackoffPolicy() { return backoffPolicy; }
        public void setBackoffPolicy(String backoffPolicy) { this.backoffPolicy = backoffPolicy; }
        public String getBackoffDelay() { return backoffDelay; }
        public void setBackoffDelay(String backoffDelay) { this.backoffDelay = backoffDelay; }
        public DeadLetterSink getDeadLetterSink() { return deadLetterSink; }
        public void setDeadLetterSink(DeadLetterSink deadLetterSink) { this.deadLetterSink = deadLetterSink; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeadLetterSink {
        private String uri;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }
}
