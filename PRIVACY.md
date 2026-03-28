# Privacy Policy for CC AI Toolkit

Last updated: 2026-03-28

## 1. Controller and Contact

CC AI Toolkit is provided by `Jordan`.

Contact:

- Support: `https://github.com/feelHappy/idea-claude-code-gui/issues`
- Website: `https://github.com/feelHappy/idea-claude-code-gui`

## 2. What the Plugin Processes

The plugin may process the following categories of data when the user interacts with it:

- Prompts, chat messages, and user instructions entered into the plugin UI
- Code snippets, file paths, selected text, and project context explicitly sent by the user
- Images attached by the user
- Provider configuration entered by the user
- Local AI workflow configuration selected by the user
- Basic diagnostic logs required to troubleshoot plugin failures

## 3. What the Plugin Does Not Do

The plugin is designed not to silently scan the user's disk for credentials.

Credential sources require explicit user action or authorization. For example:

- a user may manually enter API credentials in provider settings
- a user may explicitly authorize reading compatible local configuration files
- a user may explicitly choose to use a provider's native authentication flow

## 4. How Data Is Used

The plugin uses processed data to:

- generate AI responses requested by the user
- provide IDE-integrated features such as context injection, diff review, file navigation, and chat history
- manage provider connections selected by the user
- diagnose crashes, errors, or broken integrations

## 5. External Services

Depending on the user's configuration, the plugin may send user-selected content to:

- Anthropic services for Claude-related features
- OpenAI services for Codex-related features
- user-configured proxy or third-party API endpoints
- other user-enabled local or remote integrations

The plugin operator does not control how third-party providers process data once requests are sent to them. Users should review the privacy policies of the providers they choose to use.

## 6. Local Storage

The plugin may store the following information locally on the user's machine:

- chat and session metadata
- plugin settings
- provider preferences
- cached UI state
- locally persisted logs

Sensitive credentials should be stored only through the mechanisms explicitly supported by the plugin and the IntelliJ Platform.

## 7. Data Sharing

The plugin does not sell personal data.

Data is shared only as needed to:

- fulfill user-requested AI operations
- support configured providers or proxy endpoints
- comply with legal obligations

## 8. Data Retention

Local data is retained until:

- the user deletes it
- the plugin overwrites it as part of normal operation
- the user uninstalls the plugin and removes its local data directories

Third-party providers may keep submitted data according to their own retention policies.

## 9. User Choices

Users can generally control whether data is sent by:

- choosing whether to send prompts or context
- choosing which provider or proxy endpoint to use
- removing or changing saved settings
- revoking configured credentials

## 10. Security

Reasonable technical and organizational measures should be used to protect stored settings and transmitted data. However, no software or network transmission method is completely secure.

## 11. International Transfers

If external providers are located outside the user's jurisdiction, data may be transferred internationally as part of the requested service.

## 12. Children's Privacy

The plugin is not intended for children under the age required by applicable law to consent to data processing on their own.

## 13. Changes to This Policy

This policy may be updated from time to time. The latest version should be published at:

`https://github.com/feelHappy/idea-claude-code-gui/blob/main/PRIVACY.md`

## 14. Open Source Notice

The plugin source code and open-source license terms are available in the project repository and the bundled license file.
