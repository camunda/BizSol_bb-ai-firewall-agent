#!/bin/bash
# Safeguard Agent Test Script
# Tests user prompts for safety using the safeguard-agent process
#
# Usage:
#   ./safeguard-test.sh [user_prompt_file] [system_prompt_file]
#
# Examples:
#   ./safeguard-test.sh                                    # Use defaults
#   ./safeguard-test.sh prompts/safeguard-safeprompt.txt   # Custom user prompt
#   ./safeguard-test.sh prompts/my-prompt.txt prompts/my-system.txt  # Both custom

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default prompt files (can be overridden via arguments)
USER_PROMPT_FILE="${1:-${SCRIPT_DIR}/prompts/safeguard-unsafeprompt.txt}"
SYSTEM_PROMPT_FILE="${2:-${SCRIPT_DIR}/prompts/safeguard-systemprompt.txt}"

# Check if files exist
if [[ ! -f "$SYSTEM_PROMPT_FILE" ]]; then
  echo "Error: System prompt file not found: $SYSTEM_PROMPT_FILE" >&2
  exit 1
fi

if [[ ! -f "$USER_PROMPT_FILE" ]]; then
  echo "Error: User prompt file not found: $USER_PROMPT_FILE" >&2
  exit 1
fi

echo "System prompt: $SYSTEM_PROMPT_FILE"
echo "User prompt:   $USER_PROMPT_FILE"
echo ""

# Capture full output
OUTPUT=$(c8 await pi \
  --id=safeguard-agent \
  --fetchVariables \
  --variables="$(jq -n \
  --arg systemPrompt "$(cat "$SYSTEM_PROMPT_FILE")" \
  --arg userPrompt "$(cat "$USER_PROMPT_FILE")" \
  '{systemPrompt: $systemPrompt, userPromptToSafeguard: $userPrompt}')" 2>&1)

# Extract and display process instance key
PI_KEY=$(echo "$OUTPUT" | grep -o 'Key: [0-9]*' | grep -o '[0-9]*')
echo "Process Instance Key: $PI_KEY"
echo ""

# Extract JSON and parse results
echo "$OUTPUT" | \
  sed -n '/^{/,$p' | \
  jq '{execTime: .variables.execTime, tokenUsage: .variables.tokenUsage, responseJSON: .variables.responseJSON}'
