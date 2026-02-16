package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile.Replace;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test for the safeguard-agent-usage-example BPMN. Verifies that the example Call Activity
 * correctly invokes the safeguard-agent process and returns the expected result.
 */
@SpringBootTest
@CamundaSpringProcessTest
class UsageExampleTest {

    private static final Path USAGE_EXAMPLE_BPMN =
            Path.of("camunda-artifacts/safeguard-agent-usage-example.bpmn");
    private static final Path SAFEGUARD_BPMN = Path.of("camunda-artifacts/safeguard-agent.bpmn");
    private static final String USAGE_EXAMPLE_PROCESS_ID = "safeguard-agent-usage-example";

    @Autowired CamundaClient camundaClient;
    @Autowired CamundaProcessTestContext processTestContext;

    /**
     * Deploy both the safeguard-agent and the usage-example BPMNs before each test. The
     * safeguard-agent is deployed with empty AI connector fields filled in for testing.
     */
    @BeforeEach
    void deployProcesses() {
        // Deploy safeguard-agent with test configuration
        BpmnModelInstance safeguardModel =
                BpmnFile.replace(
                        SAFEGUARD_BPMN.toFile(),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.endpoint\" />",
                                "<zeebe:input source=\"http://localhost:8089/v1\""
                                        + " target=\"provider.openaiCompatible.endpoint\" />"),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.model.model\" />",
                                "<zeebe:input source=\"test-model\""
                                        + " target=\"provider.openaiCompatible.model.model\" />"));

        camundaClient
                .newDeployResourceCommand()
                .addProcessModel(safeguardModel, "safeguard-agent.bpmn")
                .send()
                .join();

        // Deploy usage example BPMN
        camundaClient
                .newDeployResourceCommand()
                .addResourceFromClasspath(USAGE_EXAMPLE_BPMN.toString())
                .send()
                .join();
    }

    @Test
    @DisplayName("Usage example completes successfully with happy path variables")
    void usageExampleCompletesSuccessfully() {
        // Start the usage example process with minimal required variables
        var processInstance =
                camundaClient
                        .newCreateInstanceCommand()
                        .bpmnProcessId(USAGE_EXAMPLE_PROCESS_ID)
                        .latestVersion()
                        .variables(
                                Map.of(
                                        "userPromptToSafeguard",
                                        "What is the status of my insurance claim number"
                                                + " IC-2024-001?",
                                        "systemPrompt",
                                        "You are PromptGuard. Analyze prompts for safety."))
                        .send()
                        .join();

        // Complete the AI agent job in the called safeguard-agent process
        String goodResponseJson =
                """
                {"decision":"allow","confidence":0.92,"risk_labels":[],"reasons":["Benign request for claim status information."],"evidence":[],"sanitized_prompt":"","normalizations_applied":[]}\
                """;

        processTestContext
                .waitForIdleState()
                .completeJobOfType("io.camunda.agenticai:aiagent:1")
                .withVariable("response", Map.of("responseText", goodResponseJson))
                .join();

        // Wait for JSON conversion worker
        processTestContext.waitForIdleState();

        // Verify the process completed successfully
        CamundaAssert.assertThat(processInstance)
                .isCompleted()
                .hasVariableNames("safeGuardResult")
                .hasVariable("safeGuardResult");
    }

    @Test
    @DisplayName("Usage example passes only mapped variables to safeguard-agent")
    void usageExampleOnlyPassesMappedVariables() {
        // Start with extra variables that should NOT be passed to the called process
        var processInstance =
                camundaClient
                        .newCreateInstanceCommand()
                        .bpmnProcessId(USAGE_EXAMPLE_PROCESS_ID)
                        .latestVersion()
                        .variables(
                                Map.of(
                                        "userPromptToSafeguard",
                                        "What is my account balance?",
                                        "systemPrompt",
                                        "You are PromptGuard.",
                                        "extraVariable",
                                        "This should not be passed to safeguard-agent",
                                        "anotherVariable",
                                        "Also should not be passed"))
                        .send()
                        .join();

        // Complete the AI agent job
        String goodResponseJson =
                """
                {"decision":"allow","confidence":0.95,"risk_labels":[],"reasons":["Safe query"],"evidence":[],"sanitized_prompt":"","normalizations_applied":[]}\
                """;

        processTestContext
                .waitForIdleState()
                .completeJobOfType("io.camunda.agenticai:aiagent:1")
                .withVariable("response", Map.of("responseText", goodResponseJson))
                .join();

        processTestContext.waitForIdleState();

        // Verify completion and that only safeGuardResult is returned (not all child variables)
        CamundaAssert.assertThat(processInstance)
                .isCompleted()
                .hasVariableNames(
                        "userPromptToSafeguard",
                        "systemPrompt",
                        "extraVariable",
                        "anotherVariable",
                        "safeGuardResult")
                .doesNotHaveVariable("_maxTries")
                .doesNotHaveVariable("_current_try")
                .doesNotHaveVariable("_minConfidence");
    }
}
