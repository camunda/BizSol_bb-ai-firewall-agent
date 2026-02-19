#!/bin/bash
# Safeguard Agent Test Script
# Runs the safeguard-agent-usage-example process with each prompt file
# found in the prompts/ directory.
#
# Usage:
#   ./safeguard-test.sh [options] [pattern]
#
# Options:
#   --minConfidence <float>      Min confidence threshold (e.g. 0.8)
#   --maxTries <int>             Max retry attempts (e.g. 3)
#   --maxUserPromptSize <int>    Max user prompt size in chars (e.g. 2000)
#
# The optional pattern is a glob matched against filenames in prompts/.
# If omitted, all safeguard-*.txt files are used.
#
# Variables can also be set via environment variables:
#   minConfidence, maxTries, maxUserPromptSize
#
# Command-line switches take precedence over environment variables.
#
# Examples:
#   ./safeguard-test.sh                                        # Run all prompts
#   ./safeguard-test.sh "safeguard-block-*.txt"                # Only block prompts
#   ./safeguard-test.sh --minConfidence 0.9 "*warn*.txt"       # Warn prompts with confidence
#   ./safeguard-test.sh --maxTries 5 --maxUserPromptSize 1000  # All prompts with overrides
#   minConfidence=0.85 ./safeguard-test.sh                     # Via env var

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPTS_DIR="${SCRIPT_DIR}/prompts"

# Parse command-line switches; remaining positional arg becomes PATTERN
while [[ $# -gt 0 ]]; do
  case "$1" in
    --minConfidence)
      minConfidence="$2"; shift 2 ;;
    --maxTries)
      maxTries="$2"; shift 2 ;;
    --maxUserPromptSize)
      maxUserPromptSize="$2"; shift 2 ;;
    -*)
      echo "Unknown option: $1" >&2; exit 1 ;;
    *)
      PATTERN="$1"; shift ;;
  esac
done

PATTERN="${PATTERN:-safeguard-*.txt}"

if [[ ! -d "$PROMPTS_DIR" ]]; then
  echo "Error: Prompts directory not found: $PROMPTS_DIR" >&2
  exit 1
fi

PROMPT_FILES=("$PROMPTS_DIR"/$PATTERN)

if [[ ${#PROMPT_FILES[@]} -eq 0 || ! -e "${PROMPT_FILES[0]}" ]]; then
  echo "Error: No prompt files matching '$PATTERN' in $PROMPTS_DIR" >&2
  exit 1
fi

echo "=========================================="
echo "Safeguard Agent Test"
echo "=========================================="
echo "Process:  safeguard-agent-usage-example-applied"
echo "Prompts:  ${#PROMPT_FILES[@]} file(s) in ${PROMPTS_DIR}"
[[ -n "$minConfidence" ]]    && echo "minConfidence:      $minConfidence"
[[ -n "$maxTries" ]]         && echo "maxTries:           $maxTries"
[[ -n "$maxUserPromptSize" ]] && echo "maxUserPromptSize:  $maxUserPromptSize"
echo ""

PASS=0
FAIL=0

for PROMPT_FILE in "${PROMPT_FILES[@]}"; do
  PROMPT_NAME="$(basename "$PROMPT_FILE")"
  echo "------------------------------------------"
  echo "Prompt: $PROMPT_NAME"
  echo "------------------------------------------"

  # Build jq arguments for optional variables
  JQ_ARGS=(--arg userPrompt "$(cat "$PROMPT_FILE")")
  JQ_FILTER='{userPromptToSafeguard: $userPrompt}'

  if [[ -n "$minConfidence" ]]; then
    JQ_ARGS+=(--argjson minConfidence "$minConfidence")
    JQ_FILTER="{userPromptToSafeguard: \$userPrompt, minConfidence: \$minConfidence}"
  fi
  if [[ -n "$maxTries" ]]; then
    JQ_ARGS+=(--argjson maxTries "$maxTries")
    JQ_FILTER="${JQ_FILTER%\}}, maxTries: \$maxTries}"
  fi
  if [[ -n "$maxUserPromptSize" ]]; then
    JQ_ARGS+=(--argjson maxUserPromptSize "$maxUserPromptSize")
    JQ_FILTER="${JQ_FILTER%\}}, maxUserPromptSize: \$maxUserPromptSize}"
  fi

  # Build the variables JSON
  VARIABLES_JSON="$(jq -n "${JQ_ARGS[@]}" "$JQ_FILTER")"
  echo "Variables: $VARIABLES_JSON"

  # Capture full output
  OUTPUT=$(c8 await pi \
    --id=safeguard-agent-usage-example-applied \
    --fetchVariables \
    --variables="$VARIABLES_JSON" 2>&1)

  # Extract and display process instance key
  PI_KEY=$(echo "$OUTPUT" | grep -o 'Key: [0-9]*' | grep -o '[0-9]*')
  echo "Process Instance Key: $PI_KEY"

  # Extract JSON and parse results
  RESULT=$(echo "$OUTPUT" | \
    sed -n '/^{/,$p' | \
    jq '{execTime: .variables.execTime, tokenUsage: .variables.tokenUsage, safeGuardResult: .variables.safeGuardResult}' 2>/dev/null)

  if [[ $? -eq 0 && -n "$RESULT" ]]; then
    echo "$RESULT"
    PASS=$((PASS + 1))
  else
    echo "Error: Failed to parse output for $PROMPT_NAME"
    echo "Raw output:"
    echo "$OUTPUT"
    FAIL=$((FAIL + 1))
  fi
  echo ""
done

echo "=========================================="
echo "Summary: $PASS passed, $FAIL failed out of ${#PROMPT_FILES[@]} prompts"
echo "=========================================="
