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
