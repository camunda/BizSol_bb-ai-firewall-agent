# AI Firewall Agent

It takes a user prompt and safeguards against malicious intent.  
A job worker transforms the Agent output to a proper JSON schema structure (see below).

## Prerequisites

- **Java 25** (JDK)
- **Maven 3.9+**
- **Camunda 8.8+** - e.g. [c8run](https://docs.camunda.io/docs/self-managed/setup/deploy/local/c8run/) for local development, or a [SaaS](https://docs.camunda.io/docs/guides/create-cluster/) / Self-Managed cluster
- **Docker & Docker Compose** (only if running via Docker)

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

- Set the host port for the job worker via a variable in `.env`.  
  Copy `.env.example` to `.env` and adjust:

  ```bash
  cp .env.example .env
  ```

  Default port: `9090`

- If the targeted Camunda Version is >= `8.9`, the JSON converter worker can be substituted with the [`FEEL` expression `to json(value: Any)`](https://docs.camunda.io/docs/next/components/modeler/feel/builtin-functions/feel-built-in-functions-conversion/#to-jsonvalue)

## Guardrails

The Guardrails for the AI Firewall Agent are set via these Process Variables.  
You can supply them to the Process Instance or set them directly in `safeguard-agent.bpmn`:

- `maxTries` (int) for max allowed iterations over safeguard attempts; **default**: 3
- `minConfidence` (float, 0.00 .. 1.00): for minimal confidence to be a trusted decision; **default**: 0.95
- `maxUserPromptSize` (int): maximum character size for user prompt (== input); **default**: 2000000 (2 million)

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

## Running

### 1. Deploy the BPMN to Camunda

Deploy **at minimum** `safeguard-agent.bpmn` from `/camunda-artifacts` to your Camunda 8.8+ cluster.

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
```

## Running tests

```bash
mvn test
```

Tests use [Camunda Process Test](https://docs.camunda.io/docs/apis-tools/testing/getting-started/) with Testcontainers and WireMock. A Docker runtime is required.

The build enforces:

- **60 %** BPMN path coverage (via `camunda-process-test`)
- **80 %** line coverage (via JaCoCo)
