package io.camunda.bizsol.bb.ai_firewall_agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile.Replace;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared test infrastructure for safeguard-agent process tests.
 *
 * <p>Deploys the safeguard-agent BPMN (with empty AI Agent connector fields filled) before each
 * test and provides helper methods for starting process instances and completing or failing AI
 * agent jobs via {@link CamundaProcessTestContext} utilities.
 *
 * @see <a href="https://docs.camunda.io/docs/apis-tools/testing/utilities/">Testing Utilities</a>
 */
abstract class ProcessTestBase {

    // -- BPMN element IDs -------------------------------------------------------
    static final String PROCESS_ID = "safeguard-agent";

    // -- Job types --------------------------------------------------------------
    static final String AI_AGENT_JOB_TYPE = "io.camunda.agenticai:aiagent:1";

    // -- Paths ------------------------------------------------------------------
    static final Path BPMN_SOURCE = Path.of("camunda-artifacts/safeguard-agent.bpmn");
    private static final Path SYSTEM_PROMPT_PATH =
            Path.of("camunda-artifacts/txt/safeguard-systemprompt.txt");

    /** System prompt loaded once from {@code camunda-artifacts/safeguard-systemprompt.txt}. */
    static final String SYSTEM_PROMPT = loadSystemPrompt();

    private static String loadSystemPrompt() {
        try {
            return Files.readString(SYSTEM_PROMPT_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to load system prompt from " + SYSTEM_PROMPT_PATH, e);
        }
    }

    // -- Fixtures ---------------------------------------------------------------
    @Autowired CamundaClient camundaClient;
    @Autowired CamundaProcessTestContext processTestContext;

    /**
     * Before each test: deploy the safeguard-agent BPMN with the empty AI Agent connector
     * properties filled in via string-level replacements.
     */
    @BeforeEach
    void deployProcess() {
        BpmnModelInstance model =
                BpmnFile.replace(
                        BPMN_SOURCE.toFile(),
                        Replace.replace(
                                "<zeebe:input source=\"bedrock\" target=\"provider.type\" />",
                                "<zeebe:input source=\"openaiCompatible\" target=\"provider.type\" />"
                                        + "<zeebe:input source=\"http://localhost:8089/v1\""
                                        + " target=\"provider.openaiCompatible.endpoint\" />"
                                        + "<zeebe:input source=\"test-model\""
                                        + " target=\"provider.openaiCompatible.model.model\" />"),
                        Replace.replace(
                                "<zeebe:input source=\"eu-west-1\""
                                        + " target=\"provider.bedrock.region\" />",
                                ""),
                        Replace.replace(
                                "<zeebe:input source=\"credentials\""
                                        + " target=\"provider.bedrock.authentication.type\" />",
                                ""),
                        Replace.replace(
                                "<zeebe:input source=\"{{secrets.AWS_ACCESS_KEY}}\""
                                        + " target=\"provider.bedrock.authentication.accessKey\" />",
                                ""),
                        Replace.replace(
                                "<zeebe:input source=\"{{secrets.AWS_SECRET_KEY}}\""
                                        + " target=\"provider.bedrock.authentication.secretKey\" />",
                                ""),
                        Replace.replace(
                                "<zeebe:input"
                                        + " source=\"global.anthropic.claude-sonnet-4-5-20250929-v1:0\""
                                        + " target=\"provider.bedrock.model.model\" />",
                                ""));

        camundaClient
                .newDeployResourceCommand()
                .addProcessModel(model, "safeguard-agent.bpmn")
                .send()
                .join();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Helper methods                                                          ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    /**
     * Complete the next AI agent job with a JSON string that simulates a parsed LLM response.
     * Because the connector runtime is not active in CPT, the {@code resultExpression} from the
     * task headers is not evaluated. Instead, we parse the JSON and set {@code safeGuardResult}
     * directly as a process variable. Uses {@link CamundaProcessTestContext#completeJob} which
     * waits for the job internally.
     */
    void completeAgentJobWith(String responseJsonString) {
        try {
            Object parsedJson = OBJECT_MAPPER.readValue(responseJsonString, Object.class);
            processTestContext.completeJob(
                    AI_AGENT_JOB_TYPE, Map.of("safeGuardResult", parsedJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in test fixture: " + e.getMessage());
        }
    }

    /**
     * Throw a BPMN error from the next AI agent job so the boundary error event catches it. Uses
     * {@link CamundaProcessTestContext#throwBpmnErrorFromJob} which waits for the job internally.
     */
    void failAgentJobWithError(String errorCode, String errorMessage) {
        processTestContext.throwBpmnErrorFromJob(
                AI_AGENT_JOB_TYPE,
                errorCode,
                Map.of("error", Map.of("code", errorCode, "message", errorMessage)));
    }

    /** Start a process instance with the given variables. */
    io.camunda.client.api.response.ProcessInstanceEvent startProcess(
            Map<String, Object> variables) {
        return camundaClient
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(variables)
                .send()
                .join();
    }

    /** Default variables: valid short prompt, system prompt from safeguard-systemprompt.txt. */
    Map<String, Object> defaultVariables() {
        return Map.of(
                "userPromptToSafeguard",
                "What is the status of my insurance claim number IC-2024-001?",
                "systemPrompt",
                SYSTEM_PROMPT);
    }
}
