import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

interface DebateDialogProps {
  visible: boolean;
  onClose: () => void;
  onStart: (topic: string, description: string, maxRounds?: number) => void;
}

export function DebateDialog({ visible, onClose, onStart }: DebateDialogProps) {
  const { t } = useTranslation();
  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');
  const [maxRounds, setMaxRounds] = useState(5);

  const handleStart = () => {
    if (!description.trim()) return;
    onStart(topic || t('chat.debate.title'), description, maxRounds);
    onClose();
    setTopic('');
    setDescription('');
  };

  useEffect(() => {
    if (!visible) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && description.trim()) {
        handleStart();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [visible, description, topic, maxRounds]);

  if (!visible) return null;

  return (
    <div className="debate-dialog-overlay" onClick={onClose}>
      <div className="debate-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="debate-dialog-header">
          <span className="codicon codicon-comment-discussion" />
          {t('chat.debate.dialog.title')}
        </div>
        <div className="debate-dialog-body">
          <input
            type="text"
            className="debate-dialog-input"
            placeholder={t('chat.debate.dialog.topicPlaceholder')}
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
          />
          <textarea
            className="debate-dialog-textarea"
            placeholder={t('chat.debate.dialog.descPlaceholder')}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={4}
            autoFocus
          />
          <div className="debate-dialog-options">
            <label className="debate-dialog-label">
              {t('chat.debate.dialog.maxRounds')}
            </label>
            <input
              type="number"
              className="debate-dialog-number"
              min={2}
              max={10}
              value={maxRounds}
              onChange={(e) => setMaxRounds(Number(e.target.value))}
            />
          </div>
        </div>
        <div className="debate-dialog-footer">
          <span className="debate-dialog-hint">Ctrl+Enter</span>
          <button className="bmad-command-button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="bmad-command-button primary" onClick={handleStart} disabled={!description.trim()}>
            {t('chat.debate.start')}
          </button>
        </div>
      </div>
    </div>
  );
}