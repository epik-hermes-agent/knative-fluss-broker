package com.knative.fluss.broker.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Knative Fluss Trigger CRD.
 * Defines event filter and subscriber binding.
 */
@Group("eventing.fluss.io")
@Version("v1alpha1")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Trigger extends CustomResource<TriggerSpec, TriggerStatus> implements Namespaced {

    @Override
    protected TriggerStatus initStatus() {
        return new TriggerStatus();
    }
}
