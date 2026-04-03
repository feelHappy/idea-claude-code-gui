import React, { useState, useRef, useEffect } from 'react';

interface CollapsibleTextBlockProps {
  content: string;
}

const MAX_HEIGHT = 160; // Approx 7-8 lines

// Shared singleton ResizeObserver to avoid N observer instances for N blocks
const observerCallbacks = new Map<Element, () => void>();
let sharedObserver: ResizeObserver | null = null;

function getSharedObserver(): ResizeObserver {
  if (!sharedObserver) {
    sharedObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        observerCallbacks.get(entry.target)?.();
      }
    });
  }
  return sharedObserver;
}

const CollapsibleTextBlock: React.FC<CollapsibleTextBlockProps> = ({ content }) => {
  const [expanded, setExpanded] = useState(false);
  const [isOverflowing, setIsOverflowing] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = contentRef.current;
    if (!el) return;

    const checkHeight = () => {
      if (contentRef.current) {
        setIsOverflowing(contentRef.current.scrollHeight > MAX_HEIGHT);
      }
    };

    checkHeight();

    observerCallbacks.set(el, checkHeight);
    getSharedObserver().observe(el);

    return () => {
      observerCallbacks.delete(el);
      getSharedObserver().unobserve(el);
    };
  }, [content]);

  const toggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setExpanded(!expanded);
  };

  return (
    <div className={`collapsible-block ${expanded ? 'expanded' : 'collapsed'}`}>
      <div
        className="collapsible-content"
        ref={contentRef}
        style={{
            maxHeight: (expanded || !isOverflowing) ? 'none' : `${MAX_HEIGHT}px`,
            overflow: 'hidden'
        }}
      >
        <div className="plain-text-content">{content}</div>

        {/* Gradient overlay when collapsed */}
        {!expanded && isOverflowing && (
             <div className="collapse-overlay"></div>
        )}
      </div>

      {isOverflowing && (
        <div className="collapse-toggle" onClick={toggleExpand}>
            <span className="codicon codicon-chevron-down" style={{ transform: expanded ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }}></span>
        </div>
      )}
    </div>
  );
};

export default CollapsibleTextBlock;
