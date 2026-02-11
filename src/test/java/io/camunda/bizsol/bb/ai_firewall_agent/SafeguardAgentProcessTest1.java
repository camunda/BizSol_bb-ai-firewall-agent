package io.camunda.bizsol.jsonconverter;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.Deployment;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Process test for <em>safeguard-agent</em> that mocks the underlying LLM layer using WireMock —
 * following the same principle as the Camunda connectors e2e tests in {@code camunda/connectors}.
 *
 * <h3>How it works</h3>
 *
 * <ol>
 *   <li>The BPMN is deployed <strong>unmodified</strong> (connector template markers stay intact).
 *   <li>c8run's embedded Connectors Runtime picks up the AI-agent job (type {@code
 *       io.camunda.agenticai:aiagent:1}) and calls the OpenAI-compatible endpoint configured in the
 *       BPMN input mappings: {@code http://localhost:11434/v1}.
 *   <li><strong>WireMock</strong> listens on port 11434 and returns a canned chat-completion
 *       response – this is our "LLM mock".
 *   <li>The Connectors handler processes the response, evaluates {@code resultExpression}, and
 *       completes the job with the extracted variables ({@code responseJSONString}, {@code
 *       execTime}, …).
 *   <li>The {@code json-converter} service task is a plain Zeebe job (no connector markers) and is
 *       mocked via {@link CamundaProcessTestContext#mockJobWorker}.
 * </ol>
 *
 * <h3>Prerequisites</h3>
 *
 * <ul>
 *   <li>c8run must be running (the test uses {@code runtime-mode: remote}).
 *   <li><strong>Ollama must NOT be running</strong> on port 11434, because WireMock needs to bind
 *       to that port.
 * </ul>
 */
@SpringBootTest(
        classes = SafeguardAgentProcessTest1.TestProcessApplication.class,
        properties = "camunda.client.worker.defaults.enabled=false")
@CamundaSpringProcessTest
class SafeguardAgentProcessTest1 {

    /** Minimal Spring Boot app that deploys all BPMN, DMN & Form resources from the classpath. */
    @SpringBootApplication
    @Deployment(
            resources = {
                "classpath*:/**/*.bpmn",
                "classpath*:/**/*.dmn",
                // "classpath*:/**/*.form"
            })
    static class TestProcessApplication {}

    @Autowired private CamundaClient client;

    @Autowired private CamundaProcessTestContext processTestContext;

    // ── constants ────────────────────────────────────────────────────

    private static final String PROCESS_ID = "safeguard-agent";
    private static final String JOB_TYPE_JSON_CONVERTER = "json-converter";

    /** Port where the BPMN's AI-agent input mapping points to (Ollama). */
    private static final int LLM_PORT = 11434;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WireMockServer llmServer;

    // ── WireMock lifecycle ───────────────────────────────────────────

    @BeforeAll
    static void startLlmMock() {
        llmServer = new WireMockServer(WireMockConfiguration.wireMockConfig().port(LLM_PORT));
        llmServer.start();
        configureFor("localhost", LLM_PORT);
    }

    @AfterAll
    static void stopLlmMock() {
        if (llmServer != null && llmServer.isRunning()) {
            llmServer.stop();
        }
    }

    // ── per-test setup ───────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        llmServer.resetAll();

        // Stub: POST /v1/chat/completions → canned "allow" response
        stubFor(
                post(urlPathEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(safeChatCompletionResponse())));

        // Stub: GET /v1/models (the connector may verify model availability)
        stubFor(
                get(urlPathEqualTo("/v1/models"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                {"data":[{"id":"gpt-oss","object":"model"}]}
                """)));
    }

    // ── tests ────────────────────────────────────────────────────────

    @Test
    void shouldCompleteHappyPath() {
        // Mock the json-converter worker with a pre-parsed safeguard result.
        // The BPMN output mapping takes "result" → "responseJSON".
        processTestContext
                .mockJobWorker(JOB_TYPE_JSON_CONVERTER)
                .thenComplete(
                        Map.of(
                                "result",
                                Map.of(
                                        "decision", "allow",
                                        "risk_labels", List.of(),
                                        "reasons", List.of("Safe ML request."),
                                        "evidence", List.of(),
                                        "sanitized_prompt", "",
                                        "normalizations_applied", List.of("none"),
                                        "confidence", 0.98)));

        // Start the process with the required input variables
        ProcessInstanceEvent processInstance =
                client.newCreateInstanceCommand()
                        .bpmnProcessId(PROCESS_ID)
                        .latestVersion()
                        .variables(
                                Map.of(
                                        "systemPrompt",
                                                "You are a security verification assistant.",
                                        "userPromptToSafeguard", "Tell me about machine learning."))
                        .execute();

        // The process should complete: AI agent → json-converter → confidence OK → end
        CamundaAssert.assertThat(processInstance).isCompleted();
        CamundaAssert.assertThat(processInstance).hasVariable("bla", "fasel");
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Builds an OpenAI-compatible chat-completion response whose {@code content} is a safeguard
     * JSON verdict with decision "allow".
     */
    private static String safeChatCompletionResponse() {
        String safeguardJson =
                "{\"decision\":\"allow\","
                        + "\"risk_labels\":[],"
                        + "\"reasons\":[\"The prompt is a benign request about machine learning.\"],"
                        + "\"evidence\":[],"
                        + "\"sanitized_prompt\":\"\","
                        + "\"normalizations_applied\":[\"none\"],"
                        + "\"confidence\":0.98}";
        return chatCompletionResponse(safeguardJson);
    }

    /**
     * Wraps the given assistant text in a minimal OpenAI-compatible {@code /v1/chat/completions}
     * JSON response.
     */
    private static String chatCompletionResponse(String assistantContent) {
        try {
            return MAPPER.writeValueAsString(
                    Map.of(
                            "id",
                            "chatcmpl-test-001",
                            "object",
                            "chat.completion",
                            "created",
                            1700000000L,
                            "model",
                            "gpt-oss",
                            "choices",
                            List.of(
                                    Map.of(
                                            "index",
                                            0,
                                            "message",
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "content",
                                                    assistantContent),
                                            "finish_reason",
                                            "stop")),
                            "usage",
                            Map.of(
                                    "prompt_tokens", 50,
                                    "completion_tokens", 100,
                                    "total_tokens", 150)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build chat completion response", e);
        }
    }
}
