package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile;
import io.camunda.bizsol.bb.ai_firewall_agent.util.BpmnFile.Replace;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * <p>Rate limiting is handled by subclasses (e.g. batched execution with cooldown pauses).
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

    /** Minimum seconds to pause between tests even when no rate-limit headers are present. */
    private static final long MIN_PAUSE_SECONDS = 2;

    /** URL used for probing current rate-limit status after each LLM call. */
    private static final String RATE_LIMIT_PROBE_URL = "https://models.github.ai/inference";

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
                                "<zeebe:input source=\"openai/gpt-5-mini\""
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
     * Probe the GitHub Models API to determine the current rate-limit status and sleep until the
     * next request is allowed.
     *
     * <p>Checks response headers in order of priority:
     *
     * <ol>
     *   <li>{@code retry-after} — seconds to wait before retrying
     *   <li>{@code x-ratelimit-remaining} / {@code x-ratelimit-reset} — remaining quota and UTC
     *       epoch of next reset
     * </ol>
     *
     * <p>Falls back to a {@link #MIN_PAUSE_SECONDS}-second pause when no headers are present.
     *
     * @see <a
     *     href="https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api">
     *     GitHub REST API best practices</a>
     */
    protected void waitForRateLimit() {
        try {
            HttpResponse<Void> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder()
                                            .uri(URI.create(RATE_LIMIT_PROBE_URL))
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + System.getenv("GITHUB_TOKEN"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());

            var headers = response.headers();

            // 1. retry-after takes precedence
            var retryAfter = headers.firstValue("retry-after");
            if (retryAfter.isPresent()) {
                long wait = Long.parseLong(retryAfter.get()) + 1;
                LOG.info("⏳ retry-after={}s — pausing {}s", retryAfter.get(), wait);
                TimeUnit.SECONDS.sleep(wait);
                return;
            }

            // 2. x-ratelimit-remaining / x-ratelimit-reset
            var remaining = headers.firstValue("x-ratelimit-remaining");
            var reset = headers.firstValue("x-ratelimit-reset");
            if (remaining.isPresent()) {
                int left = Integer.parseInt(remaining.get());
                LOG.info("Rate-limit remaining: {}", left);
                if (left == 0 && reset.isPresent()) {
                    long resetEpoch = Long.parseLong(reset.get());
                    long wait = Math.max(resetEpoch - Instant.now().getEpochSecond() + 1, 1);
                    LOG.info("⏳ Rate limit exhausted — waiting {}s until reset", wait);
                    TimeUnit.SECONDS.sleep(wait);
                    return;
                }
            }

            // 3. Fallback: brief pause to avoid rapid-fire requests
            LOG.info("⏳ No rate-limit headers found — applying fallback pause of {}s", MIN_PAUSE_SECONDS);
            TimeUnit.SECONDS.sleep(MIN_PAUSE_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Rate-limit wait interrupted");
        } catch (Exception e) {
            LOG.warn(
                    "Rate-limit probe failed ({}), applying {}s fallback pause",
                    e.getMessage(),
                    MIN_PAUSE_SECONDS);
            try {
                TimeUnit.SECONDS.sleep(MIN_PAUSE_SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
