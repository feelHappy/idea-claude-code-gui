/**
 * useHarnessIntegration - Manages Harness knowledge system status & install.
 *
 * Bridge events (JS → Java):
 *   get_harness_status
 *   install_harness
 *
 * Window callbacks (Java → JS):
 *   window.updateHarnessStatus
 *   window.updateHarnessInstall
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { sendToJava } from '../../../utils/bridge.js';
import type { HarnessStatus, HarnessToolbarProps } from '../HarnessBar.js';

function createDefaultHarnessStatus(): HarnessStatus {
  return { state: 'loading' };
}

function safeParse<T = unknown>(json: string): T | null {
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

export function useHarnessIntegration(): HarnessToolbarProps {
  const [status, setStatus] = useState<HarnessStatus>(createDefaultHarnessStatus);
  const [installLog, setInstallLog] = useState('');
  const [installing, setInstalling] = useState(false);
  const mountedRef = useRef(true);

  // Request status from Java backend
  const requestStatus = useCallback(() => {
    setStatus((prev) => ({ ...prev, state: 'loading' }));
    sendToJava('get_harness_status', '');
  }, []);

  // Install harness
  const handleInstall = useCallback(() => {
    setInstalling(true);
    setInstallLog('');
    sendToJava('install_harness', '{}');
  }, []);

  // Register window callbacks
  useEffect(() => {
    mountedRef.current = true;

    const prevUpdateStatus = (window as any).updateHarnessStatus;
    const prevUpdateInstall = (window as any).updateHarnessInstall;

    (window as any).updateHarnessStatus = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<HarnessStatus>(json);
      if (data) {
        setStatus(data);
      }
    };

    (window as any).updateHarnessInstall = (json: string) => {
      if (!mountedRef.current) return;
      const data = safeParse<{ type: string; message: string; success?: boolean }>(json);
      if (!data) return;

      if (data.type === 'progress') {
        setInstallLog((prev) => (prev ? prev + '\n' + data.message : data.message));
      } else if (data.type === 'result') {
        setInstalling(false);
        if (data.success) {
          setInstallLog((prev) => (prev ? prev + '\n✓ ' + data.message : '✓ ' + data.message));
        } else {
          setInstallLog((prev) => (prev ? prev + '\n✗ ' + data.message : '✗ ' + data.message));
        }
      }
    };

    // Initial status request
    requestStatus();

    return () => {
      mountedRef.current = false;
      (window as any).updateHarnessStatus = prevUpdateStatus;
      (window as any).updateHarnessInstall = prevUpdateInstall;
    };
  }, [requestStatus]);

  return {
    status,
    installLog,
    onRefresh: requestStatus,
    onInstall: handleInstall,
    installDisabled: installing,
  };
}
