# AI Firewall Agent

This repository contains a Camunda building block that evaluates an incoming user prompt before it reaches an AI-powered task. It helps you detect unsafe prompts and return a structured decision your process can act on.

![AI Firewall Agent](camunda-artifacts/safeguard-agent.png)

## What problem this building block solves
User prompts can contain malicious or unsafe instructions. In an AI-powered workflow, that can lead to prompt injection, manipulated agent behavior, or unsafe downstream actions.

## Where to use this building block
Use the AI Firewall Agent when:
- User input is passed to an AI task
- External users interact with an AI-powered workflow
- AI-generated decisions trigger automated actions
- Sensitive systems are connected downstream

A common pattern is to place this building block directly before the AI task or call activity that uses the prompt.

## How it works
- Your process passes a user prompt into the safeguard-agent process.
- The firewall evaluates the prompt with your configured AI provider and model.
- If the result is valid but confidence is below `minConfidence`, the process stores the previous result, refines the system prompt, and retries until `maxTries` is reached.
- The process returns `safeGuardResult`, which your workflow can use to continue, warn, block, escalate, or sanitize the prompt before reuse.

## Repository contents
The `camunda-artifacts` directory contains the main BPMN and reference files for the building block:

| File | Purpose |
|------|---------|
| `safeguard-agent.bpmn` | **Required.** Main firewall process to deploy to your Camunda cluster. |
| `safeguard-agent-usage-example.bpmn` | Example BPMN that calls the safeguard agent via a Call Activity. See [example](camunda-artifacts/README-usage-example.md) (Optional)  |
| `safeguard-systemprompt.txt` | Reference system prompt used by the firewall. |
| `safeguard-systemprompt-feel.txt` | FEEL-escaped version of the system prompt for BPMN expressions. |
| `safeguard-confidence-refinement.txt` | Directive appended when confidence is too low and the process retries. |

## Prerequisites
- **Java 25** (JDK)
- **Maven 3.9+**
- **Camunda 8.8+** - e.g. [c8run](https://docs.camunda.io/docs/self-managed/setup/deploy/local/c8run/) for local development, or a [SaaS](https://docs.camunda.io/docs/guides/create-cluster/) / Self-Managed cluster
- **Docker and Docker Compose** to test using processes using Docker

## Quick Start
Deploy and configure the building block

- Customize `safeguard-agent.bpmn` and deploy to your Camunda 8.8+ cluster.
- Select the `Safeguard Prompt` task and configure:
  - **Model provider** — the AI provider to use (e.g. OpenAI, Azure OpenAI, Ollama, etc.)
  - **Model** — the specific model name (e.g. `gpt-4o`, `llama3`, etc.)
  - **API key / Credentials** — as required by the chosen provider (typically via Connector secrets)

Start a process instance
For the minimal happy path, start the process with a prompt like this:

```json
{
  "userPromptToSafeguard": "What is the status of my insurance claim number IC-2024-001?"
}
```

## Inputs and configuration

### Required

| Variable | Description | Default |
|----------|-------------|---------|
| `userPromptToSafeguard`| (string) the user prompt to evaluate | null |
| `systemPrompt`| (string) for system prompt | (see file `safeguard-systemprompt.txt`) |

### Optional Guardrails

The guardrails for the AI Firewall Agent are set via these process variables.  
You can supply them to the Process Instance or set them directly in `safeguard-agent.bpmn`:

| Variable | Description | Default |
|----------|-------------|---------|
| `systemPrompt` | Embedded pre-configured prompt | Override the firewall instructions used for analysis |
| `maxTries`| `3` | Maximum safeguard attempts before escalation |
| `minConfidence` | `0.95` | Minimum confidence required to trust the decision |
| `maxUserPromptSize` | `2000000` | Maximum allowed prompt size in characters |

<!-- - Set the host port for the job worker via a variable in `.env`.  
  Copy `.env.example` to `.env` and adjust:

  ```bash
  cp .env.example .env
  ```

  Default port: `9090`

- If the targeted Camunda Version is >= `8.9`, the JSON converter worker can be substituted with the [`FEEL` expression `to json(value: Any)`](https://docs.camunda.io/docs/next/components/modeler/feel/builtin-functions/feel-built-in-functions-conversion/#to-jsonvalue) -->

<!-- ## Guardrails

The Guardrails for the AI Firewall Agent are set via these Process Variables.  
You can supply them to the Process Instance or set them directly in `safeguard-agent.bpmn`:

- `maxTries` (int) for max allowed iterations over safeguard attempts; **default**: 3
- `minConfidence` (float, 0.00 .. 1.00): for minimal confidence to be a trusted decision; **default**: 0.95
- `maxUserPromptSize` (int): maximum character size for user prompt (== input); **default**: 2000000 (2 million) -->

### Confidence refinement loop

When the LLM returns a valid safeguard result but the confidence score falls below `minConfidence`, the process automatically:

1. **Retains the result history** – the current `safeGuardResult` is appended to `safeGuardResultHistory` for auditability.
2. **Refines the system prompt** – a `CONFIDENCE REFINEMENT DIRECTIVE` is appended to the system prompt, instructing the LLM to re-examine its assessment with deeper analysis and the previous result as context (see `safeguard-confidence-refinement.txt` for the template).
3. **Loops back** to the iteration check – if `_current_try` ≤ `_maxTries`, the refined prompt is sent again; otherwise the `safeguard_max-iterations-reached` escalation fires.

## Output

The process writes its result to the `safeGuardResult` variable using this schema:

```json
{
  "decision": "allow | warn | block",
  "risk_labels": [
    "injection | jailbreak | harmful_intent | policy_evasion | sensitive_data | privacy | obfuscation | tool_manipulation | other"
  ],
  "reasons": [
    "Short, concrete bullets explaining the key risks or the absence of them."
  ],
  "evidence": [
    "Exact quoted spans from the user prompt that support each reason."
  ],
  "sanitized_prompt": "If decision is warn or block, provide a single revised version of the user prompt with unsafe directives removed or neutralized while preserving legitimate intent. For clearly safe 'allow' cases where a rewrite is unnecessary, return an empty string.",
  "normalizations_applied": [
    "List of normalization steps performed (e.g., removed zero-width chars, decoded URL-encoding)."
  ],
  "confidence": 0.0
}
```

### What the decision means
`allow` — The prompt is safe to continue as-is.
`warn` — The prompt has issues, but you may continue with additional review or a sanitized version.
`block` — The prompt should not continue downstream.


### Error handling and escalations

The building block can escalate when the prompt is too large, the model output is invalid, retries are exhausted, or the AI task fails.

Common escalation paths include:

`safeguard_max-user-input-exceeded`
`safeguard_max-iterations-reached`
`safeguard_task-agent-failed`
`safeguard_json-worker-error`
`safeguard_bad-agent-output`

The usage example catches these escalations and converts them into BPMN errors so operators can review failures



<!-- 
## Running

### 1. Customize and Deploy the BPMN to Camunda

Customize and Deploy **at minimum** `safeguard-agent.bpmn` from `/camunda-artifacts` to your Camunda 8.8+ cluster.

### 2. Connect the Job Worker to the Camunda cluster

The application in `/src/main/java/io/camunda/bizsol/bb/ai_firewall_agent/AIFirewallAgentApplication.java` needs to to connect to the Camunda cluster. By default (with no config), the underyling Camunda SDK connects to `localhost:26500` (gRPC) and `localhost:8080` (REST), which matches a local **c8run** setup.

To point at a different cluster, set this environment variable before starting the application:

```bash
export CAMUNDA_CLIENT_REST_ADDRESS=http://localhost:8080
```

or modify `/src/main/resources/application.yaml` directly.

For **Camunda SaaS**, follow the [Spring Zeebe client configuration docs](https://docs.camunda.io/docs/apis-tools/spring-zeebe-sdk/getting-started/).

### 3. Start the Job Worker application

Choose one of:

- **Maven** (directly):

  ```bash
  mvn spring-boot:run
  ```

  The application starts on port **9090** (configured in `src/main/resources/application.yaml`).

- **Docker Compose**:

  ```bash
  docker-compose -f docker-compose.ai-firewall-agent.yaml up
  ```

  Exposes port **9090** on the host (override via `AI_FIREWALL_AGENT_PORT` in `.env`).

### 4. Start a process instance

Start a process instance of `safeguard-agent` (or the usage-example process) with:

```json
{
  "userPromptToSafeguard": "What is the status of my insurance claim number IC-2024-001?"
}
``` -->

## Running tests

```bash
mvn test
```

Tests use [Camunda Process Test](https://docs.camunda.io/docs/apis-tools/testing/getting-started/) with Testcontainers and WireMock and require Docker

The build enforces:
- **60 %** BPMN path coverage (via `camunda-process-test`)
- **80 %** line coverage (via JaCoCo)

### For contributors
There is also a work-in-progress set of LLM integration tests that send real prompts through the process using GitHub Models. These tests use GITHUB_TOKEN, are slower than unit/process tests, and are skipped automatically when the token is not set.



## (WIP) LLM Integration Tests (IT)

⚠️ This is a work in progress and not finished yet - look, don't touch :)

End-to-end integration tests that validate prompt classification with real LLM calls.

LLM integration tests (`*IT.java` files) send actual user prompts through the safeguard-agent BPMN process using GitHub Models (gpt-4o-mini) via the Camunda Process Test connectors runtime. These tests verify that the LLM correctly classifies prompts as `allow`, `warn`, or `block`.

### Key Differences from Unit/CPT Tests

- **Naming convention**: `*IT.java` suffix (e.g., `SafeguardPromptClassificationIT.java`)
- **Test runner**: Maven Failsafe plugin (not Surefire)
- **Base class**: `LlmIntegrationTestBase` (not `ProcessTestBase`)
- **Execution**: Real HTTP calls to GitHub Models API (slow: 10-30s per test)
- **Configuration**: Connectors runtime enabled (`camunda.process-test.connectors-enabled=true`)

### LLM Provider

Tests use **GitHub Models** (openai/gpt-4.1-mini) with authentication via `GITHUB_TOKEN`:

- **Endpoint**: `https://models.github.ai/inference`
- **Model**: `openai/gpt-4.1-mini`
- **Authentication**: `GITHUB_TOKEN` environment variable — in CI, the workflow must declare `permissions: models: read` so the default token can access GitHub Models
- **Local testing**: Set `GITHUB_TOKEN` env var to a PAT with `models:read` permission

### Running Locally

```bash
# Set your GitHub PAT (with models:read permission) and run
export GITHUB_TOKEN=ghp_...
mvn failsafe:integration-test -B
```

If `GITHUB_TOKEN` is not set, tests are automatically skipped.

### Running in CI

The GitHub Actions workflow grants `models: read` permission to the default `GITHUB_TOKEN`, which enables access to the GitHub Models inference API — no extra secrets required:

```yaml
jobs:
  test:
    permissions:
      contents: read
      models: read
    steps:
      - name: Run LLM integration tests
        env:
          GITHUB_TOKEN: ${{ github.token }}
        run: mvn failsafe:integration-test failsafe:verify -B -q
```

### Adding New Prompt Test Cases

1. **Create a prompt file** in `src/test/resources/prompts/` following the naming convention:
   - `safeguard-block-{shortname}.txt` for prompts that should be blocked
   - `safeguard-warn-{shortname}.txt` for prompts that should trigger warnings
   - `safeguard-allow-{shortname}.txt` for safe prompts

2. **Add a test method** in `SafeguardPromptClassificationIT.java`:
   ```java
   @Test
   @DisplayName("Test description")
   void testMethodName() {
       String prompt = loadPrompt("safeguard-{decision}-{shortname}.txt");
       var processInstance = startSafeguardProcess(prompt);
       
       CamundaAssert.assertThat(processInstance)
               .hasCompletedElements("Event_safeGuardResult")
               .isCompleted();
       
       CamundaAssert.assertThat(processInstance)
               .hasVariableSatisfies("safeGuardResult", Map.class,
                       result -> Assertions.assertThat(result.get("decision"))
                               .isEqualTo("allow|warn|block"));
   }
   ```

### minConfidence Setting

LLM integration tests set `minConfidence: 0.5` (lower than production default of 0.95) to avoid retry loops during testing. The LLM may return varying confidence levels (0.7-0.99), and a low threshold ensures the process doesn't retry, which would make tests slow and flaky. Assertions target the `decision` value, not confidence.

### Coverage

Integration test coverage is tracked separately in `jacoco-it.exec`, then merged with unit test coverage in `jacoco-merged.exec`. This is already configured in `pom.xml`.
