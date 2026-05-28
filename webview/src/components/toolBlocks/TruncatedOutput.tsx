import { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/** Maximum lines to render before truncating. */
const MAX_VISIBLE_LINES = 300;
/** Lines to show from the tail when truncating. */
const TAIL_LINES = 50;

interface TruncatedOutputProps {
  content: string;
  isError?: boolean;
}

/**
 * Renders tool output with line truncation.
 * Shows head + tail with an expand button when output exceeds MAX_VISIBLE_LINES.
 */
export default function TruncatedOutput({ content }: TruncatedOutputProps) {
  const { t } = useTranslation();
  const [showFull, setShowFull] = useState(false);

  const { display, totalLines, truncated } = useMemo(() => {
    if (!content) return { display: '', totalLines: 0, truncated: false };
    const lines = content.split('\n');
    const count = lines.length;
    if (count <= MAX_VISIBLE_LINES || showFull) {
      return { display: content, totalLines: count, truncated: false };
    }
    const head = lines.slice(0, MAX_VISIBLE_LINES - TAIL_LINES).join('\n');
    const tail = lines.slice(-TAIL_LINES).join('\n');
    return { display: head + '\n\n…\n\n' + tail, totalLines: count, truncated: true };
  }, [content, showFull]);

  if (!display) return null;

  return (
    <>
      <pre className="bash-output-text">{display}</pre>
      {truncated && (
        <button
          className="bash-output-expand-btn"
          onClick={(e) => { e.stopPropagation(); setShowFull(true); }}
        >
          {t('tools.showAllLines', { count: totalLines })}
        </button>
      )}
    </>
  );
}

export { MAX_VISIBLE_LINES, TAIL_LINES };
