package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM integration tests for safeguard prompt classification.
 *
 * <p>These tests send real user prompts through the safeguard-agent BPMN process using GitHub
 * Models (gpt-4o-mini) via the Camunda Process Test connectors runtime. Each test validates that
 * the LLM correctly classifies prompts as {@code allow}, {@code warn}, or {@code block}.
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

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Block Decisions                                                         ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Test
    @DisplayName("Blocks prompt injection attempt")
    void blocksPromptInjection() {
        assertDecision("safeguard-block-injection.txt", "block");
    }

    @Test
    @DisplayName("Blocks jailbreak attempt")
    void blocksJailbreakAttempt() {
        assertDecision("safeguard-block-jailbreak.txt", "block");
    }

    @Test
    @DisplayName("Blocks harmful intent")
    void blocksHarmfulIntent() {
        assertDecision("safeguard-block-harmful.txt", "block");
    }

    @Test
    @DisplayName("Blocks data exfiltration attempt")
    void blocksDataExfiltration() {
        assertDecision("safeguard-block-exfiltration.txt", "block");
    }

    @Test
    @DisplayName("Blocks tool manipulation attempt")
    void blocksToolManipulation() {
        assertDecision("safeguard-block-toolmanipulation.txt", "block");
    }

    @Test
    @DisplayName("Blocks threatening message")
    void blocksThreateningMessage() {
        assertDecision("safeguard-block-threat.txt", "block");
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Warn Decisions                                                          ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Test
    @DisplayName("Warns on Base64 obfuscation")
    void warnsOnBase64Obfuscation() {
        assertDecision("safeguard-warn-base64.txt", "warn");
    }

    @Test
    @DisplayName("Warns on roleplay evasion")
    void warnsOnRoleplayEvasion() {
        assertDecision("safeguard-warn-roleplay.txt", "warn");
    }

    @Test
    @DisplayName("Warns on educational prefix evasion")
    void warnsOnEducationalPrefixEvasion() {
        assertDecision("safeguard-warn-evasion.txt", "warn");
    }

    @Test
    @DisplayName("Warns on mixed obfuscation")
    void warnsOnMixedObfuscation() {
        assertDecision("safeguard-warn-mixed.txt", "warn");
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Allow Decisions                                                         ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Test
    @DisplayName("Allows insurance claim inquiry")
    void allowsInsuranceClaimInquiry() {
        assertDecision("safeguard-safeprompt.txt", "allow");
    }

    @Test
    @DisplayName("Allows loan application")
    void allowsLoanApplication() {
        assertDecision("safeguard-allow-loan.txt", "allow");
    }

    @Test
    @DisplayName("Allows product return request")
    void allowsProductReturn() {
        assertDecision("safeguard-allow-return.txt", "allow");
    }

    @Test
    @DisplayName("Allows general knowledge question")
    void allowsGeneralKnowledge() {
        assertDecision("safeguard-allow-knowledge.txt", "allow");
    }

    // ── helper ──────────────────────────────────────────────────────────────

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
                        result -> {
                            // Ensure decision field is present
                            Assertions.assertThat(result)
                                    .withFailMessage(
                                            "safeGuardResult must contain 'decision' field. Actual"
                                                    + " result: %s",
                                            result)
                                    .containsKey("decision");

                            Object decision = result.get("decision");

                            // Validate decision is one of the three valid values
                            Assertions.assertThat(decision)
                                    .withFailMessage(
                                            "decision must be one of 'allow', 'warn', or 'block'."
                                                    + " Actual: '%s'. Full result: %s",
                                            decision, result)
                                    .isIn("allow", "warn", "block");

                            // Assert expected value
                            Assertions.assertThat(decision)
                                    .withFailMessage(
                                            "Expected decision '%s' but got '%s' for prompt file:"
                                                    + " %s. Full result: %s",
                                            expectedDecision, decision, promptFile, result)
                                    .isEqualTo(expectedDecision);
                        });
    }
}
