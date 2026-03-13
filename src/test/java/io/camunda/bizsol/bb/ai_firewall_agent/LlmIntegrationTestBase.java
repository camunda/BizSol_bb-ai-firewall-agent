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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Shared base class for LLM integration tests that use real LLM calls via GitHub Models.
 *
 * <p>Unlike {@link ProcessTestBase}, this class enables the connectors runtime to handle AI agent
 * jobs. Tests in this class make actual HTTP calls to GitHub Models (gpt-4o-mini) using the {@code
 * GITHUB_TOKEN} environment variable.
 *
 * <p>Tests are conditionally enabled when {@code GITHUB_TOKEN} is set. This allows graceful
 * skipping in local environments without credentials.
 *
 * <h3>Rate limiting</h3>
 *
 * GitHub Models enforces a burst rate limit (~5 requests per minute). The {@link
 * #acquireRateSlot()} method implements a sliding-window rate limiter shared across all tests in a
 * JVM. Tests call it before starting a process instance; it blocks until a slot is available in the
 * current window.
 *
 * @see <a href="https://docs.camunda.io/docs/apis-tools/testing/utilities/">Camunda Process
 *     Test</a>
 */
@SpringBootTest(
        properties = {
            "camunda.process-test.connectors-enabled=true",
            "camunda.process-test.connectors-secrets.GITHUB_TOKEN=${GITHUB_TOKEN:}"
        })
abstract class LlmIntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(LlmIntegrationTestBase.class);

    // -- Rate limiter -----------------------------------------------------------
    /** Maximum requests allowed within the sliding window. */
    private static final int RATE_LIMIT_MAX_REQUESTS = 4;

    /** Sliding window duration. */
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    /** Timestamps of recent requests (sliding window). Guarded by {@code RATE_LOCK}. */
    private static final Deque<Instant> REQUEST_TIMESTAMPS = new ArrayDeque<>();

    private static final Object RATE_LOCK = new Object();

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
     * Before all tests: verify that {@code GITHUB_TOKEN} is available, then set a longer assertion
     * timeout for real LLM calls. Tests are skipped when {@code GITHUB_TOKEN} is not set. In CI,
     * the workflow must declare {@code permissions: models: read} so the default token can access
     * GitHub Models.
     */
    @BeforeAll
    static void configureCamundaAssert() {
        String token = System.getenv("GITHUB_TOKEN");
        Assumptions.assumeTrue(
                token != null && !token.isBlank(),
                "Skipping LLM integration tests: GITHUB_TOKEN is not set");
        CamundaAssert.setAssertionTimeout(Duration.ofSeconds(45));
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
                                        + "          <zeebe:input source=\"{{secrets.GITHUB_TOKEN}}\""
                                        + " target=\"provider.openaiCompatible.authentication.apiKey\""
                                        + " />"),
                        Replace.replace(
                                "<zeebe:input target=\"provider.openaiCompatible.model.model\" />",
                                "<zeebe:input source=\"openai/gpt-4.1-mini\""
                                        + " target=\"provider.openaiCompatible.model.model\" />"),
                        Replace.replace("retries=\"3\"", "retries=\"0\""),
                        Replace.replace("PT10M", "PT30S"));

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
     * <p>Callers must invoke {@link #acquireRateSlot()} before calling this method to respect
     * GitHub Models API rate limits.
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
                                0.5,
                                "maxTries",
                                1))
                .send()
                .join();
    }

    /**
     * Blocks until a rate-limit slot is available within the sliding window. Ensures at most {@link
     * #RATE_LIMIT_MAX_REQUESTS} are sent per {@link #RATE_LIMIT_WINDOW}.
     */
    protected static void acquireRateSlot() {
        synchronized (RATE_LOCK) {
            while (true) {
                Instant now = Instant.now();
                Instant windowStart = now.minus(RATE_LIMIT_WINDOW);

                // Evict timestamps outside the window
                while (!REQUEST_TIMESTAMPS.isEmpty()
                        && REQUEST_TIMESTAMPS.peekFirst().isBefore(windowStart)) {
                    REQUEST_TIMESTAMPS.pollFirst();
                }

                if (REQUEST_TIMESTAMPS.size() < RATE_LIMIT_MAX_REQUESTS) {
                    REQUEST_TIMESTAMPS.addLast(now);
                    LOG.info(
                            "Rate slot acquired ({}/{} in current window)",
                            REQUEST_TIMESTAMPS.size(),
                            RATE_LIMIT_MAX_REQUESTS);
                    return;
                }

                // Wait until the oldest timestamp leaves the window
                Instant oldest = REQUEST_TIMESTAMPS.peekFirst();
                long waitMs =
                        Duration.between(now, oldest.plus(RATE_LIMIT_WINDOW)).toMillis() + 500;
                if (waitMs > 0) {
                    LOG.info("Rate limit reached, waiting {}s for next slot", waitMs / 1000);
                    try {
                        RATE_LOCK.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
