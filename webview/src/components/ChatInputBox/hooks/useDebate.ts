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
}

export interface UseDebateResult {
  debateState: DebateState;
  rounds: DebateRound[];
  startDebate: (topic: string, description: string, maxRounds?: number) => void;
  stopDebate: () => void;
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

  const startDebate = useCallback((topic: string, description: string, maxRounds = 5) => {
    setRounds([]);
    sendToJava('start_debate', JSON.stringify({ topic, description, maxRounds }));
  }, []);

  const stopDebate = useCallback(() => {
    sendToJava('stop_debate', '');
  }, []);

  const refreshStatus = useCallback(() => {
    sendToJava('get_debate_status', '');
  }, []);

  useEffect(() => {
    mountedRef.current = true;

    const prevState = (window as any).updateDebateState;
    const prevRound = (window as any).updateDebateRound;
    const prevEvent = (window as any).updateDebateEvent;

    (window as any).updateDebateState = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<DebateState>(json);
      if (data) setDebateState(data);
    };

    (window as any).updateDebateRound = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<DebateRound>(json);
      if (data) setRounds((prev) => [...prev, data]);
    };

    (window as any).updateDebateEvent = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<{ type: string; message: string }>(json);
      if (data && data.type === 'error') {
        setDebateState((prev) => ({ ...prev, state: 'ERROR', error: data.message }));
      }
    };

    refreshStatus();

    return () => {
      mountedRef.current = false;
      (window as any).updateDebateState = prevState;
      (window as any).updateDebateRound = prevRound;
      (window as any).updateDebateEvent = prevEvent;
    };
  }, [refreshStatus]);

  return { debateState, rounds, startDebate, stopDebate, refreshStatus };
}
