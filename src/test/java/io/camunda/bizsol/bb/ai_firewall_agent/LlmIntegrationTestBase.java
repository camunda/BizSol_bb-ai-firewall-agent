package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile.Replace;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared base class for LLM integration tests that use real LLM calls via GitHub Models.
 *
 * <p>Unlike {@link ProcessTestBase}, this class enables the connectors runtime to handle AI agent
 * jobs. Tests in this class make actual HTTP calls to GitHub Models (gpt-4o-mini) using {@code
 * GITHUB_TOKEN} or {@code LLM_API_KEY} environment variables.
 *
 * <p>Tests are conditionally enabled when either {@code GITHUB_TOKEN} or {@code LLM_API_KEY} is
 * set. This allows graceful skipping in local environments without credentials.
 *
 * <h3>Configuration</h3>
 *
 * <ul>
 *   <li>Connectors runtime enabled: {@code camunda.process-test.connectors-enabled=true}
 *   <li>LLM endpoint: {@code https://models.inference.ai.github.com/v1}
 *   <li>LLM model: {@code gpt-4o-mini}
 *   <li>API key: {@code GITHUB_TOKEN} env var (via connector secret {@code LLM_API_KEY})
 *   <li>Assertion timeout: 3 minutes (real LLM calls can take 10-30s)
 * </ul>
 *
 * @see <a href="https://docs.camunda.io/docs/apis-tools/testing/utilities/">Camunda Process
 *     Test</a>
 */
@SpringBootTest(
        properties = {
            "camunda.process-test.connectors-enabled=true",
            "camunda.process-test.connectors-secrets.LLM_API_KEY=${GITHUB_TOKEN:${LLM_API_KEY:}}"
        })
abstract class LlmIntegrationTestBase {

    // -- BPMN element IDs -------------------------------------------------------
    static final String PROCESS_ID = "safeguard-agent";

    // -- Paths ------------------------------------------------------------------
    static final Path BPMN_SOURCE = Path.of("camunda-artifacts/safeguard-agent.bpmn");
    private static final Path SYSTEM_PROMPT_PATH =
            Path.of("camunda-artifacts/safeguard-systemprompt.txt");

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

    /**
     * Before all tests: set a longer assertion timeout for real LLM calls. Real LLM calls can take
     * 10-30 seconds, so we allow up to 3 minutes.
     */
    @BeforeAll
    static void configureCamundaAssert() {
        // CamundaAssert.setAssertionTimeout(Duration.ofMinutes(3));
    }

    /**
     * Before each test: deploy the safeguard-agent BPMN with GitHub Models configuration. Replaces
     * the local Ollama endpoint and empty model with GitHub Models settings.
     */
    @BeforeEach
    void deployProcess() {
        BpmnModelInstance model =
                BpmnFile.replace(
                        BPMN_SOURCE.toFile(),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.endpoint\" />",
                                "<zeebe:input source=\"https://models.github.ai/inference\""
                                    + " target=\"provider.openaiCompatible.endpoint\" />\n"
                                    + "          <zeebe:input source=\"{{secrets.LLM_API_KEY}}\""
                                    + " target=\"provider.openaiCompatible.authentication.apiKey\""
                                    + " />"),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.model.model\" />",
                                "<zeebe:input source=\"openai/gpt-4.1-mini\""
                                        + " target=\"provider.openaiCompatible.model.model\" />"));

        // --- temporary: dump the resulting BPMN for debugging ---
        try {
            Path tmp = Files.createTempFile("safeguard-agent-IT-", ".bpmn");
            Bpmn.writeModelToFile(tmp.toFile(), model);
            System.out.println("[LlmIntegrationTestBase] Deployed BPMN written to: " + tmp);
        } catch (Exception e) {
            System.err.println("[LlmIntegrationTestBase] Could not write debug BPMN: " + e);
        }

        camundaClient
                .newDeployResourceCommand()
                .addProcessModel(model, "safeguard-agent.bpmn")
                .send()
                .join();
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Helper methods                                                          ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    /**
     * Load a prompt text file from the classpath {@code prompts/} directory.
     *
     * @param filename the name of the prompt file (e.g., {@code safeguard-block-injection.txt})
     * @return the prompt text content
     */
    protected String loadPrompt(String filename) {
        try {
            return Files.readString(
                    Path.of("src/test/resources/prompts/" + filename), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt file: " + filename, e);
        }
    }

    /**
     * Start a safeguard process instance with the given user prompt. Uses the default system prompt
     * and sets {@code minConfidence} to 0.5 to avoid retry loops during testing (the LLM may return
     * varying confidence levels).
     *
     * @param userPrompt the user prompt to safeguard
     * @return the process instance event
     */
    protected ProcessInstanceEvent startSafeguardProcess(String userPrompt) {
        return camundaClient
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(
                        Map.of(
                                "userPromptToSafeguard",
                                userPrompt,
                                "systemPrompt",
                                SYSTEM_PROMPT,
                                "minConfidence",
                                0.5))
                .send()
                .join();
    }
}
