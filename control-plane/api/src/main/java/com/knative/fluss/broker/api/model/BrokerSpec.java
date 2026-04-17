package com.knative.fluss.broker.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.config.SchemaConfig;

/**
 * Specification for a Broker CRD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrokerSpec {
    private FlussConfig fluss;
    private IngressConfig ingress;
    private SchemaConfig schema;

    public FlussConfig getFluss() { return fluss; }
    public void setFluss(FlussConfig fluss) { this.fluss = fluss; }
    public IngressConfig getIngress() { return ingress; }
    public void setIngress(IngressConfig ingress) { this.ingress = ingress; }
    public SchemaConfig getSchema() { return schema; }
    public void setSchema(SchemaConfig schema) { this.schema = schema; }

    public record IngressConfig(int replicas, ResourceConfig resources) {}
    public record ResourceConfig(String cpu, String memory) {}
}
