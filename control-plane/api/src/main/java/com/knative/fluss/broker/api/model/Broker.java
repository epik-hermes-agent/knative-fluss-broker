package com.knative.fluss.broker.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Knative Fluss Broker CRD.
 * Represents a broker instance backed by a Fluss log table.
 */
@Group("eventing.fluss.io")
@Version("v1alpha1")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Broker extends CustomResource<BrokerSpec, BrokerStatus> implements Namespaced {

    @Override
    protected BrokerStatus initStatus() {
        return new BrokerStatus();
    }
}
