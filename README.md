# AI Firewall Agent

It takes a user prompt and safeguards against malicious intent.  
The process uses the Camunda AI Agent Connector to call the LLM and returns a structured JSON result via a FEEL expression — no external job worker required.

![AI Firewall Agent](camunda-artifacts/safeguard-agent.png)

## Prerequisites

- **Camunda 8.8+** - e.g. [c8run](https://docs.camunda.io/docs/self-managed/setup/deploy/local/c8run/) for local development, or a [SaaS](https://docs.camunda.io/docs/guides/create-cluster/) / Self-Managed cluster´

## Mandatory inputs

- `userPromptToSafeguard` (string) for user prompt; **default**: null
- `systemPrompt` (string) for system prompt; **default**: (see file `safeguard-systemprompt.txt`)

## Customizations

### Mandatory

- Open `safeguard-agent.bpmn` in [Camunda Modeler](https://modeler.cloud.camunda.io/) (Web or Desktop)
- Select the `Safeguard Prompt` task and configure:
  - **Model provider** — the AI provider to use (e.g. OpenAI, Azure OpenAI, Ollama, etc.)
  - **Model** — the specific model name (e.g. `gpt-4o`, `llama3`, etc.)
  - **API key / credentials** — as required by the chosen provider (typically via Connector secrets)

### Optional

- Adjust guardrail parameters (see [Guardrails](#guardrails) below) directly in the BPMN or via process variables.

## Guardrails

The Guardrails for the AI Firewall Agent are set via these Process Variables.  
You can supply them to the Process Instance or set them directly in `safeguard-agent.bpmn`:

- `maxTries` (int) for max allowed iterations over safeguard attempts; **default**: 3
- `minConfidence` (float, 0.00 .. 1.00): for minimal confidence to be a trusted decision; **default**: 0.95
- `maxUserPromptSize` (int): maximum character size for user prompt (== input); **default**: 2000000 (2 million)

### Confidence refinement loop

When the LLM returns a valid safeguard result but the confidence score falls below `minConfidence`, the process automatically:

1. **Retains the result history** – the current `safeGuardResult` is appended to `safeGuardResultHistory` for auditability.
2. **Refines the system prompt** – a `CONFIDENCE REFINEMENT DIRECTIVE` is appended to the system prompt, instructing the LLM to re-examine its assessment with deeper analysis and the previous result as context (see `safeguard-confidence-refinement.txt` for the template).
3. **Loops back** to the iteration check – if `_current_try` ≤ `_maxTries`, the refined prompt is sent again; otherwise the `safeguard_max-iterations-reached` escalation fires.

## JSON schema output

Process has the result variable `safeGuardResult` adhere to this schema:

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

## Camunda artifacts

The `/camunda-artifacts` directory contains:

| File | Purpose |
|------|---------|
| `safeguard-agent.bpmn` | **Required.** The main safeguard agent process. Deploy this to your cluster. |
| `safeguard-agent-usage-example.bpmn` | Optional. Example BPMN that calls the safeguard agent via a Call Activity. See [README-usage-example.md](camunda-artifacts/README-usage-example.md). |
| `safeguard-systemprompt.txt` | The system prompt used by the safeguard agent (plain text, for reference). |
| `safeguard-systemprompt-feel.txt` | The same system prompt as a FEEL-escaped string, ready to paste into a BPMN expression. |
| `safeguard-confidence-refinement.txt` | Template for the confidence refinement directive appended to the system prompt when confidence is below threshold. |

## Running

### 1. Customize and Deploy the BPMN to Camunda

Customize and deploy **at minimum** `safeguard-agent.bpmn` from `/camunda-artifacts` to your Camunda 8.8+ cluster.

No external job worker is needed — the process uses only the built-in AI Agent Connector and FEEL expressions.

### 2. Start a process instance

Start a process instance of `safeguard-agent` (or the usage-example process) with:

```json
{
  "userPromptToSafeguard": "What is the status of my insurance claim number IC-2024-001?"
}
```

## Running tests

### Requirements

- **Java 25** (JDK) — only for building and running tests locally
- **Maven 3.9+** — only for building and running tests locally
- **Docker** — required for running tests (Testcontainers)

### Unit / CPT tests

```bash
mvn test
```

Tests use [Camunda Process Test](https://docs.camunda.io/docs/apis-tools/testing/getting-started/) with Testcontainers and WireMock. A Docker runtime is required.

The build enforces:

- **60 %** BPMN path coverage (via `camunda-process-test`)
- **80 %** line coverage (via JaCoCo)

### Prompt Tests

Tests that validate prompt classification with real LLM calls.

`SafeguardPromptClassificationIT` sends actual user prompts through the safeguard-agent BPMN process using GitHub Models (openai/gpt-4.1-mini) via the Camunda Process Test connectors runtime.
Tests are auto-discovered from prompt files — grouped by expected decision (`block`, `warn`, `allow`).

| Aspect | Detail |
|--------|--------|
| **Naming convention** | `*IT.java` suffix → Maven Failsafe plugin |
| **Base class** | `LlmIntegrationTestBase` |
| **LLM provider** | [GitHub Models](https://models.github.ai/inference) — `openai/gpt-4.1-mini` |
| **Auth** | `GITHUB_TOKEN` env var (in CI: `permissions: models: read`) |
| **Speed** | 10-30 s per prompt (real HTTP calls) |

#### Running locally

```bash
export GITHUB_TOKEN=ghp_...
mvn failsafe:integration-test -B
```

If `GITHUB_TOKEN` is not set, tests are automatically skipped.

#### Adding new test cases

Drop a text file into `src/test/resources/prompts/` following the naming convention:

```shell
safeguard-<category>-<name>.txt
```

where `<category>` is one of `block`, `warn`, or `allow`.

Examples:

- `safeguard-block-sqli.txt` — prompt that should be **blocked**
- `safeguard-warn-suspicious.txt` — prompt that should trigger a **warning**
- `safeguard-allow-greeting.txt` — safe prompt that should be **allowed**

No code changes required — the test factory discovers new files automatically.

#### minConfidence setting

LLM integration tests set `minConfidence: 0.5` (lower than production default of 0.95) to avoid retry loops during testing. Assertions target the `decision` value, not confidence.

#### Coverage

Integration test coverage is tracked separately in `jacoco-it.exec`, then merged with unit test coverage in `jacoco-merged.exec`. This is already configured in `pom.xml`.
