import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { __testing, createInitialEventState, processCodexEventStream } from './codex-event-handler.js';

function createBridgeState(emitted) {
  return {
    emittedToolUseIds: new Set(),
    emittedSyntheticToolResults: new Set(),
    bridgedFunctionCalls: new Map(),
    subagentToolUseIdsByAgentId: new Map(),
    emitMessage: (message) => emitted.push(message),
  };
}

async function* eventStream(events) {
  for (const event of events) {
    yield event;
  }
}

async function runCodexEventStream(events) {
  const emitted = [];
  const state = createInitialEventState((message) => emitted.push(message));
  await processCodexEventStream(eventStream(events), state, {
    cwd: null,
    threadId: null,
    threadOptions: { approvalPolicy: 'never' },
    normalizedPermissionMode: 'bypassPermissions',
    turnAbortController: new AbortController(),
  });
  return emitted;
}

async function createTempGitRepo() {
  const repoDir = await mkdtemp(path.join(os.tmpdir(), 'codex-event-handler-'));
  execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['config', 'user.name', 'Codex Test'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['config', 'user.email', 'codex@example.com'], { cwd: repoDir, stdio: 'ignore' });
  return repoDir;
}

test('computeAgentMessageUpdate emits only the delta for cumulative snapshots', () => {
  const first = __testing.computeAgentMessageUpdate('A');
  assert.equal(first.kind, 'initial');
  assert.equal(first.emittedText, 'A');
  assert.equal(first.assistantText, 'A');

  const second = __testing.computeAgentMessageUpdate('A\nB', first.assistantText);
  assert.equal(second.kind, 'cumulative');
  assert.equal(second.emittedText, 'B');
  assert.equal(second.assistantText, 'A\nB');

  const third = __testing.computeAgentMessageUpdate('A\nB\nC', second.assistantText);
  assert.equal(third.kind, 'cumulative');
  assert.equal(third.emittedText, 'C');
  assert.equal(third.assistantText, 'A\nB\nC');
});

test('computeAgentMessageUpdate appends unrelated assistant messages without dropping content', () => {
  const first = __testing.computeAgentMessageUpdate('First section');
  const second = __testing.computeAgentMessageUpdate('Second section', first.assistantText);

  assert.equal(second.kind, 'append');
  assert.equal(second.emittedText, 'Second section');
  assert.equal(second.assistantText, 'First section\nSecond section');
});

test('computeAgentMessageUpdate ignores exact duplicates and stale shorter snapshots', () => {
  const duplicate = __testing.computeAgentMessageUpdate('Repeated', 'Repeated');
  assert.equal(duplicate.kind, 'duplicate');
  assert.equal(duplicate.emittedText, '');
  assert.equal(duplicate.assistantText, 'Repeated');

  const stale = __testing.computeAgentMessageUpdate('Repeat', 'Repeated');
  assert.equal(stale.kind, 'stale');
  assert.equal(stale.emittedText, '');
  assert.equal(stale.assistantText, 'Repeated');
});

test('handleResponseItemPayload bridges update_plan into TodoWrite tool messages', () => {
  const emitted = [];
  const state = createBridgeState(emitted);

  __testing.handleResponseItemPayload({
    type: 'function_call',
    name: 'update_plan',
    call_id: 'call_plan_1',
    arguments: JSON.stringify({
      explanation: 'Focus on the bug first',
      plan: [
        { step: 'Reproduce the issue', status: 'completed' },
        { step: 'Patch the bridge', status: 'in_progress' },
      ],
    }),
  }, state);

  __testing.handleResponseItemPayload({
    type: 'function_call_output',
    call_id: 'call_plan_1',
    output: 'Plan updated',
  }, state);

  assert.equal(emitted.length, 2);
  assert.equal(emitted[0].type, 'assistant');
  assert.deepEqual(emitted[0].message.content[0], {
    type: 'tool_use',
    id: 'call_plan_1',
    name: 'TodoWrite',
    input: {
      explanation: 'Focus on the bug first',
      source: 'codex_update_plan',
      todos: [
        { id: 'call_plan_1_0', content: 'Reproduce the issue', status: 'completed' },
        { id: 'call_plan_1_1', content: 'Patch the bridge', status: 'in_progress' },
      ],
    },
  });
  assert.equal(emitted[1].type, 'user');
  assert.deepEqual(emitted[1].message.content[0], {
    type: 'tool_result',
    tool_use_id: 'call_plan_1',
    is_error: false,
    content: 'Plan updated',
  });
});

test('buildTodoWriteInputFromUpdatePlan preserves empty plans so the UI can clear stale todos', () => {
  assert.deepEqual(
    __testing.buildTodoWriteInputFromUpdatePlan({ plan: [] }, 'call_plan_empty'),
    {
      todos: [],
      source: 'codex_update_plan',
    },
  );
});

test('handleResponseItemPayload bridges spawn_agent and resolves completion on wait_agent', () => {
  const emitted = [];
  const state = createBridgeState(emitted);

  __testing.handleResponseItemPayload({
    type: 'function_call',
    name: 'spawn_agent',
    call_id: 'call_agent_spawn',
    arguments: JSON.stringify({
      agent_type: 'worker',
      message: 'Check the failing Codex status panel behavior and report back.',
    }),
  }, state);

  __testing.handleResponseItemPayload({
    type: 'function_call_output',
    call_id: 'call_agent_spawn',
    output: JSON.stringify({ id: 'agent_123', nickname: 'Worker 1' }),
  }, state);

  __testing.handleResponseItemPayload({
    type: 'function_call',
    name: 'wait_agent',
    call_id: 'call_wait_agent',
    arguments: JSON.stringify({ targets: ['agent_123'] }),
  }, state);

  __testing.handleResponseItemPayload({
    type: 'function_call_output',
    call_id: 'call_wait_agent',
    output: 'completed',
  }, state);

  assert.equal(emitted.length, 2);
  assert.equal(emitted[0].type, 'assistant');
  assert.deepEqual(emitted[0].message.content[0], {
    type: 'tool_use',
    id: 'call_agent_spawn',
    name: 'agent',
    input: {
      subagent_type: 'worker',
      description: 'Check the failing Codex status panel behavior and report back.',
      prompt: 'Check the failing Codex status panel behavior and report back.',
      source: 'codex_spawn_agent',
    },
  });
  assert.equal(emitted[1].type, 'user');
  assert.deepEqual(emitted[1].message.content[0], {
    type: 'tool_result',
    tool_use_id: 'call_agent_spawn',
    is_error: false,
    content: 'completed',
  });
});

test('processCodexEventStream bridges todo_list items from the real Codex event stream', async () => {
  const emitted = await runCodexEventStream([
    { type: 'turn.started' },
    {
      type: 'item.started',
      item: {
        id: 'todo_item_1',
        type: 'todo_list',
        items: [
          { text: 'inspect event stream', completed: false },
          { text: 'report status panel result', completed: true },
        ],
      },
    },
    {
      type: 'item.updated',
      item: {
        id: 'todo_item_1',
        type: 'todo_list',
        items: [],
      },
    },
    { type: 'turn.completed' },
  ]);

  assert.deepEqual(
    emitted.map((message) => message.message?.content?.[0]).filter(Boolean),
    [
      {
        type: 'tool_use',
        id: 'todo_item_1',
        name: 'TodoWrite',
        input: {
          todos: [
            { id: 'todo_item_1_0', content: 'inspect event stream', status: 'pending' },
            { id: 'todo_item_1_1', content: 'report status panel result', status: 'completed' },
          ],
          source: 'codex_todo_list',
        },
      },
      {
        type: 'tool_use',
        id: 'todo_item_1',
        name: 'TodoWrite',
        input: {
          todos: [],
          source: 'codex_todo_list',
        },
      },
    ],
  );
});

test('processCodexEventStream bridges collab_tool_call spawn_agent/wait success into agent tool messages', async () => {
  const emitted = await runCodexEventStream([
    { type: 'turn.started' },
    {
      type: 'item.started',
      item: {
        id: 'collab_spawn_1',
        type: 'collab_tool_call',
        tool: 'spawn_agent',
        sender_thread_id: 'thread_main',
        receiver_thread_ids: [],
        prompt: "Count how many letters are in the word 'bridge'. Reply with just the number.",
        agents_states: {},
        status: 'in_progress',
      },
    },
    {
      type: 'item.completed',
      item: {
        id: 'collab_spawn_1',
        type: 'collab_tool_call',
        tool: 'spawn_agent',
        sender_thread_id: 'thread_main',
        receiver_thread_ids: ['agent_thread_1'],
        prompt: "Count how many letters are in the word 'bridge'. Reply with just the number.",
        agents_states: {
          agent_thread_1: { status: 'pending_init', message: null },
        },
        status: 'completed',
      },
    },
    {
      type: 'item.completed',
      item: {
        id: 'collab_wait_1',
        type: 'collab_tool_call',
        tool: 'wait',
        sender_thread_id: 'thread_main',
        receiver_thread_ids: ['agent_thread_1'],
        prompt: null,
        agents_states: {
          agent_thread_1: { status: 'completed', message: '6' },
        },
        status: 'completed',
      },
    },
    { type: 'turn.completed' },
  ]);

  assert.deepEqual(
    emitted.map((message) => message.message?.content?.[0]).filter(Boolean),
    [
      {
        type: 'tool_use',
        id: 'collab_spawn_1',
        name: 'agent',
        input: {
          subagent_type: 'default',
          description: "Count how many letters are in the word 'bridge'. Reply with just the number.",
          prompt: "Count how many letters are in the word 'bridge'. Reply with just the number.",
          source: 'codex_collab_tool_call',
        },
      },
      {
        type: 'tool_result',
        tool_use_id: 'collab_spawn_1',
        is_error: false,
        content: '6',
      },
    ],
  );
});

test('processCodexEventStream marks collab_tool_call wait failures as subagent errors', async () => {
  const emitted = await runCodexEventStream([
    { type: 'turn.started' },
    {
      type: 'item.completed',
      item: {
        id: 'collab_spawn_2',
        type: 'collab_tool_call',
        tool: 'spawn_agent',
        sender_thread_id: 'thread_main',
        receiver_thread_ids: ['agent_thread_2'],
        prompt: 'Purely textual task only.',
        agents_states: {
          agent_thread_2: { status: 'pending_init', message: null },
        },
        status: 'completed',
      },
    },
    {
      type: 'item.completed',
      item: {
        id: 'collab_wait_2',
        type: 'collab_tool_call',
        tool: 'wait',
        sender_thread_id: 'thread_main',
        receiver_thread_ids: ['agent_thread_2'],
        prompt: null,
        agents_states: {
          agent_thread_2: {
            status: 'errored',
            message: 'unexpected status 502 Bad Gateway',
          },
        },
        status: 'failed',
      },
    },
    { type: 'turn.completed' },
  ]);

  assert.deepEqual(
    emitted.map((message) => message.message?.content?.[0]).filter(Boolean),
    [
      {
        type: 'tool_use',
        id: 'collab_spawn_2',
        name: 'agent',
        input: {
          subagent_type: 'default',
          description: 'Purely textual task only.',
          prompt: 'Purely textual task only.',
          source: 'codex_collab_tool_call',
        },
      },
      {
        type: 'tool_result',
        tool_use_id: 'collab_spawn_2',
        is_error: true,
        content: 'unexpected status 502 Bad Gateway',
      },
    ],
  );
});

test('collectTurnModifiedFiles reports edits to files that were already dirty before the turn', async (t) => {
  const repoDir = await createTempGitRepo();
  t.after(async () => {
    await rm(repoDir, { recursive: true, force: true });
  });

  const trackedFile = path.join(repoDir, 'tracked.txt');
  await writeFile(trackedFile, 'base\n', 'utf8');
  execFileSync('git', ['add', 'tracked.txt'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['commit', '-m', 'init'], { cwd: repoDir, stdio: 'ignore' });

  await writeFile(trackedFile, 'user dirty\n', 'utf8');
  const preExistingModifiedFiles = await __testing.snapshotModifiedFiles(repoDir);

  await writeFile(trackedFile, 'codex change\n', 'utf8');
  const changes = await __testing.collectTurnModifiedFiles({ preExistingModifiedFiles }, { cwd: repoDir });

  assert.equal(changes.length, 1);
  assert.equal(changes[0].status, 'M');
  assert.equal(changes[0].old_content, 'user dirty\n');
  assert.equal(changes[0].new_content, 'codex change\n');
  assert.equal(changes[0].path, trackedFile);
});

test('collectTurnModifiedFiles reconstructs old content for newly modified clean tracked files', async (t) => {
  const repoDir = await createTempGitRepo();
  t.after(async () => {
    await rm(repoDir, { recursive: true, force: true });
  });

  const trackedFile = path.join(repoDir, 'clean.txt');
  await writeFile(trackedFile, 'base\n', 'utf8');
  execFileSync('git', ['add', 'clean.txt'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['commit', '-m', 'init'], { cwd: repoDir, stdio: 'ignore' });

  await writeFile(trackedFile, 'codex change\n', 'utf8');
  const changes = await __testing.collectTurnModifiedFiles({ preExistingModifiedFiles: new Map() }, { cwd: repoDir });

  assert.equal(changes.length, 1);
  assert.equal(changes[0].status, 'M');
  assert.equal(changes[0].old_content, 'base\n');
  assert.equal(changes[0].new_content, 'codex change\n');
  assert.equal(changes[0].path, trackedFile);
});
