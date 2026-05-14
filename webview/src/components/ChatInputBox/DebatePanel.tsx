import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sendToJava } from '../../utils/bridge.js';
import { useDebate } from './hooks/useDebate.js';
import type { DebateRound } from './hooks/useDebate.js';

export function DebatePanel() {
  const { t } = useTranslation();
  const { debateState, rounds, startDebate, stopDebate } = useDebate();
  const [showDialog, setShowDialog] = useState(false);
  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');
  const [maxRounds, setMaxRounds] = useState(5);

  const handleStart = () => {
    if (!description.trim()) return;
    startDebate(topic || 'Bug', description, maxRounds);
    setShowDialog(false);
    setTopic('');
    setDescription('');
  };

  useEffect(() => {
    if (!showDialog) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowDialog(false);
      } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && description.trim()) {
        handleStart();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [showDialog, description, topic, maxRounds]);

  const isActive = debateState.active;
  const stateLabel = t(`chat.debate.status.${debateState.state.toLowerCase()}`, {
    defaultValue: debateState.state,
  });

  return (
    <div className="bmad-command-bar">
      <div className="bmad-command-main">
        <div className="bmad-command-head">
          <span className={`bmad-command-badge ${isActive ? 'state-loading' : debateState.state === 'CONSENSUS' ? 'state-ready' : 'state-missing'}`}>
            {stateLabel}
          </span>
          {isActive && debateState.round != null && (
            <span className="bmad-command-badge" style={{ opacity: 0.7 }}>
              {t('chat.debate.roundProgress', {
                defaultValue: 'Round {{current}}/{{max}}',
                current: debateState.round,
                max: debateState.maxRounds,
              })}
            </span>
          )}
          <span style={{ flex: 1 }} />
          <span className="bmad-command-head-title">
            {t('chat.debate.title', { defaultValue: 'Bug Debate' })}
          </span>
        </div>
        {debateState.message && (
          <div className="bmad-command-summary">{debateState.message}</div>
        )}
      </div>

      {rounds.length > 0 && (
        <div className="bmad-command-feedback" style={{ maxHeight: '200px', overflow: 'auto' }}>
          {rounds.map((r: DebateRound, i: number) => (
            <div key={i} style={{ marginBottom: '8px' }}>
              <div style={{ fontWeight: 600, fontSize: '11px', color: r.provider === 'Claude' ? 'var(--vscode-charts-blue)' : 'var(--vscode-charts-green)' }}>
                {r.provider} (Round {r.round})
              </div>
              <div className="bmad-command-detail muted" style={{ whiteSpace: 'pre-wrap', maxHeight: '80px', overflow: 'auto' }}>
                {r.content.length > 300 ? r.content.substring(0, 300) + '...' : r.content}
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="bmad-command-actions">
        {!isActive && (
          <button className="bmad-command-button primary" onClick={() => setShowDialog(true)}>
            <i className="codicon codicon-comment-discussion" />
            {t('chat.debate.start', { defaultValue: 'Start Debate' })}
          </button>
        )}
        {isActive && (
          <button className="bmad-command-button" onClick={stopDebate}>
            <i className="codicon codicon-debug-stop" />
            {t('chat.debate.stop', { defaultValue: 'Stop' })}
          </button>
        )}
        {debateState.filePath && (
          <button className="bmad-command-button" onClick={() => {
            sendToJava('open_file', JSON.stringify({ path: debateState.filePath }));
          }}>
            <i className="codicon codicon-file" />
            {t('chat.debate.viewFile', { defaultValue: 'View' })}
          </button>
        )}
      </div>

      {showDialog && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
        }} onClick={() => setShowDialog(false)}>
          <div style={{
            background: 'var(--vscode-editor-background)', border: '1px solid var(--vscode-widget-border)',
            borderRadius: '8px', padding: '16px', width: '400px', maxWidth: '90vw',
          }} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', fontSize: '14px' }}>
              {t('chat.debate.dialog.title', { defaultValue: 'Start Bug Debate' })}
            </h3>
            <input
              type="text"
              placeholder={t('chat.debate.dialog.topicPlaceholder', { defaultValue: 'Topic (optional)' })}
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              style={{
                width: '100%', padding: '6px 8px', marginBottom: '8px', boxSizing: 'border-box',
                background: 'var(--vscode-input-background)', border: '1px solid var(--vscode-input-border)',
                color: 'var(--vscode-input-foreground)', borderRadius: '4px',
              }}
            />
            <textarea
              placeholder={t('chat.debate.dialog.descPlaceholder', { defaultValue: 'Describe the bug or problem...' })}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={5}
              style={{
                width: '100%', padding: '6px 8px', marginBottom: '8px', boxSizing: 'border-box',
                background: 'var(--vscode-input-background)', border: '1px solid var(--vscode-input-border)',
                color: 'var(--vscode-input-foreground)', borderRadius: '4px', resize: 'vertical',
              }}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
              <label style={{ fontSize: '12px' }}>
                {t('chat.debate.dialog.maxRounds', { defaultValue: 'Max rounds:' })}
              </label>
              <input
                type="number" min={2} max={10} value={maxRounds}
                onChange={(e) => setMaxRounds(Number(e.target.value))}
                style={{
                  width: '50px', padding: '4px', background: 'var(--vscode-input-background)',
                  border: '1px solid var(--vscode-input-border)', color: 'var(--vscode-input-foreground)',
                  borderRadius: '4px',
                }}
              />
            </div>
            <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
              <button className="bmad-command-button" onClick={() => setShowDialog(false)}>
                {t('common.cancel', { defaultValue: 'Cancel' })}
              </button>
              <button className="bmad-command-button primary" onClick={handleStart} disabled={!description.trim()}>
                {t('chat.debate.start', { defaultValue: 'Start Debate' })}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
