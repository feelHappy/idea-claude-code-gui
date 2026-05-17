import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { sendToJava } from '../../../utils/bridge.js';
import { useDebate } from './useDebate.js';

vi.mock('../../../utils/bridge.js', () => ({
  sendToJava: vi.fn(),
}));

const mockedSendToJava = vi.mocked(sendToJava);

describe('useDebate', () => {
  beforeEach(() => {
    delete (window as any).updateDebateState;
    delete (window as any).updateDebateRound;
    delete (window as any).updateDebateEvent;
    mockedSendToJava.mockClear();
  });

  it('startDebate sets optimistic active state and sends message', () => {
    const { result } = renderHook(() => useDebate());

    expect(result.current.debateState.active).toBe(false);
    expect(result.current.debateState.state).toBe('IDLE');

    act(() => {
      result.current.startDebate('Test Topic', 'Test description', 3);
    });

    // Optimistic: active immediately true, state STARTING
    expect(result.current.debateState.active).toBe(true);
    expect(result.current.debateState.state).toBe('STARTING');
    expect(result.current.debateState.topic).toBe('Test Topic');
    expect(mockedSendToJava).toHaveBeenCalledWith(
      'start_debate',
      JSON.stringify({ topic: 'Test Topic', description: 'Test description', maxRounds: 3 })
    );
  });

  it('error event preserves active:true from optimistic set', () => {
    const { result } = renderHook(() => useDebate());

    act(() => {
      result.current.startDebate('Topic', 'Desc', 5);
    });
    expect(result.current.debateState.active).toBe(true);

    // Simulate backend error via updateDebateEvent (partial update)
    act(() => {
      (window as any).updateDebateEvent(JSON.stringify({ type: 'error', message: 'No active session.' }));
    });

    // active should still be true (partial update doesn't overwrite it)
    expect(result.current.debateState.active).toBe(true);
    expect(result.current.debateState.state).toBe('ERROR');
    expect(result.current.debateState.error).toBe('No active session.');
  });

  it('updateDebateState full replacement works for terminal states', () => {
    const { result } = renderHook(() => useDebate());

    act(() => {
      result.current.startDebate('Topic', 'Desc', 5);
    });

    // Simulate backend sending CONSENSUS (full replacement, active: false)
    act(() => {
      (window as any).updateDebateState(JSON.stringify({
        active: false,
        state: 'CONSENSUS',
        topic: 'Topic',
        round: 2,
        maxRounds: 5,
        message: 'Consensus reached by Claude at round 2',
        filePath: '/tmp/debate.md',
      }));
    });

    expect(result.current.debateState.active).toBe(false);
    expect(result.current.debateState.state).toBe('CONSENSUS');
    expect(result.current.debateState.filePath).toBe('/tmp/debate.md');
  });

  it('dismissDebate resets to IDLE', () => {
    const { result } = renderHook(() => useDebate());

    act(() => {
      result.current.startDebate('Topic', 'Desc', 5);
    });
    expect(result.current.debateState.active).toBe(true);

    act(() => {
      result.current.dismissDebate();
    });

    expect(result.current.debateState.active).toBe(false);
    expect(result.current.debateState.state).toBe('IDLE');
    expect(result.current.rounds).toEqual([]);
  });

  it('rounds accumulate via updateDebateRound', () => {
    const { result } = renderHook(() => useDebate());

    act(() => {
      (window as any).updateDebateRound(JSON.stringify({ provider: 'Claude', content: 'Analysis...', round: 1 }));
    });
    act(() => {
      (window as any).updateDebateRound(JSON.stringify({ provider: 'Codex', content: 'Counter...', round: 1 }));
    });

    expect(result.current.rounds).toHaveLength(2);
    expect(result.current.rounds[0].provider).toBe('Claude');
    expect(result.current.rounds[1].provider).toBe('Codex');
  });
});
