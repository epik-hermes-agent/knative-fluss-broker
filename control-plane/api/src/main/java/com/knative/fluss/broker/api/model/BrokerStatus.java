package com.knative.fluss.broker.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

/**
 * Status for a Broker CRD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrokerStatus {
    private List<Condition> conditions = new ArrayList<>();
    private List<Address> addresses = new ArrayList<>();
    private Long observedGeneration;

    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
    public List<Address> getAddresses() { return addresses; }
    public void setAddresses(List<Address> addresses) { this.addresses = addresses; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }

    public record Address(String name, String url) {}
}
