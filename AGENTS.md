# Camunda Business Solution Firewall Agent

## Git Policy

- **NEVER run `git add`, `git commit`, `git push`, or any other git write command** unless explicitly instructed to do so by the user.
- The user controls staging and committing. Running `git add` from the terminal overwrites any staging decisions the user has made in the VS Code git pane.
- If you need to inform the user about files to commit, describe them in text — do not stage or commit them.

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

## BPMN & DMN Validation

- **BPMN files**: Every created or modified `.bpmn` file must be validated with [bpmnlint](https://github.com/bpmn-io/bpmnlint) before commit. Run `npx bpmnlint <file>.bpmn` and fix all reported errors and warnings.
- **DMN files**: Every created or modified `.dmn` file must be validated with [dmnlint](https://github.com/bpmn-io/dmnlint) before commit. Run `npx dmnlint <file>.dmn` and fix all reported errors and warnings

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

### System Prompt Sync Process

The system prompt exists in three locations that must always stay in sync:

1. **`camunda-artifacts/safeguard-systemprompt.txt`** — canonical plain-text source
2. **`camunda-artifacts/safeguard-systemprompt-feel.txt`** — FEEL string variant (escaped `\n`, `\"`, unicode escapes like `\u2014`)
3. **`camunda-artifacts/safeguard-agent.bpmn`** — embedded in `StartEvent_1`'s output mapping for variable `_systemPrompt` (XML-escaped FEEL: `\n`, `\&#34;`, `&#39;`, `\u2014`)

Whenever `safeguard-systemprompt.txt` is changed, the same change **must** be propagated in FEEL syntax to both `safeguard-systemprompt-feel.txt` and the `_systemPrompt` default value in `StartEvent_1` of `safeguard-agent.bpmn`.

To sync all three locations after editing the canonical prompt, run:

```bash
mvn compile exec:java
```

This executes `SyncPrompt` which escapes the plain-text prompt into a FEEL string literal, validates it via the FEEL-scala engine (round-trip evaluation), writes the FEEL file, and updates the BPMN embedding.

## Important Notes for AI Agents

- **Always run `mvn spotless:apply` before committing** to ensure code formatting compliance
- BPMN artifacts in `camunda-artifacts/` are bundled as resources during build
- Job type for AI agent connector: `io.camunda.agenticai:aiagent:1`
- Test helper methods available in `ProcessTestBase` for completing/failing AI jobs
- BpmnFile utility class allows string-level replacements for test BPMN deployment

