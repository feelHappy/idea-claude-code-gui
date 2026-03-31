/**
 * Codex channel command handler – keeps Codex specific logic separated.
 */
import {
  sendMessage as codexSendMessage,
  getMcpServerStatus as codexGetMcpServerStatus,
  getMcpServerTools as codexGetMcpServerTools
} from '../services/codex/message-service.js';

/**
 * Execute a Codex command.
 * @param {string} command
 * @param {string[]} args
 * @param {object|null} stdinData
 */
export async function handleCodexCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        const {
          message,
          threadId,
          cwd,
          permissionMode,
          model,
          baseUrl,
          apiKey,
          reasoningEffort,
          attachments  // Image attachments (local_image format)
        } = stdinData;
        await codexSendMessage(
          message,
          threadId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          baseUrl || '',
          apiKey || '',
          reasoningEffort || 'medium',
          attachments || []  // Pass attachments to message service
        );
      } else {
        await codexSendMessage(args[0], args[1], args[2], args[3], args[4]);
      }
      break;
    }

    case 'getMcpServerTools': {
      const serverId = stdinData?.serverId || args[0] || null;
      const serverConfig = stdinData?.serverConfig || null;
      await codexGetMcpServerTools(serverId, serverConfig);
      break;
    }

    case 'getMcpServerStatus': {
      const servers = stdinData?.servers || null;
      await codexGetMcpServerStatus(servers);
      break;
    }

    default:
      throw new Error(`Unknown Codex command: ${command}`);
  }
}

export function getCodexCommandList() {
  return ['send', 'getMcpServerStatus', 'getMcpServerTools'];
}
