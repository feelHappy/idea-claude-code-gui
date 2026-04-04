import { useCallback } from 'react';

interface ToggleSwitchProps {
  checked?: boolean;
  size?: 'small' | 'default';
  onClick?: (checked: boolean, e: React.MouseEvent) => void;
}

/**
 * Lightweight toggle switch component — drop-in replacement for antd Switch.
 */
const ToggleSwitch = ({ checked = false, size = 'default', onClick }: ToggleSwitchProps) => {
  const isSmall = size === 'small';
  const width = isSmall ? 28 : 44;
  const height = isSmall ? 16 : 22;
  const dotSize = isSmall ? 12 : 18;
  const dotOffset = 2;

  const handleClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onClick?.(!checked, e);
    },
    [checked, onClick],
  );

  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={handleClick}
      style={{
        position: 'relative',
        display: 'inline-flex',
        alignItems: 'center',
        width,
        height,
        padding: 0,
        border: 'none',
        borderRadius: height / 2,
        cursor: 'pointer',
        background: checked
          ? 'var(--vscode-button-background, #007acc)'
          : 'var(--vscode-input-border, rgba(255,255,255,0.2))',
        transition: 'background 0.2s',
        flexShrink: 0,
      }}
    >
      <span
        style={{
          position: 'absolute',
          top: dotOffset,
          left: checked ? width - dotSize - dotOffset : dotOffset,
          width: dotSize,
          height: dotSize,
          borderRadius: '50%',
          background: '#fff',
          transition: 'left 0.2s',
          boxShadow: '0 1px 2px rgba(0,0,0,0.3)',
        }}
      />
    </button>
  );
};

export default ToggleSwitch;
