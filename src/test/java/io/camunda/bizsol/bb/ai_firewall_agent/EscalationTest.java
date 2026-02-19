package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tests all escalation event paths in the safeguard-agent BPMN process.
 *
 * <p>The AI Agent connector task properties are already fully configured in the BPMN file via
 * {@code zeebe:input} mappings and {@code zeebe:taskHeaders}. This test deploys the BPMN as-is
 * (with optional string-level replacements via {@link BpmnFile#replace}) and manually completes /
 * fails AI agent jobs to exercise each escalation path.
 *
 * <h3>Escalation events under test</h3>
 *
 * <ol>
 *   <li><b>safeguard_max-user-input-exceeded</b> – user prompt exceeds size threshold
 *   <li><b>safeguard_max-iterations-reached</b> – max retry count exhausted
 *   <li><b>safeguard_task-agent-failed</b> – AI agent task throws a BPMN error
 *   <li><b>safeguard_bad-agent-output</b> – LLM response missing required JSON fields
 *   <li><b>safeguard_json-worker-error</b> – JSON converter worker fails to parse response (tested
 *       in {@link JsonWorkerErrorTest})
 * </ol>
 */
@SpringBootTest
@CamundaSpringProcessTest
class EscalationTest extends ProcessTestBase {

    /** Escalation: user prompt too large */
    private static final String ESCALATION_MAX_INPUT_EXCEEDED = "Event_max-user-input-exceeded";

    /** Escalation: max iterations reached */
    private static final String ESCALATION_MAX_ITERATIONS = "Event_max-iterations-reached";

    /** Escalation: AI agent task error (boundary error → escalation throw) */
    private static final String ESCALATION_TASK_AGENT_FAILED = "Event_task-agent-failed";

    /** Escalation: LLM output not matching expected JSON schema */
    private static final String ESCALATION_BAD_AGENT_OUTPUT = "Event_bad-agent-output";

    /** Script task: retains safeGuardResult history when confidence is insufficient */
    private static final String ACTIVITY_RETAIN_HISTORY = "Activity_retain_history";

    /** Script task: appends confidence-refinement directive to system prompt */
    private static final String ACTIVITY_REFINE_SYSTEM_PROMPT = "Activity_refine_system_prompt";

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Escalation: safeguard_max-user-input-exceeded                           ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Escalation: safeguard_max-user-input-exceeded")
    class MaxUserInputExceeded {

        @Test
        @DisplayName(
                "User prompt exceeding _maxUserPromptSize triggers"
                        + " safeguard_max-user-input-exceeded escalation")
        void escalatesWhenUserPromptExceedsMaxSize() {
            // Use a very small maxUserPromptSize so a short prompt still triggers it
            Map<String, Object> vars = new HashMap<>();
            vars.put("userPromptToSafeguard", "This prompt is too long for the threshold");
            vars.put("systemPrompt", SYSTEM_PROMPT);
            vars.put("maxUserPromptSize", 5); // threshold: only 5 characters allowed

            var processInstance = startProcess(vars);

            // The process should reach the escalation throw event without
            // requiring any job handling (no agent call needed)
            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements(ESCALATION_MAX_INPUT_EXCEEDED);
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Escalation: safeguard_max-iterations-reached                            ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Escalation: safeguard_max-iterations-reached")
    class MaxIterationsReached {

        @Test
        @DisplayName("Low confidence + maxTries=1 triggers safeguard_max-iterations-reached")
        void escalatesWhenMaxIterationsExhausted() {
            // maxTries=1 → only one attempt allowed
            Map<String, Object> vars = new HashMap<>();
            vars.put("userPromptToSafeguard", "Test prompt for iteration check");
            vars.put("systemPrompt", SYSTEM_PROMPT);
            vars.put("maxTries", 1);
            vars.put("minConfidence", 0.95);

            var processInstance = startProcess(vars);

            // 1st iteration: complete the AI agent job with a valid but
            //    LOW-confidence response → json-converter parses it →
            //    confidence < minConfidence → loop back → _current_try now > maxTries
            String lowConfidenceJson =
                    """
                    {"decision":"allow","confidence":0.3,"risk_labels":[],"reasons":["low confidence test"],"evidence":[],"sanitized_prompt":"","normalizations_applied":[]}\
                    """;
            completeAgentJobWith(lowConfidenceJson);

            // The JsonConverterWorker handles the json-converter job automatically.
            // After parsing, safeGuardResult.confidence = 0.3 < 0.95 →
            //   confidence too low → "Retain history of safeGuard results" (script) →
            //   "Refine system prompt" (script) → loops back to iteration check →
            //   _current_try = 2 > _maxTries = 1 → escalation
            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements(
                            ACTIVITY_RETAIN_HISTORY,
                            ACTIVITY_REFINE_SYSTEM_PROMPT,
                            ESCALATION_MAX_ITERATIONS);

            // Verify the low-confidence result was appended to safeGuardResultHistory
            CamundaAssert.assertThat(processInstance)
                    .hasVariableSatisfies(
                            "safeGuardResultHistory",
                            List.class,
                            history -> {
                                Assertions.assertThat(history).hasSize(1);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> entry = (Map<String, Object>) history.get(0);
                                Assertions.assertThat(entry.get("decision")).isEqualTo("allow");
                                Assertions.assertThat(
                                                ((Number) entry.get("confidence")).doubleValue())
                                        .isEqualTo(0.3);
                            });
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Escalation: safeguard_task-agent-failed                                 ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Escalation: safeguard_task-agent-failed")
    class TaskAgentFailed {

        @Test
        @DisplayName("BPMN error from AI agent triggers safeguard_task-agent-failed escalation")
        void escalatesWhenAgentTaskThrowsError() {
            var processInstance = startProcess(defaultVariables());

            // Throw a BPMN error from the AI agent job.
            // The boundary error event (Event_00rqlj4) catches it and routes
            // to the escalation throw event (Event_task-agent-failed).
            failAgentJobWithError("MODEL_CALL_FAILED", "Simulated model failure for testing");

            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements(ESCALATION_TASK_AGENT_FAILED);
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Escalation: safeguard_bad-agent-output                                  ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Escalation: safeguard_bad-agent-output")
    class BadAgentOutput {

        @Test
        @DisplayName(
                "Response missing 'decision' and 'confidence' triggers"
                        + " safeguard_bad-agent-output escalation")
        void escalatesWhenResponseMissingRequiredFields() {
            var processInstance = startProcess(defaultVariables());

            // Complete the AI agent job with JSON that is valid but does NOT
            // contain the required 'decision' and 'confidence' fields.
            // After json-converter parses it, the gateway check
            //   is defined(safeGuardResult.decision) and
            //   is defined(safeGuardResult.confidence)
            // evaluates to false → escalation
            String invalidOutputJson =
                    """
                    {"some_field":"some_value","foo":"bar"}\
                    """;
            completeAgentJobWith(invalidOutputJson);

            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements(ESCALATION_BAD_AGENT_OUTPUT);
        }

        @Test
        @DisplayName(
                "Response with 'decision' but missing 'confidence' triggers"
                        + " safeguard_bad-agent-output escalation")
        void escalatesWhenConfidenceFieldMissing() {
            var processInstance = startProcess(defaultVariables());

            // Only 'decision' is present, 'confidence' is missing
            String partialJson =
                    """
                    {"decision":"allow","risk_labels":[],"reasons":["partial"]}\
                    """;
            completeAgentJobWith(partialJson);

            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements(ESCALATION_BAD_AGENT_OUTPUT);
        }
    }
}
