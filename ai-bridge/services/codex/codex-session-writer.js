/**
 * Codex Session Writer
 *
 * Writes plugin-initiated Codex conversations to JSONL session files
 * under ~/.codex/sessions/yyyy/MM/dd/ so that CodexHistoryParser.java
 * can discover and index them alongside Codex CLI sessions.
 *
 * File naming convention (mirrors Codex CLI):
 *   rollout-{isoTimestamp}-{uuid}.jsonl
 *
 * Minimum record layout required by CodexHistoryParser:
 *   1. session_meta  — provides cwd + firstTimestamp
 *   2. event_msg (user_message) — provides title (payload.message)
 *   3. response_item — increments messageCount to satisfy isValidSession()
 *   4. event_msg (task_complete) — marks end of session
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import crypto from 'crypto';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Return ISO-8601 timestamp string for now, e.g. "2026-04-02T19:30:00.123Z"
 */
function nowIso() {
  return new Date().toISOString();
}

/**
 * Generate a UUID v4 string.
 */
function generateUuid() {
  return crypto.randomUUID();
}

/**
 * Build session file path:
 *   <home>/.codex/sessions/<yyyy>/<MM>/<dd>/rollout-<fileTs>-<uuid>.jsonl
 *
 * @param {Date} date
 * @param {string} uuid
 * @returns {string}
 */
function buildSessionFilePath(date, uuid) {
  const yyyy = date.getFullYear().toString();
  const MM = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');

  // Timestamp portion in filename: replace colons and dots to stay filesystem-safe
  // e.g. "2026-04-02T19-30-00-123"
  const ts = date.toISOString()
    .replace('T', 'T')
    .replace(/:/g, '-')
    .replace('.', '-')
    .replace('Z', '');

  const dir = path.join(os.homedir(), '.codex', 'sessions', yyyy, MM, dd);
  const fileName = `rollout-${ts}-${uuid}.jsonl`;
  return path.join(dir, fileName);
}

/**
 * Serialize one JSONL record (object → single line with newline).
 *
 * @param {string} type
 * @param {Object} payload
 * @param {string} [timestamp]
 * @returns {string}
 */
function buildRecord(type, payload, timestamp) {
  const record = {
    timestamp: timestamp || nowIso(),
    type,
    payload
  };
  return JSON.stringify(record) + '\n';
}

/**
 * Strip <agents-instructions>...</agents-instructions> wrapper from message
 * so the title stored in history shows the real user question.
 *
 * @param {string} message
 * @returns {string}
 */
function stripAgentsInstructions(message) {
  if (!message) return '';
  // Remove the agents-instructions block including its surrounding newlines
  return message
    .replace(/<agents-instructions>[\s\S]*?<\/agents-instructions>\s*/g, '')
    .trim();
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Write a Codex conversation to a JSONL session file.
 *
 * The resulting file is compatible with CodexHistoryParser.java:
 *   - session_meta  → sets cwd / firstTimestamp
 *   - event_msg (user_message) → title extraction
 *   - response_item → messageCount += 1  (satisfies isValidSession)
 *   - event_msg (task_complete) → end marker
 *
 * @param {Object} params
 * @param {string} params.userMessage       Original user message (pre-agents-instructions wrapping)
 * @param {string} params.assistantResponse Final assistant text response
 * @param {string} [params.cwd]             Working directory of the session
 * @param {string} [params.sessionId]       Codex thread/session UUID (reuse or generate)
 * @param {string} [params.model]           Model name used
 * @returns {string|null} Absolute path of the written file, or null on failure
 */
export function writeCodexSession({
  userMessage,
  assistantResponse,
  cwd,
  sessionId,
  model
}) {
  try {
    const cleanMessage = stripAgentsInstructions(userMessage);

    // If the message is empty after stripping, there is nothing meaningful to record
    if (!cleanMessage) {
      return null;
    }

    const now = new Date();
    const nowTs = now.toISOString();
    const uuid = sessionId || generateUuid();

    const filePath = buildSessionFilePath(now, uuid);
    const dir = path.dirname(filePath);

    // Ensure directory exists
    fs.mkdirSync(dir, { recursive: true });

    let content = '';

    // -----------------------------------------------------------------------
    // Record 1: session_meta
    // -----------------------------------------------------------------------
    content += buildRecord('session_meta', {
      id: uuid,
      timestamp: nowTs,
      cwd: cwd || '',
      originator: 'cc-ai-toolkit',
      source: 'plugin',
      model_provider: 'openai',
      model: model || 'unknown'
    }, nowTs);

    // -----------------------------------------------------------------------
    // Record 2: event_msg / user_message  (title source for CodexHistoryParser)
    // -----------------------------------------------------------------------
    content += buildRecord('event_msg', {
      type: 'user_message',
      message: cleanMessage,
      images: []
    }, nowTs);

    // -----------------------------------------------------------------------
    // Record 3: response_item (assistant response)
    //   messageCount is incremented for every response_item, need at least 1
    // -----------------------------------------------------------------------
    const responseTs = nowIso();
    content += buildRecord('response_item', {
      type: 'message',
      role: 'assistant',
      content: [
        {
          type: 'output_text',
          text: assistantResponse || ''
        }
      ]
    }, responseTs);

    // -----------------------------------------------------------------------
    // Record 4: event_msg / task_complete
    // -----------------------------------------------------------------------
    content += buildRecord('event_msg', {
      type: 'task_complete'
    }, responseTs);

    fs.writeFileSync(filePath, content, 'utf8');
    return filePath;
  } catch (err) {
    // Non-fatal: history persistence failure should not disrupt the conversation
    console.error('[CodexSessionWriter] Failed to write session file:', err.message);
    return null;
  }
}
