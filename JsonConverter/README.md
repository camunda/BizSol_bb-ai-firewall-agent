# JSON Converter Job Worker

A minimal Camunda 8.8 Job Worker that converts JSON strings to JSON objects.

## Job Worker Type
`json-converter`

## Input
- `jsonString`: A stringified JSON object

## Output
- `result`: The parsed JSON object

## Configuration

Update `src/main/resources/application.properties` with your Camunda 8 credentials:
```properties
zeebe.client.cloud.region=your-region
zeebe.client.cloud.cluster-id=your-cluster-id
zeebe.client.cloud.client-id=your-client-id
zeebe.client.cloud.client-secret=your-client-secret
```

For local Zeebe broker, use:
```properties
zeebe.client.broker.gateway-address=localhost:26500
zeebe.client.security.plaintext=true
```

## Build and Run

```bash
mvn clean install
mvn spring-boot:run
```

## Error Handling

If the JSON string is invalid, the worker will throw a `RuntimeException` and log the error, causing the job to fail in Camunda.

