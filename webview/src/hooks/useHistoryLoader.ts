import { useEffect, useRef } from 'react';
import { sendBridgeEvent } from '../utils/bridge';

export interface UseHistoryLoaderOptions {
  currentView: 'chat' | 'history' | 'settings';
  currentProvider: string;
}

export function useHistoryLoader(options: UseHistoryLoaderOptions): void {
  const { currentView, currentProvider } = options;
  // Track the previous view to detect transitions into the history tab
  const prevViewRef = useRef<string>(currentView);

  useEffect(() => {
    const prevView = prevViewRef.current;
    prevViewRef.current = currentView;

    if (currentView !== 'history') {
      return;
    }

    // Clear cache when entering history tab from another view, or when switching
    // provider while already on history tab. This ensures that sessions created
    // right before the user navigates here are always picked up.
    const shouldClearCache = prevView !== 'history';

    let historyRetryCount = 0;
    const MAX_HISTORY_RETRIES = 30;
    let currentTimer: ReturnType<typeof setTimeout> | null = null;

    const requestHistoryData = () => {
      if (window.sendToJava) {
        if (shouldClearCache) {
          // deep_search_history clears the cache and immediately triggers a
          // fresh load internally, so we don't need a separate load call.
          sendBridgeEvent('deep_search_history', currentProvider);
        } else {
          // Provider switched while already on the history tab: just reload.
          sendBridgeEvent('load_history_data', currentProvider);
        }
      } else {
        historyRetryCount++;
        if (historyRetryCount < MAX_HISTORY_RETRIES) {
          currentTimer = setTimeout(requestHistoryData, 100);
        } else {
          console.warn('[Frontend] Failed to load history data: bridge not available after', MAX_HISTORY_RETRIES, 'retries');
        }
      }
    };

    currentTimer = setTimeout(requestHistoryData, 50);

    return () => {
      if (currentTimer) {
        clearTimeout(currentTimer);
      }
    };
  }, [currentView, currentProvider]);
}
