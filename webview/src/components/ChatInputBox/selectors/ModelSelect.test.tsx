import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelSelect } from './ModelSelect';
import type { ModelInfo } from '../types';
import { STORAGE_KEYS } from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.model ?? key,
  }),
}));

describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-sonnet-4-6',
    label: 'Sonnet 4.6',
    description: 'Sonnet 4.6 - Use the default model',
  };

  const codexModel: ModelInfo = {
    id: 'gpt-5.4',
    label: 'gpt-5.4',
    description: 'Latest frontier model with enhanced capabilities.',
  };

  beforeEach(() => {
    localStorage.clear();
  });

  it('reads the latest Claude model mapping after rerender', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-4' }),
    );

    const { rerender } = render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4');

    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-5' }),
    );

    rerender(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-5');
  });

  it('falls back to the global main mapping when no specific Claude mapping exists', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ main: 'glm-4.7' }),
    );

    render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4.7');
  });

  it('does not apply Claude model mapping to Codex models', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ main: 'glm-5.1' }),
    );

    render(
      <ModelSelect
        value={codexModel.id}
        onChange={vi.fn()}
        models={[codexModel]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('models.codex.gpt54.label');
    expect(screen.getByRole('button').textContent).not.toContain('glm-5.1');
  });
});
