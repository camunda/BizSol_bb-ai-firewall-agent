# JSON Converter Job Worker

A minimal Camunda 8.8 Job Worker that converts JSON strings to JSON objects.

## Job Worker Type
`json-converter`

## Input
- `jsonString`: A stringified JSON object

## Output
- `result`: The parsed JSON object

## Error Handling

If the JSON string is invalid, the worker will throw a `RuntimeException` and log the error, causing the job to fail in Camunda.

