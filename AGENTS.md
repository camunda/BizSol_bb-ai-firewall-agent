# Camunda Business Solution Communication Agent

## Commit Message Guidelines

Follow conventions in COMMIT-MESSAGE-GUIDELINE.md

## Implementation Details

- prefer functional programming over OOP where reasonable
- when Connectors are in use in a BPMN, refer to camunda/connectors for usage and testing example

## Project Architecture & Structure

- **Language & Framework**: Spring Boot 3.5 with Java 25
- **Process Engine**: Camunda 8.8 Spring Boot Starter for BPMN process automation
- **Package Structure**: `io.camunda.bizsol.bb.ai_firewall_agent` with layered architecture:
  - `workers/` - Job worker components (annotated with `@Component`)
  - `services/` - Business logic services (annotated with `@Service`)
  - `models/` - Data models and DTOs
- **Architectural Rules**: ArchUnit enforces strict boundaries (workers → services → models)

## Key Technologies & Testing Frameworks

- **Testing**:
  - Camunda Process Test with Spring integration (`@CamundaProcessTest`, `@SpringBootTest`) and Testcontainers
  - Consult camunda/camunda/tree/main/ as reference. Documentation is at <https://docs.camunda.io/docs/apis-tools/testing/getting-started/>
- **Mocking**: WireMock for REST endpoint simulation
- **Coverage**: JaCoCo with enforced thresholds (unit tests: 80%, CPT: 60%)
- **Formatting**: Spotless with Google Java Format (AOSP style) - enforced at compile phase

## Testing Requirements

- Extend `ProcessTestBase` for process integration tests
- Use `CamundaProcessTestContext` for completing/failing jobs in tests
- Mock external AI agent connectors with WireMock
- Test resources location: `src/test/resources/`
- Coverage thresholds enforced at build time via `mvn verify`

## Code Quality & Standards

- **Formatting**: Code must pass `mvn spotless:check` before commit
- **Workers**: Annotate with `@Component` and use `@JobWorker` for job handlers
- **Services**: Annotate with `@Service` (follow naming convention: `*Service`)
- **Logging**: Use SLF4J logger pattern: `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`

## Build & Run Commands

```bash
# Full build with tests, coverage, and formatting
mvn clean verify

# Auto-format code
mvn spotless:apply

# Run application locally
mvn spring-boot:run
```

## BPMN Resources

- Process Definitions: Located in camunda-artifacts/ directory
- Main Process: safeguard-agent.bpmn (Process ID: safeguard-agent)
- System Prompt Template: safeguard-systemprompt.txt

## Important Notes for AI Agents

- **Always run `mvn spotless:apply` before committing** to ensure code formatting compliance
- BPMN artifacts in `camunda-artifacts/` are bundled as resources during build
- Job type for AI agent connector: `io.camunda.agenticai:aiagent:1`
- Test helper methods available in `ProcessTestBase` for completing/failing AI jobs
- BpmnFile utility class allows string-level replacements for test BPMN deployment

## LLM Integration Tests (IT)

End-to-end integration tests that validate prompt classification with real LLM calls.

### Purpose

LLM integration tests (`*IT.java` files) send actual user prompts through the safeguard-agent BPMN process using GitHub Models (gpt-4o-mini) via the Camunda Process Test connectors runtime. These tests verify that the LLM correctly classifies prompts as `allow`, `warn`, or `block`.

### Key Differences from Unit/CPT Tests

- **Naming convention**: `*IT.java` suffix (e.g., `SafeguardPromptClassificationIT.java`)
- **Test runner**: Maven Failsafe plugin (not Surefire)
- **Base class**: `LlmIntegrationTestBase` (not `ProcessTestBase`)
- **Execution**: Real HTTP calls to GitHub Models API (slow: 10-30s per test)
- **Configuration**: Connectors runtime enabled (`camunda.process-test.connectors-enabled=true`)

### LLM Provider

Tests use **GitHub Models** (gpt-4o-mini) with authentication via `GITHUB_TOKEN`:

- **Endpoint**: `https://models.inference.ai.github.com/v1`
- **Model**: `gpt-4o-mini`
- **Authentication**: `GITHUB_TOKEN` environment variable (available by default in GitHub Actions)
- **Local testing**: Use `LLM_API_KEY` env var with any OpenAI-compatible API key

### Running Locally

```bash
# With GitHub token
GITHUB_TOKEN=ghp_... mvn failsafe:integration-test -B

# With any OpenAI-compatible API key
LLM_API_KEY=sk-... mvn failsafe:integration-test -B

# Or set in environment and run
export GITHUB_TOKEN=ghp_...
mvn failsafe:integration-test -B
```

If neither `GITHUB_TOKEN` nor `LLM_API_KEY` is set, tests are automatically skipped.

### Running in CI

GitHub Actions automatically provides `GITHUB_TOKEN` in all workflow runs. The workflow explicitly sets it for the LLM integration test step:

```yaml
- name: Run LLM integration tests
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
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
