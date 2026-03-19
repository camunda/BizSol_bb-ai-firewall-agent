package io.camunda.bizsol.bb.ai_firewall_agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.Variable;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM integration tests for safeguard prompt classification.
 *
 * <p>Tests are auto-discovered from prompt files in {@code src/test/resources/prompts/} matching
 * the pattern {@code safeguard-<category>-<name>.txt}. To add a new test case, simply drop a new
 * text file following that naming convention.
 *
 * <p>Supported categories: {@code block}, {@code warn}, {@code allow}. The category extracted from
 * the filename is used as the expected decision.
 *
 * <p>The Bedrock model can be overridden via {@code -Dtest.bedrock.model=…} (see {@link
 * LlmIntegrationTestBase#MODEL}).
 *
 * <p>The Bedrock region can be overridden via {@code -Dtest.bedrock.region=…} (see {@link
 * LlmIntegrationTestBase#BEDROCK_REGION}).
 *
 * @see LlmIntegrationTestBase
 */
@CamundaSpringProcessTest
class SafeguardPromptClassificationIT extends LlmIntegrationTestBase {

    private static final Logger LOG =
            LoggerFactory.getLogger(SafeguardPromptClassificationIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path PROMPTS_DIR = Path.of("src/test/resources/prompts");
    private static final Pattern PROMPT_FILE_PATTERN =
            Pattern.compile("safeguard-(block|warn|allow)-(.+)\\.txt");

    // /** Number of tests to run before pausing. */
    // private static final int BATCH_SIZE = 4;
    //
    // /** Seconds to wait between batches to let the API rate-limit window reset. */
    // private static final int BATCH_COOLDOWN_SECONDS = 65;

    @TestFactory
    @Execution(ExecutionMode.CONCURRENT)
    List<DynamicTest> safeguardClassification() {
        // 1. Discover all prompt files, sorted by category then name
        Map<String, List<Path>> byCategory = discoverPromptFiles();

        Assertions.assertThat(byCategory)
                .as("Expected prompt files for at least one category in %s", PROMPTS_DIR)
                .isNotEmpty();

        // Flatten into an ordered list of (file, category) pairs
        List<PromptTestCase> allTests = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            String category = entry.getKey();
            for (Path file : entry.getValue()) {
                allTests.add(new PromptTestCase(file, category));
            }
        }

        LOG.info(
                "Discovered {} prompt test(s) across {} categor{}: {}",
                allTests.size(),
                byCategory.size(),
                byCategory.size() == 1 ? "y" : "ies",
                byCategory.keySet());

        // 2. Build a flat list of DynamicTests with cooldown entries between batches
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < allTests.size(); i++) {
            // Insert cooldown before every batch (except the first)
            // if (i > 0 && i % BATCH_SIZE == 0) {
            //     int batchNum = i / BATCH_SIZE;
            //     tests.add(
            //             DynamicTest.dynamicTest(
            //                     "⏳ cooldown before batch " + (batchNum + 1),
            //                     () -> {
            //                         LOG.info(
            //                                 "Waiting {}s for API rate-limit window to reset...",
            //                                 BATCH_COOLDOWN_SECONDS);
            //                         TimeUnit.SECONDS.sleep(BATCH_COOLDOWN_SECONDS);
            //                         LOG.info("Cooldown complete, resuming tests");
            //                     }));
            // }

            PromptTestCase tc = allTests.get(i);
            String displayName = "[" + tc.category + "] " + testDisplayName(tc.file);
            tests.add(DynamicTest.dynamicTest(displayName, () -> assertDecision(tc)));
        }

        return tests;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record PromptTestCase(Path file, String category) {}

    private static Map<String, List<Path>> discoverPromptFiles() {
        Map<String, List<Path>> byCategory = new TreeMap<>();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(PROMPTS_DIR, "safeguard-*.txt")) {
            for (Path file : stream) {
                Matcher m = PROMPT_FILE_PATTERN.matcher(file.getFileName().toString());
                if (m.matches()) {
                    byCategory.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan prompt directory " + PROMPTS_DIR, e);
        }
        return byCategory;
    }

    private static String testDisplayName(Path file) {
        Matcher m = PROMPT_FILE_PATTERN.matcher(file.getFileName().toString());
        if (m.matches()) {
            return m.group(2).replace('-', ' ');
        }
        return file.getFileName().toString();
    }

    private void assertDecision(PromptTestCase tc) {
        String promptFile = tc.file.getFileName().toString();
        LOG.info("▶ [{}] {}", tc.category, promptFile);

        String prompt = loadPrompt(promptFile);
        var processInstance = startSafeguardProcess(prompt);

        try {
            CamundaAssert.assertThat(processInstance)
                    .hasCompletedElements("Event_safeGuardResult")
                    .isCompleted();

            CamundaAssert.assertThat(processInstance)
                    .hasVariableSatisfies(
                            "safeGuardResult",
                            Map.class,
                            result ->
                                    Assertions.assertThat(result.get("decision"))
                                            .isEqualTo(tc.category));
            LOG.info("✓ [{}] {} — passed", tc.category, promptFile);
        } catch (AssertionError e) {
            LOG.error("✗ [{}] {} — FAILED: {}", tc.category, promptFile, e.getMessage());
            logProcessVariables(processInstance);
            throw e;
        }
    }

    private void logProcessVariables(ProcessInstanceEvent processInstance) {
        try {
            SearchResponse<Variable> result =
                    camundaClient
                            .newVariableSearchRequest()
                            .filter(
                                    f ->
                                            f.processInstanceKey(
                                                    processInstance.getProcessInstanceKey()))
                            .send()
                            .join();
            LOG.error("Process instance {} variables:", processInstance.getProcessInstanceKey());
            for (Variable v : result.items()) {
                String name = v.getName().toLowerCase();
                if (name.contains("systemprompt")
                        || name.equals("data")
                        || name.equals("userpromptsafeguarded")) continue;
                if (name.equals("safeguardresult")) {
                    try {
                        Object json = MAPPER.readValue(v.getValue(), Object.class);
                        LOG.error(
                                "  {} =\n{}",
                                v.getName(),
                                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json));
                    } catch (Exception ignored) {
                        LOG.error("  {} =(not pretty-printed) {}", v.getName(), v.getValue());
                    }
                } else {
                    LOG.error("  {} = {}", v.getName(), v.getValue());
                }
            }
        } catch (Exception ex) {
            LOG.warn("Could not fetch process variables for debugging: {}", ex.getMessage());
        }
    }
}
