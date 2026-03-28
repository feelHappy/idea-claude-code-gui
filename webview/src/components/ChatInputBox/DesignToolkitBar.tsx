import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ImpeccableBar } from './ImpeccableBar.js';
import { UiUxProBar } from './UiUxProBar.js';
import type { ImpeccableToolbarProps, UiUxToolbarProps } from './types.js';

type DesignToolkitMode = 'uiUxPro' | 'impeccable';

interface DesignToolkitBarProps {
  uiUxPro?: UiUxToolbarProps;
  impeccable?: ImpeccableToolbarProps;
}

export function DesignToolkitBar({ uiUxPro, impeccable }: DesignToolkitBarProps) {
  const { t } = useTranslation();

  const availableModes = useMemo<Array<{
    id: DesignToolkitMode;
    label: string;
    content: React.ReactNode;
  }>>(() => {
    const modes: Array<{
      id: DesignToolkitMode;
      label: string;
      content: React.ReactNode;
    }> = [];

    if (uiUxPro) {
      modes.push({
        id: 'uiUxPro',
        label: t('chat.designToolkit.modes.uiUxPro', { defaultValue: 'UI UX Pro Max' }),
        content: <UiUxProBar {...uiUxPro} />,
      });
    }

    if (impeccable) {
      modes.push({
        id: 'impeccable',
        label: t('chat.designToolkit.modes.impeccable', { defaultValue: 'Impeccable' }),
        content: <ImpeccableBar {...impeccable} />,
      });
    }

    return modes;
  }, [impeccable, t, uiUxPro]);

  const [activeMode, setActiveMode] = useState<DesignToolkitMode | null>(availableModes[0]?.id ?? null);

  useEffect(() => {
    if (availableModes.length === 0) {
      if (activeMode !== null) {
        setActiveMode(null);
      }
      return;
    }

    if (!activeMode || !availableModes.some((mode) => mode.id === activeMode)) {
      setActiveMode(availableModes[0].id);
    }
  }, [activeMode, availableModes]);

  const activeModeItem = availableModes.find((mode) => mode.id === activeMode) ?? availableModes[0];
  if (!activeModeItem) {
    return null;
  }

  return (
    <div className="design-toolkit-bar">
      {availableModes.length > 1 ? (
        <div className="design-toolkit-modes" role="tablist" aria-label={t('chat.designToolkit.ariaLabel', { defaultValue: 'Design toolkit modes' })}>
          {availableModes.map((mode) => (
            <button
              key={mode.id}
              type="button"
              className={`design-toolkit-mode${mode.id === activeModeItem.id ? ' active' : ''}`}
              role="tab"
              aria-selected={mode.id === activeModeItem.id}
              onClick={() => setActiveMode(mode.id)}
            >
              {mode.label}
            </button>
          ))}
        </div>
      ) : null}

      <div className="design-toolkit-content">
        {activeModeItem.content}
      </div>
    </div>
  );
}

export default DesignToolkitBar;
