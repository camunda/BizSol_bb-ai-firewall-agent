#!/usr/bin/env node
/**
 * Prompt tuning script for safeguard-agent.
 *
 * Prerequisites:
 *   1. Local Camunda cluster running; c8ctl already wired to it
 *   2. Connector runtime configured with AWS_ACCESS_KEY / AWS_SECRET_KEY secrets
 *
 * The script deploys src/test/resources/safeguard-agent.aws.bpmn automatically
 * before every run (patching model/region in-memory when --model / --region are given).
 * The system prompt is always passed as a process variable, so editing
 * camunda-artifacts/safeguard-systemprompt.txt takes effect immediately —
 * no redeploy needed between prompt iterations.
 *
 * Usage:
 *   node scripts/run-prompts-local.mjs [options]
 *
 * Options:
 *   --parallel N              Max concurrent prompts (default: 5)
 *   --filter allow|warn|block Run only one category
 *   --prompt <filename>       Run a single file (e.g. safeguard-block-jailbreak.txt)
 *   --model <id>              Bedrock model id (default: eu.anthropic.claude-sonnet-4-6)
 *   --region <region>         Bedrock region   (default: eu-central-1)
 *   --request-timeout <ms>    Timeout per LLM call in ms (default: 120000)
 *   --verbose                 Print full safeGuardResult JSON for every result
 *   --dry-run                 Print what would happen without deploying or calling c8ctl
 *
 * Assertion logic mirrors SafeguardPromptClassificationIT.java:
 *   - allow → decision must equal "allow"
 *   - block → decision must equal "block"
 *   - warn  → decision may be "warn" or "block" (LLMs may reasonably escalate)
 *
 * Iteration loop:
 *   1. node scripts/run-prompts-local.mjs               # deploy + run all 14 prompts
 *   2. Edit camunda-artifacts/safeguard-systemprompt.txt
 *   3. node scripts/run-prompts-local.mjs --prompt <failing-file> --verbose
 *   4. Repeat until all pass
 *   5. mvn compile exec:java                             # sync prompt to production BPMN
 */

import { readFileSync, writeFileSync, readdirSync, mkdtempSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const execFileAsync = promisify(execFile);

// ── Path setup ────────────────────────────────────────────────────────────────

const SCRIPT_DIR = fileURLToPath(new URL('.', import.meta.url));
const PROJECT_ROOT = resolve(SCRIPT_DIR, '..');
const PROMPTS_DIR = join(PROJECT_ROOT, 'src', 'test', 'resources', 'prompts');
const SYSTEM_PROMPT_PATH = join(PROJECT_ROOT, 'camunda-artifacts', 'safeguard-systemprompt.txt');
const BPMN_SOURCE = join(PROJECT_ROOT, 'src', 'test', 'resources', 'safeguard-agent.aws.bpmn');

// ── Constants (mirrors LlmIntegrationTestBase) ────────────────────────────────

const PROCESS_ID = 'safeguard-agent';
const MIN_CONFIDENCE = 0.8;
const MAX_TRIES = 3;
const PROMPT_PATTERN = /^safeguard-(block|warn|allow)-(.+)\.txt$/;
const DEFAULT_MODEL = 'mistral.devstral-2-123b';
const DEFAULT_REGION = 'eu-central-1';

// ── CLI arg parsing ───────────────────────────────────────────────────────────

function parseArgs(argv) {
  const args = argv.slice(2);
  const opts = {
    parallel: 3,
    filter: null,
    prompt: null,
    model: DEFAULT_MODEL,
    region: DEFAULT_REGION,
    requestTimeout: 120_000,
    verbose: false,
    dryRun: false,
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    // Support both --key=value and --key value forms
    const eqIdx = arg.indexOf('=');
    const key = eqIdx >= 0 ? arg.slice(0, eqIdx) : arg;
    const val = eqIdx >= 0 ? arg.slice(eqIdx + 1) : args[i + 1];

    switch (key) {
      case '--parallel':
        opts.parallel = parseInt(val, 10);
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--filter':
        opts.filter = val;
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--prompt':
        opts.prompt = val;
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--model':
        opts.model = val;
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--region':
        opts.region = val;
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--request-timeout':
        opts.requestTimeout = parseInt(val, 10);
        if (!eqIdx || eqIdx < 0) i++;
        break;
      case '--verbose':
        opts.verbose = true;
        break;
      case '--dry-run':
        opts.dryRun = true;
        break;
      default:
        console.error(`Unknown option: ${key}`);
        process.exit(1);
    }
  }

  return opts;
}

// ── Prompt discovery ──────────────────────────────────────────────────────────

function discoverPrompts(filter, singleFile) {
  const files = readdirSync(PROMPTS_DIR)
    .filter(f => PROMPT_PATTERN.test(f))
    .sort();

  if (singleFile) {
    const match = files.find(f => f === singleFile);
    if (!match) {
      console.error(`Prompt file not found in ${PROMPTS_DIR}: ${singleFile}`);
      process.exit(1);
    }
    return [match];
  }

  if (filter) {
    if (!['allow', 'warn', 'block'].includes(filter)) {
      console.error(`Invalid --filter value "${filter}". Must be allow, warn, or block.`);
      process.exit(1);
    }
    return files.filter(f => PROMPT_PATTERN.exec(f)?.[1] === filter);
  }

  return files;
}

// ── BPMN deploy ──────────────────────────────────────────────────────────────

/**
 * Patch model/region into the BPMN XML and deploy via c8ctl.
 * Writes to a temp file so the source BPMN is never modified.
 */
async function deployBpmn(opts) {
  let bpmnXml = readFileSync(BPMN_SOURCE, 'utf8');

  // Patch region if different from what's in the file
  bpmnXml = bpmnXml.replace(
    /(<zeebe:input source=")[^"]+(?="\s+target="provider\.bedrock\.region")/,
    `$1${opts.region}`,
  );

  // Patch model if different from what's in the file
  bpmnXml = bpmnXml.replace(
    /(<zeebe:input source=")[^"]+(?="\s+target="provider\.bedrock\.model\.model")/,
    `$1${opts.model}`,
  );

  if (opts.dryRun) {
    console.log(`[dry-run] deploy ${BPMN_SOURCE}  (model=${opts.model}, region=${opts.region})`);
    return;
  }

  // Write patched BPMN to a temp file
  const tmpDir = mkdtempSync(join(tmpdir(), 'safeguard-agent-'));
  const tmpBpmn = join(tmpDir, 'safeguard-agent.bpmn');
  try {
    writeFileSync(tmpBpmn, bpmnXml, 'utf8');
    await execFileAsync('c8ctl', ['deploy', tmpBpmn]);
    console.log(`Deployed safeguard-agent  (model=${opts.model}, region=${opts.region})\n`);
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
}

// ── Concurrency pool ──────────────────────────────────────────────────────────

async function runWithConcurrency(tasks, maxConcurrent) {
  const results = new Array(tasks.length);
  let next = 0;

  async function worker() {
    while (next < tasks.length) {
      const i = next++;
      results[i] = await tasks[i]();
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(maxConcurrent, tasks.length) }, worker),
  );
  return results;
}

// ── Run one prompt via c8ctl ──────────────────────────────────────────────────

async function runPrompt(filename, systemPrompt, opts) {
  const m = PROMPT_PATTERN.exec(filename);
  const category = m[1];
  const label = m[2].replace(/-/g, ' ');

  const userPrompt = readFileSync(join(PROMPTS_DIR, filename), 'utf8');

  const variables = JSON.stringify({
    userPromptToSafeguard: userPrompt,
    systemPrompt,
    minConfidence: MIN_CONFIDENCE,
    maxTries: MAX_TRIES,
  });

  // c8ctl await pi is an alias for create pi --awaitCompletion=true.
  // json output mode is set once at startup so all c8ctl status messages go to stderr,
  // leaving stdout as clean JSON that we can parse.
  const c8ctlArgs = [
    'await', 'pi',
    `--id=${PROCESS_ID}`,
    `--requestTimeout=${opts.requestTimeout}`,
    '--fetchVariables',
    `--variables=${variables}`,
  ];

  if (opts.dryRun) {
    // Truncate variables for display (system prompt is large)
    const displayArgs = c8ctlArgs.map(a =>
      a.startsWith('--variables=') ? '--variables=<json>' : a,
    );
    console.log(`[dry-run] c8ctl ${displayArgs.join(' ')}`);
    return { filename, category, label, status: 'dry-run', pass: true };
  }

  const MAX_RETRIES = 2;
  const RETRY_DELAY_MS = 4000;

  const startMs = Date.now();
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    if (attempt > 0) {
      // Brief backoff before retry (service-unavailable / backpressure)
      await new Promise(r => setTimeout(r, RETRY_DELAY_MS));
    }
    try {
      // maxBuffer: response includes all process variables (system prompt etc.) — set generously
      const { stdout } = await execFileAsync('c8ctl', c8ctlArgs, {
        maxBuffer: 20 * 1024 * 1024,
        // Add headroom beyond the Camunda-side timeout for c8ctl's own overhead
        timeout: opts.requestTimeout + 30_000,
      });

      const result = JSON.parse(stdout);
      const safeGuardResult = result?.variables?.safeGuardResult;
      const decision = safeGuardResult?.decision;
      const elapsed = ((Date.now() - startMs) / 1000).toFixed(1);

      // Mirror SafeguardPromptClassificationIT: warn accepts warn OR block
      const pass =
        category === 'warn'
          ? decision === 'warn' || decision === 'block'
          : decision === category;

      if (pass) {
        console.log(`  ✓ [${category}] ${label}  (${decision}, ${elapsed}s)`);
      } else {
        console.log(`  ✗ [${category}] ${label}  — expected: ${category}, got: ${decision ?? '(missing)'}  (${elapsed}s)`);
      }

      if (opts.verbose || !pass) {
        const indent = s => s.replace(/^/gm, '    ');
        console.log(indent(JSON.stringify(safeGuardResult ?? '(no safeGuardResult in response)', null, 2)));
      }

      return { filename, category, label, pass, decision, elapsed, safeGuardResult };
    } catch (err) {
      // Retry on Service Unavailable (Zeebe backpressure) if retries remain
      const isUnavailable = (err.message ?? '').includes('Service Unavailable') ||
                            (err.stderr ?? '').includes('Service Unavailable');
      if (isUnavailable && attempt < MAX_RETRIES) {
        const elapsed = ((Date.now() - startMs) / 1000).toFixed(1);
        console.error(`  [retry ${attempt + 1}/${MAX_RETRIES}] [${category}] ${label}  (${elapsed}s) — Service Unavailable, retrying...`);
        continue;
      }

      const elapsed = ((Date.now() - startMs) / 1000).toFixed(1);
      // c8ctl exits non-zero when process fails (escalation, timeout, etc.)
      // stdout may still contain partial JSON if the error is in the process result
      const firstLine = (err.message ?? String(err)).split('\n')[0];
      console.log(`  ✗ [${category}] ${label}  — ERROR (${elapsed}s): ${firstLine}`);
      if (opts.verbose) {
        console.error(err);
      }
      return { filename, category, label, pass: false, error: err.message ?? String(err), elapsed };
    }
  }
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  const opts = parseArgs(process.argv);

  // Switch c8ctl to JSON output mode so that all status/info messages go to stderr
  // and stdout remains clean parseable JSON for every subsequent c8ctl invocation.
  if (!opts.dryRun) {
    await execFileAsync('c8ctl', ['output', 'json']);
  } else {
    console.log('[dry-run] c8ctl output json');
  }

  // Deploy the BPMN (patched with model/region) before running any prompts.
  // systemPrompt is passed as a variable every call, so no redeploy is needed
  // between prompt iterations — just rerun the script.
  await deployBpmn(opts);

  const systemPrompt = readFileSync(SYSTEM_PROMPT_PATH, 'utf8');
  const files = discoverPrompts(opts.filter, opts.prompt);

  if (files.length === 0) {
    console.error('No matching prompt files found.');
    process.exit(1);
  }

  const concurrency = opts.dryRun ? 1 : opts.parallel;
  const scope = opts.filter ? `[${opts.filter}]` : opts.prompt ? `[${opts.prompt}]` : '[all]';
  console.log(`Running ${files.length} prompt(s) ${scope}  parallel=${concurrency}  timeout=${opts.requestTimeout}ms\n`);

  const tasks = files.map(f => () => runPrompt(f, systemPrompt, opts));
  const results = await runWithConcurrency(tasks, concurrency);

  if (opts.dryRun) {
    console.log(`\n${files.length} command(s) listed (dry-run, nothing executed)`);
    return;
  }

  // ── Summary ────────────────────────────────────────────────────────────────
  const passed = results.filter(r => r.pass).length;
  const failed = results.filter(r => !r.pass);

  console.log(`\n${'─'.repeat(60)}`);
  console.log(`Results: ${passed}/${results.length} passed`);

  if (failed.length > 0) {
    console.log('\nFailed:');
    for (const r of failed) {
      if (r.error) {
        console.log(`  ✗ [${r.category}] ${r.label}  — error: ${r.error.split('\n')[0]}`);
      } else {
        console.log(`  ✗ [${r.category}] ${r.label}  — expected: ${r.category}, got: ${r.decision ?? '(missing)'}`);
      }
    }
    process.exitCode = 1;
  } else {
    console.log('\nAll prompts passed! ✓');
    console.log('\nNext step: mvn compile exec:java  →  sync prompt to production BPMN + FEEL file');
  }
}

main().catch(err => {
  console.error('Fatal:', err);
  process.exit(1);
});
