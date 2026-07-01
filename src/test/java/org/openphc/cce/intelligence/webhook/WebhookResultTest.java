package org.openphc.cce.intelligence.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookResult} factory methods and its JSON projection.
 */
class WebhookResultTest {

    @Test
    void success_setsFlagsAndOmitsError() {
        WebhookResult result = WebhookResult.success(200, 1);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getHttpStatus()).isEqualTo(200);
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getErrorMessage()).isNull();

        JsonNode json = result.toJsonNode();
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(json.get("attempts").asInt()).isEqualTo(1);
        assertThat(json.has("errorMessage")).isFalse();
    }

    @Test
    void failure_setsErrorAndSerializesIt() {
        WebhookResult result = WebhookResult.failure(503, "service unavailable", 3);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getHttpStatus()).isEqualTo(503);
        assertThat(result.getAttempts()).isEqualTo(3);
        assertThat(result.getErrorMessage()).isEqualTo("service unavailable");

        JsonNode json = result.toJsonNode();
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("httpStatus").asInt()).isEqualTo(503);
        assertThat(json.get("attempts").asInt()).isEqualTo(3);
        assertThat(json.get("errorMessage").asText()).isEqualTo("service unavailable");
    }

    @Test
    void failure_withNullError_omitsErrorMessageField() {
        WebhookResult result = WebhookResult.failure(0, null, 2);

        assertThat(result.toJsonNode().has("errorMessage")).isFalse();
    }
}
