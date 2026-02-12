# Camunda Business Solution Communication Agent

## Commit Message Guidelines

Follow conventions in COMMIT-MESSAGE-GUIDELINE.md

## Implementation Details

- prefer functional programming over OOP where reasonable

- for Unit- and Integration Tests, use Camunda Process Test with Testcontainers. Consult camunda/camunda/tree/main/ as reference. Documentation is at <https://docs.camunda.io/docs/apis-tools/testing/getting-started/>
- always run all tests as a final Quality Gate

- when Connectors are in use in a BPMN, refer to camunda/connectors for usage and testing examples
