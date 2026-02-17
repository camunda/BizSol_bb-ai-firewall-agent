package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Nested
    @DisplayName("Block Decisions")
    class BlockDecisions {

        @Test
        @DisplayName("Blocks prompt injection attempt")
        void blocksPromptInjection() {
            String prompt = loadPrompt("safeguard-block-injection.txt");
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
                                            .isEqualTo("block"));
        }

        @Test
        @DisplayName("Blocks jailbreak attempt")
        void blocksJailbreakAttempt() {
            String prompt = loadPrompt("safeguard-block-jailbreak.txt");
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
                                            .isEqualTo("block"));
        }

        @Test
        @DisplayName("Blocks harmful intent")
        void blocksHarmfulIntent() {
            String prompt = loadPrompt("safeguard-block-harmful.txt");
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
                                            .isEqualTo("block"));
        }

        @Test
        @DisplayName("Blocks data exfiltration attempt")
        void blocksDataExfiltration() {
            String prompt = loadPrompt("safeguard-block-exfiltration.txt");
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
                                            .isEqualTo("block"));
        }

        @Test
        @DisplayName("Blocks tool manipulation attempt")
        void blocksToolManipulation() {
            String prompt = loadPrompt("safeguard-block-toolmanipulation.txt");
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
                                            .isEqualTo("block"));
        }

        @Test
        @DisplayName("Blocks threatening message")
        void blocksThreateningMessage() {
            String prompt = loadPrompt("safeguard-block-threat.txt");
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
                                            .isEqualTo("block"));
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Warn Decisions                                                          ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Warn Decisions")
    class WarnDecisions {

        @Test
        @DisplayName("Warns on Base64 obfuscation")
        void warnsOnBase64Obfuscation() {
            String prompt = loadPrompt("safeguard-warn-base64.txt");
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
                                            .isEqualTo("warn"));
        }

        @Test
        @DisplayName("Warns on roleplay evasion")
        void warnsOnRoleplayEvasion() {
            String prompt = loadPrompt("safeguard-warn-roleplay.txt");
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
                                            .isEqualTo("warn"));
        }

        @Test
        @DisplayName("Warns on educational prefix evasion")
        void warnsOnEducationalPrefixEvasion() {
            String prompt = loadPrompt("safeguard-warn-evasion.txt");
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
                                            .isEqualTo("warn"));
        }

        @Test
        @DisplayName("Warns on mixed obfuscation")
        void warnsOnMixedObfuscation() {
            String prompt = loadPrompt("safeguard-warn-mixed.txt");
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
                                            .isEqualTo("warn"));
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════╗
    // ║ Allow Decisions                                                         ║
    // ╚══════════════════════════════════════════════════════════════════════════╝

    @Nested
    @DisplayName("Allow Decisions")
    class AllowDecisions {

        @Test
        @DisplayName("Allows insurance claim inquiry")
        void allowsInsuranceClaimInquiry() {
            String prompt = loadPrompt("safeguard-safeprompt.txt");
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
                                            .isEqualTo("allow"));
        }

        @Test
        @DisplayName("Allows loan application")
        void allowsLoanApplication() {
            String prompt = loadPrompt("safeguard-allow-loan.txt");
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
                                            .isEqualTo("allow"));
        }

        @Test
        @DisplayName("Allows product return request")
        void allowsProductReturn() {
            String prompt = loadPrompt("safeguard-allow-return.txt");
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
                                            .isEqualTo("allow"));
        }

        @Test
        @DisplayName("Allows general knowledge question")
        void allowsGeneralKnowledge() {
            String prompt = loadPrompt("safeguard-allow-knowledge.txt");
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
                                            .isEqualTo("allow"));
        }
    }
}
