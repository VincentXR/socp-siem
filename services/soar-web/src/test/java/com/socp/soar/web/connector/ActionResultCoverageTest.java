package com.socp.soar.web.connector;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionResultCoverageTest {

    @Test
    void successCarriesOperationIdAndImmutableReceipt() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("count", 1);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("httpStatus", 200);

        ActionResult result = ActionResult.success("OP-1", output, receipt);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.operationId()).isEqualTo("OP-1");
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.remoteTime()).isNull();
        assertThat(result.output()).containsEntry("count", 1);
        assertThat(result.receipt()).containsEntry("httpStatus", 200);

        output.put("mutated", true);
        assertThat(result.output()).doesNotContainKey("mutated");

        assertThatThrownBy(() -> result.output().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.receipt().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failedCarriesErrorCodeAndRetryability() {
        ActionResult retryable = ActionResult.failed("SERVICE_CALL_FAILED", "HTTP 503", true);
        assertThat(retryable.status()).isEqualTo("FAILED");
        assertThat(retryable.errorCode()).isEqualTo("SERVICE_CALL_FAILED");
        assertThat(retryable.errorMessage()).isEqualTo("HTTP 503");
        assertThat(retryable.retryable()).isTrue();
        assertThat(retryable.operationId()).isNull();
        assertThat(retryable.output()).isEmpty();
        assertThat(retryable.receipt()).isEmpty();

        ActionResult terminal = ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unknown action", false);
        assertThat(terminal.retryable()).isFalse();
        assertThat(terminal.status()).isEqualTo("FAILED");
    }

    @Test
    void unknownKeepsAmbiguousRemoteOutcome() {
        ActionResult result = ActionResult.unknown("REMOTE_RESULT_UNKNOWN", "read timed out");

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.errorCode()).isEqualTo("REMOTE_RESULT_UNKNOWN");
        assertThat(result.errorMessage()).isEqualTo("read timed out");
        assertThat(result.retryable()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.receipt()).isEmpty();
    }

    @Test
    void statusIsNormalizedAndMissingCollectionsBecomeEmpty() {
        assertThat(new ActionResult(null, null, null, false, null, null, null, null).status())
                .isEqualTo("FAILED");
        assertThat(new ActionResult("succeeded", null, null, false, null, null, null, null).status())
                .isEqualTo("SUCCEEDED");
        assertThat(new ActionResult("Unknown", null, null, false, null, null, null, null).status())
                .isEqualTo("UNKNOWN");

        ActionResult empty = new ActionResult("SUCCEEDED", null, Map.of(), false, null, null, null, Map.of());
        assertThat(empty.output()).isEmpty();
        assertThat(empty.receipt()).isEmpty();
        assertThatThrownBy(() -> empty.output().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
