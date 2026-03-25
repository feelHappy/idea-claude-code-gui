import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { FEATURE_GUIDE_CONTENT } from '../version/featureGuide';
import { APP_VERSION } from '../version/version';

interface FeatureGuideDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenChangelog: () => void;
}

const FeatureGuideDialog = ({
  isOpen,
  onClose,
  onOpenChangelog,
}: FeatureGuideDialogProps) => {
  const { i18n } = useTranslation();
  const language = i18n.language.toLowerCase().startsWith('zh') ? 'zh' : 'en';
  const content = FEATURE_GUIDE_CONTENT[language];

  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div className="feature-guide-overlay">
      <div className="feature-guide-dialog">
        <div className="feature-guide-header">
          <div className="feature-guide-title-area">
            <span className="feature-guide-badge">{content.badge}</span>
            <h3>{content.title}</h3>
            <span className="feature-guide-version">v{APP_VERSION}</span>
            <p className="feature-guide-subtitle">{content.subtitle}</p>
          </div>
          <button
            className="feature-guide-close-btn"
            onClick={onClose}
            aria-label={content.closeButton}
          >
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="feature-guide-body">
          <p className="feature-guide-summary">{content.summary}</p>

          <div className="feature-guide-grid">
            {content.cards.map((card) => (
              <div key={card.title} className="feature-guide-card">
                <div className="feature-guide-card-head">
                  <span className={`feature-guide-card-icon codicon ${card.icon}`} aria-hidden="true" />
                  <span className="feature-guide-card-title">{card.title}</span>
                </div>
                <p className="feature-guide-card-description">{card.description}</p>
              </div>
            ))}
          </div>

          <div className="feature-guide-section">
            <h4>{content.stepsTitle}</h4>
            <div className="feature-guide-step-list">
              {content.steps.map((step, index) => (
                <div key={step.title} className="feature-guide-step">
                  <span className="feature-guide-step-index">{index + 1}</span>
                  <div className="feature-guide-step-content">
                    <div className="feature-guide-step-title">{step.title}</div>
                    <div className="feature-guide-step-description">{step.description}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="feature-guide-section">
            <h4>{content.notesTitle}</h4>
            <ul className="feature-guide-note-list">
              {content.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </div>
        </div>

        <div className="feature-guide-footer">
          <button className="feature-guide-btn" onClick={onOpenChangelog}>
            {content.changelogButton}
          </button>
          <button className="feature-guide-btn primary" onClick={onClose}>
            {content.closeButton}
          </button>
        </div>
      </div>
    </div>
  );
};

export default FeatureGuideDialog;
