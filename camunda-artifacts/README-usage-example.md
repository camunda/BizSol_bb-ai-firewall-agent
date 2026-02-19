# Safeguard Agent Usage Example

This directory contains a minimal BPMN example demonstrating how to use the `safeguard-agent.bpmn` process via a Call Activity.


## Files

- **safeguard-agent-usage-example.bpmn** - Example BPMN that calls the safeguard-agent
- **safeguard-agent.bpmn** - The main safeguard agent process
- **safeguard-systemprompt.txt** - System prompt for the safeguard agent
- **safeguard-systemprompt-feel.txt**: the same system prompt as a FEEL-escaped string, ready to paste into a BPMN expression.

## Usage Example

The `safeguard-agent-usage-example.bpmn` demonstrates the minimal configuration needed to use the safeguard agent:

### Process Flow

1. **Start Event** - Begins the process
2. **Call Activity** - Calls the `safeguard-agent` process
3. **End Event** - Completes with the safeguard result

### Input Variables

The Call Activity maps both required and optional variables:

- **userPromptToSafeguard** (required): The user prompt to be analyzed
  - Example: `"What is the status of my insurance claim number IC-2024-001?"`
- **minConfidence** (optional): Minimum confidence level required; **default**: 0.95
- **maxTries** (optional): Maximum retry attempts for safeguard analysis; **default**: 3
- **maxUserPromptSize** (optional): Maximum characters in user prompt; **default**: 2000000

**Note:** The `systemPrompt` variable is optional and defaults to a comprehensive prompt guard template embedded in the safeguard-agent process (see `safeguard-systemprompt-feel.txt`). The example demonstrates the minimal configuration by not passing this variable.

### Output Variables

The Call Activity maps the following outputs:

- **safeGuardResult**: Contains the safeguard analysis result as a JSON object
- **error**: Contains error details if a BPMN error occurred in the safeguard agent

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

## Escalation and Error Handling

The usage example includes **event subprocesses** that catch all escalation events thrown by the safeguard agent. Each escalation is caught and converted into a BPMN error (creating an incident for operator review):

| Event Subprocess | Escalation Code | Trigger |
|---|---|---|
| User Prompt too large | `safeguard_max-user-input-exceeded` | User prompt exceeds `maxUserPromptSize` |
| Max Iterations reached | `safeguard_max-iterations-reached` | Retry count exhausted without sufficient confidence |
| Task Agent failed | `safeguard_task-agent-failed` | AI agent connector throws a BPMN error |
| Json Worker error | `safeguard_json-worker-error` | JSON converter worker fails to parse LLM response |
| Bad Agent output | `safeguard_bad-agent-output` | LLM response missing required `decision`/`confidence` fields |
| Generic catch-all | _(any)_ | Catches any other escalation from the Call Activity |

You can customize these subprocesses to implement your own error handling strategy (e.g., logging, notifications, retry logic) instead of creating incidents.

## How to Deploy and Run

1. Deploy both BPMN files to your Camunda 8 cluster:
   - `safeguard-agent.bpmn`
   - `safeguard-agent-usage-example.bpmn`

1. Start the Job Worker in `/src/main/java/io/camunda/bizsol/bb/ai_firewall_agent/AIFirewallAgentApplication.java`
  
1. Start a process instance of `safeguard-agent-usage` with the minimal required variable:

   ```json
   {
     "userPromptToSafeguard": "What is the status of my insurance claim number IC-2024-001?"
   }
   ```

1. The process will complete with the `safeGuardResult` variable containing the analysis.

## Advanced Configuration

The safeguard-agent supports additional optional parameters. The usage example already maps these via the Call Activity input:

- **systemPrompt** (default: content from `safeguard-systemprompt.txt`): Custom system prompt for the AI agent
- **maxTries** (default: 3): Maximum retry attempts for safeguard analysis
- **minConfidence** (default: 0.95): Minimum confidence level required
- **maxUserPromptSize** (default: 2000000): Maximum characters in user prompt

To override the defaults, supply these variables when starting the process instance.

## Integration Notes

- The Call Activity uses `propagateAllChildVariables="false"` to prevent variable leakage
- Only explicitly mapped variables are passed to and from the safeguard-agent
- This ensures clean separation between the calling process and the safeguard agent
