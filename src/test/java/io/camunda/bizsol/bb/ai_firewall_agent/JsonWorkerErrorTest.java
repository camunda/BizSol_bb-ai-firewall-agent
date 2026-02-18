package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CPT tests for the json-converter worker error path in the safeguard-agent BPMN process.
 *
 * <p>When the {@code JsonConverterWorker} receives malformed JSON from the AI agent response, it
 * throws a {@link io.camunda.client.exception.BpmnError} with code {@code jsonConversionError}. The
 * boundary error event ({@code Event_errorJsonWorker}) on the json-converter service task catches
 * this and routes to the {@code safeguard_json-worker-error} escalation throw event ({@code
 * Event_json-worker-error}).
 */
@SpringBootTest
@CamundaSpringProcessTest
class JsonWorkerErrorTest extends ProcessTestBase {

    /** Escalation throw event for json-converter worker errors. */
    private static final String ESCALATION_JSON_WORKER_ERROR = "Event_json-worker-error";

    @Test
    @DisplayName(
            "Malformed JSON from AI agent triggers jsonConversionError boundary event"
                    + " and safeguard_json-worker-error escalation")
    void escalatesWhenAgentReturnsInvalidJson() {
        var processInstance = startProcess(defaultVariables());

        // Complete the AI agent job with a response that is NOT valid JSON.
        // The JsonConverterWorker will fail to parse it and throw a BpmnError
        // with code "jsonConversionError" → boundary error → escalation.
        completeAgentJobWith("{bad json}");

        CamundaAssert.assertThat(processInstance)
                .hasCompletedElements(ESCALATION_JSON_WORKER_ERROR);
    }

    @Test
    @DisplayName(
            "Plain text from AI agent triggers jsonConversionError boundary event"
                    + " and safeguard_json-worker-error escalation")
    void escalatesWhenAgentReturnsPlainText() {
        var processInstance = startProcess(defaultVariables());

        // AI agent returns plain text instead of JSON
        completeAgentJobWith("Sorry, I cannot process this request.");

        CamundaAssert.assertThat(processInstance)
                .hasCompletedElements(ESCALATION_JSON_WORKER_ERROR);
    }

    @Test
    @DisplayName(
            "XML from AI agent triggers jsonConversionError boundary event"
                    + " and safeguard_json-worker-error escalation")
    void escalatesWhenAgentReturnsXml() {
        var processInstance = startProcess(defaultVariables());

        // AI agent returns XML instead of JSON
        completeAgentJobWith("<response><decision>allow</decision></response>");

        CamundaAssert.assertThat(processInstance)
                .hasCompletedElements(ESCALATION_JSON_WORKER_ERROR);
    }
}
