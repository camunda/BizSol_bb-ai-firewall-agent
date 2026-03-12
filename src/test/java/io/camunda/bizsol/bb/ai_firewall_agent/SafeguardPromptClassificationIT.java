package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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
 * <h3>Test execution</h3>
 *
 * <ul>
 *   <li>Maven: {@code mvn failsafe:integration-test} (requires {@code GITHUB_TOKEN})
 *   <li>CI: Runs automatically with {@code GITHUB_TOKEN} from GitHub Actions
 *   <li>Naming: {@code *IT.java} suffix triggers Maven Failsafe plugin
 * </ul>
 *
 * @see LlmIntegrationTestBase
 */
@CamundaSpringProcessTest
class SafeguardPromptClassificationIT extends LlmIntegrationTestBase {

    private static final Path PROMPTS_DIR = Path.of("src/test/resources/prompts");
    private static final Pattern PROMPT_FILE_PATTERN =
            Pattern.compile("safeguard-(block|warn|allow)-(.+)\\.txt");

    @TestFactory
    Collection<DynamicContainer> safeguardClassification() {
        Map<String, List<Path>> byCategory = discoverPromptFiles();

        Assertions.assertThat(byCategory)
                .as("Expected prompt files for at least one category in %s", PROMPTS_DIR)
                .isNotEmpty();

        List<DynamicContainer> containers = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<DynamicTest> tests =
                    entry.getValue().stream()
                            .map(
                                    file -> {
                                        String name = testDisplayName(file);
                                        return DynamicTest.dynamicTest(
                                                name,
                                                () ->
                                                        assertDecision(
                                                                file.getFileName().toString(),
                                                                category));
                                    })
                            .toList();
            containers.add(DynamicContainer.dynamicContainer(category, tests));
        }
        return containers;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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

    private void assertDecision(String promptFile, String expectedDecision) {
        String prompt = loadPrompt(promptFile);
        var processInstance = startSafeguardProcess(prompt);

        CamundaAssert.assertThat(processInstance)
                .hasCompletedElements("Event_safeGuardResult")
                .isCompleted();

        CamundaAssert.assertThat(processInstance)
                .hasVariableSatisfies(
                        "safeGuardResult",
                        Map.class,
                        result ->
                                Assertions.assertThat(result.get("decision"))
                                        .isEqualTo(expectedDecision));
    }
}
