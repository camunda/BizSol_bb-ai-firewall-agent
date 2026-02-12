# AI Firewall Agent

It takes a user prompt and safeguards against malicious intent.

## mandatory inputs 

- `userPromptToSafeguard` (string) for user prompt; **default**: null
- `systemPrompt` (string) for system prompt; **default**: (see file `safeguard-systemprompt.txt`)

## mandatory customizations

- complete the `Model provider` and `Model` sections of the `Safeguard Prompt` task

## Guardrails

- `maxTries` (int) for max allowed iterations over safeguard attempts; **default**: 3
- `minConfidence` (float, 0.00 .. 1.00): for minimal confidence to be a trusted decision; **default** : 0.95
- `maxUserPromptSize` (int): maximum character size for user prompt (== input); **default**: 2000000 (2 million)

## JSON schema output

Process has the variable `safeGuardResult` adhere to this schema:

```json
{
  "decision": "allow" | "warn" | "block",
  "risk_labels": [
    "injection" | "jailbreak" | "harmful_intent" | "policy_evasion" |
    "sensitive_data" | "privacy" | "obfuscation" | "tool_manipulation" | "other"
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
