# Safeguard Agent Usage Example

This directory contains a minimal BPMN example demonstrating how to use the `safeguard-agent.bpmn` process via a Call Activity.

## Files

- **safeguard-agent-usage-example.bpmn** - Example BPMN that calls the safeguard-agent
- **safeguard-agent.bpmn** - The main safeguard agent process
- **safeguard-systemprompt.txt** - System prompt for the safeguard agent

## Usage Example

The `safeguard-agent-usage-example.bpmn` demonstrates the minimal configuration needed to use the safeguard agent:

### Process Flow

1. **Start Event** - Begins the process
2. **Call Activity** - Calls the `safeguard-agent` process
3. **End Event** - Completes with the safeguard result

### Input Variables

The example requires only the essential variable:

- **userPromptToSafeguard** (required): The user prompt to be analyzed
  - Example: `"What is the status of my insurance claim number IC-2024-001?"`

**Note:** The `systemPrompt` variable is optional and defaults to a comprehensive prompt guard template embedded in the safeguard-agent process (see `safeguard-systemprompt-feel.txt`). The example demonstrates the minimal configuration by not passing this variable.

### Output Variables

The Call Activity maps only the essential output:

- **safeGuardResult**: Contains the safeguard analysis result as a JSON object

### Expected Output (Happy Path)

For a benign prompt like the example, you can expect:

```json
{
  "decision": "allow",
  "risk_labels": [],
  "reasons": ["Benign request for claim status information."],
  "evidence": [],
  "sanitized_prompt": "",
  "normalizations_applied": [],
  "confidence": 0.92
}
```

## How to Deploy and Run

1. Deploy both BPMN files to your Camunda 8 cluster:
   - `safeguard-agent.bpmn`
   - `safeguard-agent-usage-example.bpmn`

2. Start a process instance of `safeguard-agent-usage-example` with the minimal required variable:
   ```json
   {
     "userPromptToSafeguard": "What is the status of my insurance claim number IC-2024-001?"
   }
   ```

3. The process will complete with the `safeGuardResult` variable containing the analysis.

## Advanced Configuration

The safeguard-agent supports additional optional parameters that can be passed via the Call Activity:

- **systemPrompt** (default: content from `safeguard-systemprompt.txt`): Custom system prompt for the AI agent
- **maxTries** (default: 3): Maximum retry attempts for safeguard analysis
- **minConfidence** (default: 0.95): Minimum confidence level required
- **maxUserPromptSize** (default: 2000000): Maximum characters in user prompt

These are intentionally omitted from the example to keep it minimal and demonstrate that only `userPromptToSafeguard` is required. Add them to the Call Activity's input mapping only if you need to override the defaults.

## Integration Notes

- The Call Activity uses `propagateAllChildVariables="false"` to prevent variable leakage
- Only explicitly mapped variables are passed to and from the safeguard-agent
- This ensures clean separation between the calling process and the safeguard agent
