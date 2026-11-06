#!/usr/bin/env bash
# The cleanup eval: the cases in tools/cleanup-eval/cases.jsonl through chat models, with the
# real ChatCleanup (prompt, worked examples, guard) and the normalizer that runs after it.
# Plan: docs/plans/2026-11-06-cleanup-prompt.md ("Measurement").
#
# Keys only from the environment, set for the run; nothing is written but the report:
#   TINFOIL_API_KEY  GROQ_API_KEY  OPENAI_API_KEY  AMBER_PC_URL (the address with its key)
#
# Examples:
#   tools/cleanup-eval/run.sh --dry-run
#   tools/cleanup-eval/run.sh --models openai:gpt-4.1-nano --variant AB --repeat 2
#   tools/cleanup-eval/run.sh --models tinfoil:deepseek-v4-1-flash,groq:openai/gpt-oss-20b \
#       --only self-correction,nein-as-content --out /tmp/eval.md
#
# Options: --models provider:model,...  (tinfoil, groq, openai, amberpc)
#          --variant B|A|AB  (B: the prompt in the code; A: the prompt of 0.26.3)
#          --repeat N  --cases FILE  --only IDS_OR_GROUPS  --out FILE  --dry-run
set -euo pipefail
cd "$(dirname "$0")/../.."
exec ./gradlew -q :core:cleanupEval --args="$*"
