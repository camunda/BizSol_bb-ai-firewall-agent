package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Happy-path test for the safeguard-agent BPMN process. Verifies that a well-formed, high
 * confidence AI agent response completes the process without triggering any escalation event.
 */
@SpringBootTest
@CamundaSpringProcessTest
class HappyPathTest extends ProcessTestBase {

    @Test
    @DisplayName("Valid response with high confidence completes without escalation")
    void completesSuccessfullyWithHighConfidence() {
        var processInstance = startProcess(defaultVariables());

        // Complete the AI agent with a well-formed, high-confidence response
        String goodResponseJson =
                """
                {"decision":"allow","confidence":0.99,"risk_labels":[],"reasons":["Prompt is safe"],"evidence":[],"sanitized_prompt":"","normalizations_applied":["none"]}\
                """;
        completeAgentJobWith(goodResponseJson);

        // The AI agent connector parses the JSON response →
        //   safeGuardResult has decision + confidence →
        //   confidence (0.99) >= minConfidence (0.95) →
        //   process ends at EndEvent_safeGuardResult
        CamundaAssert.assertThat(processInstance)
                .hasCompletedElements("EndEvent_safeGuardResult")
                .isCompleted()
                .hasVariableNames("safeGuardResult");
    }
}
