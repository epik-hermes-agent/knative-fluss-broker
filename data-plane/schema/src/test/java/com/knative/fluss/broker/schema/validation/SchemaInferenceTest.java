package com.knative.fluss.broker.schema.validation;

import com.knative.fluss.broker.common.config.SchemaConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SchemaInferenceTest {

    private final SchemaConfig config = SchemaConfig.defaults();

    @Test
    void shouldInferJsonObjectSchema() {
        var data = "{\"name\":\"Alice\",\"age\":30}".getBytes();
        var schema = SchemaInference.infer(data, config);

        assertThat(schema).isNotNull();
        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("\"name\"");
        assertThat(schema).contains("\"age\"");
    }

    @Test
    void shouldInferArraySchema() {
        var data = "[{\"id\":1},{\"id\":2}]".getBytes();
        var schema = SchemaInference.infer(data, config);

        assertThat(schema).isNotNull();
        assertThat(schema).contains("\"type\":\"array\"");
    }

    @Test
    void shouldReturnNullForEmptyData() {
        assertThat(SchemaInference.infer(new byte[0], config)).isNull();
        assertThat(SchemaInference.infer(null, config)).isNull();
    }

    @Test
    void shouldReturnNullForNonJson() {
        var data = "not json at all!!!".getBytes();
        assertThat(SchemaInference.infer(data, config)).isNull();
    }
}
