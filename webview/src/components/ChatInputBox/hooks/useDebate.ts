import { useCallback, useEffect, useRef, useState } from 'react';
import { sendToJava } from '../../../utils/bridge.js';

export interface DebateState {
  active: boolean;
  state: string;
  topic?: string;
  round?: number;
  maxRounds?: number;
  filePath?: string;
  message?: string;
  error?: string;
}

export interface DebateRound {
  provider: string;
  content: string;
  round: number;
  streaming?: boolean;
}

export interface UseDebateResult {
  debateState: DebateState;
  rounds: DebateRound[];
  startDebate: (topic: string, description: string, maxRounds?: number) => void;
  stopDebate: () => void;
  dismissDebate: () => void;
  refreshStatus: () => void;
}

function createDefaultState(): DebateState {
  return { active: false, state: 'IDLE' };
}

function safeParse<T = unknown>(json: string): T | null {
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

export function useDebate(): UseDebateResult {
  const [debateState, setDebateState] = useState<DebateState>(createDefaultState);
  const [rounds, setRounds] = useState<DebateRound[]>([]);
  const mountedRef = useRef(true);
  const streamingRef = useRef<{ provider: string; round: number; content: string } | null>(null);

  const startDebate = useCallback((topic: string, description: string, maxRounds = 5) => {
    setRounds([]);
    streamingRef.current = null;
    setDebateState({ active: true, state: 'STARTING', topic, maxRounds });
    sendToJava('start_debate', JSON.stringify({ topic, description, maxRounds }));
  }, []);

  const stopDebate = useCallback(() => {
    sendToJava('stop_debate', '');
  }, []);

  const dismissDebate = useCallback(() => {
    setDebateState(createDefaultState());
    setRounds([]);
    streamingRef.current = null;
  }, []);

  const refreshStatus = useCallback(() => {
    sendToJava('get_debate_status', '');
  }, []);

  useEffect(() => {
    mountedRef.current = true;

    const prevState = (window as any).updateDebateState;
    const prevRound = (window as any).updateDebateRound;
    const prevEvent = (window as any).updateDebateEvent;
    const prevStream = (window as any).updateDebateStream;

    (window as any).updateDebateState = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<DebateState>(json);
      if (data) setDebateState(data);
    };

    (window as any).updateDebateStream = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<{ provider: string; delta: string; round: number }>(json);
      if (!data) return;

      const cur = streamingRef.current;
      if (cur && cur.provider === data.provider && cur.round === data.round) {
        cur.content += data.delta;
      } else {
        streamingRef.current = { provider: data.provider, round: data.round, content: data.delta };
      }

      const updated = streamingRef.current!;
      setRounds((prev) => {
        const idx = prev.findIndex((r) => r.provider === updated.provider && r.round === updated.round && r.streaming);
        const entry: DebateRound = { provider: updated.provider, content: updated.content, round: updated.round, streaming: true };
        if (idx >= 0) {
          const next = [...prev];
          next[idx] = entry;
          return next;
        }
        return [...prev, entry];
      });
    };

    (window as any).updateDebateRound = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<DebateRound>(json);
      if (!data) return;

      streamingRef.current = null;
      setRounds((prev) => {
        const idx = prev.findIndex((r) => r.provider === data.provider && r.round === data.round && r.streaming);
        const entry: DebateRound = { provider: data.provider, content: data.content, round: data.round };
        if (idx >= 0) {
          const next = [...prev];
          next[idx] = entry;
          return next;
        }
        return [...prev, entry];
      });
    };

    (window as any).updateDebateEvent = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<{ type: string; message: string }>(json);
      if (data && data.type === 'error') {
        setDebateState((prev) => ({
          ...prev,
          state: 'ERROR',
          error: data.message,
        }));
      }
    };

    refreshStatus();

    return () => {
      mountedRef.current = false;
      (window as any).updateDebateState = prevState;
      (window as any).updateDebateRound = prevRound;
      (window as any).updateDebateEvent = prevEvent;
      (window as any).updateDebateStream = prevStream;
    };
  }, [refreshStatus]);

  return { debateState, rounds, startDebate, stopDebate, dismissDebate, refreshStatus };
}
