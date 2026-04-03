import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

interface WaitingIndicatorProps {
  size?: number;
  /** Loading start timestamp (ms), used to maintain continuous timing across view switches */
  startTime?: number;
  /** Callback to retract (undo) the last user message before AI responds */
  onRetract?: () => void;
}

export const WaitingIndicator = ({ size = 18, startTime, onRetract }: WaitingIndicatorProps) => {
  const { t } = useTranslation();
  const [isRetracting, setIsRetracting] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(() => {
    if (startTime) {
      return Math.floor((Date.now() - startTime) / 1000);
    }
    return 0;
  });

  // Timer: track elapsed seconds (single interval, 1 re-render/sec)
  useEffect(() => {
    const timer = setInterval(() => {
      if (startTime) {
        setElapsedSeconds(Math.floor((Date.now() - startTime) / 1000));
      } else {
        setElapsedSeconds(prev => prev + 1);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [startTime]);

  // Format elapsed time: show "X seconds" under 60s, "X min Y sec" above 60s
  const formatElapsedTime = (seconds: number): string => {
    if (seconds < 60) {
      return `${seconds} ${t('common.seconds')}`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${t('chat.minutesAndSeconds', { minutes, seconds: remainingSeconds })}`;
  };

  return (
    <div className="waiting-indicator">
      <span className="waiting-spinner" style={{ width: size, height: size }} />
      <span className="waiting-text">
	        {t('chat.generatingResponse')}<span className="waiting-dots">...</span>
	        <span className="waiting-seconds">（{t('chat.elapsedTime', { time: formatElapsedTime(elapsedSeconds) })}）</span>
      </span>
      {onRetract && (
        <button
          type="button"
          className={`waiting-retract-btn${isRetracting ? ' retracting' : ''}`}
          onClick={() => {
            if (isRetracting) return;
            setIsRetracting(true);
            onRetract();
          }}
          disabled={isRetracting}
          title={t('rewrite.retractTooltip')}
        >
          {isRetracting ? t('rewrite.retracting', '撤回中...') : t('rewrite.retractTooltip')}
        </button>
      )}
    </div>
  );
};

export default WaitingIndicator;

