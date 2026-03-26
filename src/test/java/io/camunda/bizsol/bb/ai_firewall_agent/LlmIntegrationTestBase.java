package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile.Replace;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Shared base class for LLM integration tests that use real LLM calls via AWS Bedrock.
 *
 * <p>Unlike {@link ProcessTestBase}, this class enables the connectors runtime to handle AI agent
 * jobs. Tests in this class make actual calls to AWS Bedrock (Claude) using {@code AWS_BEDROCK_KEY}
 * and {@code AWS_BEDROCK_SECRET} environment variables.
 *
 * <p>Tests are conditionally enabled when both credentials are set. This allows graceful skipping
 * in local environments without credentials.
 *
 * @see <a href="https://docs.camunda.io/docs/apis-tools/testing/utilities/">Camunda Process
 *     Test</a>
 */
@SpringBootTest(
        properties = {
            "camunda.process-test.connectors-enabled=true",
            "camunda.process-test.connectors-secrets.AWS_ACCESS_KEY=${AWS_BEDROCK_KEY:}",
            "camunda.process-test.connectors-secrets.AWS_SECRET_KEY=${AWS_BEDROCK_SECRET:}"
        })
abstract class LlmIntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(LlmIntegrationTestBase.class);

    /** Default region when {@code test.bedrock.region} system property is not set. */
    private static final String DEFAULT_REGION = "eu-central-1";

    /** AWS Bedrock region. Override via {@code -Dtest.bedrock.region=…}. */
    static final String BEDROCK_REGION = System.getProperty("test.bedrock.region", DEFAULT_REGION);

    /** Default model when {@code test.bedrock.model} system property is not set. */
    private static final String DEFAULT_MODEL = "eu.anthropic.claude-sonnet-4-6";

    /**
     * Model identifier used for the BPMN connector. Override via {@code -Dtest.bedrock.model=…}.
     */
    static final String MODEL = System.getProperty("test.bedrock.model", DEFAULT_MODEL);

    // -- Process defaults -------------------------------------------------------
    static final String PROCESS_ID = "safeguard-agent";
    static final double MIN_CONFIDENCE = 0.8;
    static final int MAX_TRIES = 3;

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
     * Before all tests: verify that AWS Bedrock credentials are available, then set a longer
     * assertion timeout for real LLM calls. Tests are skipped when credentials are not set.
     */
    @BeforeAll
    static void configureCamundaAssert() {
        String key = System.getenv("AWS_BEDROCK_KEY");
        String secret = System.getenv("AWS_BEDROCK_SECRET");
        Assumptions.assumeTrue(
                key != null && !key.isBlank() && secret != null && !secret.isBlank(),
                "Skipping LLM integration tests: AWS_BEDROCK_KEY/AWS_BEDROCK_SECRET not set");
        CamundaAssert.setAssertionTimeout(Duration.ofSeconds(45));
    }

    /**
     * Before each test: deploy the safeguard-agent BPMN with AWS Bedrock configuration. Replaces
     * the openaiCompatible provider with Bedrock provider settings.
     */
    @BeforeEach
    void deployProcess() {
        BpmnModelInstance model =
                BpmnFile.replace(
                        BPMN_SOURCE.toFile(),
                        Replace.replace(
                                "<zeebe:input source=\"openaiCompatible\" target=\"provider.type\" />",
                                "<zeebe:input source=\"bedrock\" target=\"provider.type\" />"),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.endpoint\" />",
                                "<zeebe:input source=\""
                                        + BEDROCK_REGION
                                        + "\" target=\"provider.bedrock.region\" />\n"
                                        + "          <zeebe:input source=\"credentials\""
                                        + " target=\"provider.bedrock.authentication.type\" />\n"
                                        + "          <zeebe:input source=\"{{secrets.AWS_ACCESS_KEY}}\""
                                        + " target=\"provider.bedrock.authentication.accessKey\" />\n"
                                        + "          <zeebe:input source=\"{{secrets.AWS_SECRET_KEY}}\""
                                        + " target=\"provider.bedrock.authentication.secretKey\" />"),
                        Replace.replace(
                                "<zeebe:input source=\"PT10M\""
                                        + " target=\"provider.openaiCompatible.timeouts.timeout\" />",
                                ""),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.model.model\" />",
                                "<zeebe:input source=\""
                                        + MODEL
                                        + "\""
                                        + " target=\"provider.bedrock.model.model\" />"),
                        Replace.replace("retries=\"3\"", "retries=\"0\""));

        // --- dump the resulting BPMN for debugging (skip in CI) ---
        if (System.getenv("CI") == null) {
            try {
                Path tmp = Files.createTempFile("safeguard-agent-IT-", ".bpmn");
                Bpmn.writeModelToFile(tmp.toFile(), model);
                LOG.debug("Deployed BPMN written to: {}", tmp);
            } catch (Exception e) {
                LOG.warn("Could not write debug BPMN", e);
            }
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
                                MIN_CONFIDENCE,
                                "maxTries",
                                MAX_TRIES))
                .send()
                .join();
    }
}
