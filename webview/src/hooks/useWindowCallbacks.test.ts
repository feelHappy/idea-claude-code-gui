import { act, renderHook } from '@testing-library/react';
import { useWindowCallbacks } from './useWindowCallbacks.js';
import type { UseWindowCallbacksOptions } from './useWindowCallbacks.js';
import type { ClaudeMessage } from '../types/index.js';

/**
 * Integration tests for useWindowCallbacks — verifies the real window callback
 * chain (historyLoadComplete, addErrorMessage, updateMessages guard, clearMessages,
 * setSessionId) rather than simulating state bits.
 */
describe('useWindowCallbacks integration', () => {
  const t = ((key: string) => key) as any;

  /** Build the full options object with vi.fn() stubs for every field. */
  const createOptions = (overrides?: Partial<UseWindowCallbacksOptions>): UseWindowCallbacksOptions => ({
    t,
    addToast: vi.fn(),
    clearToasts: vi.fn(),

    // State setters
    setMessages: vi.fn(),
    setStatus: vi.fn(),
    setLoading: vi.fn(),
    setLoadingStartTime: vi.fn(),
    setIsThinking: vi.fn(),
    setExpandedThinking: vi.fn(),
    setStreamingActive: vi.fn(),
    setHistoryData: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setPermissionMode: vi.fn(),
    setClaudePermissionMode: vi.fn(),
    setCodexPermissionMode: vi.fn(),
    setSelectedClaudeModel: vi.fn(),
    setSelectedCodexModel: vi.fn(),
    setProviderConfigVersion: vi.fn(),
    setActiveProviderConfig: vi.fn(),
    setClaudeSettingsAlwaysThinkingEnabled: vi.fn(),
    setStreamingEnabledSetting: vi.fn(),
    setSendShortcut: vi.fn(),
    setAutoOpenFileEnabled: vi.fn(),
    setSdkStatus: vi.fn(),
    setSdkStatusLoaded: vi.fn(),
    setIsRewinding: vi.fn(),
    setRewindDialogOpen: vi.fn(),
    setCurrentRewindRequest: vi.fn(),
    setContextInfo: vi.fn(),
    setSelectedAgent: vi.fn(),

    // Refs
    currentProviderRef: { current: 'claude' },
    messagesContainerRef: { current: null },
    isUserAtBottomRef: { current: true },
    userPausedRef: { current: false },
    suppressNextStatusToastRef: { current: false },
    streamingContentRef: { current: '' },
    isStreamingRef: { current: false },
    useBackendStreamingRenderRef: { current: false },
    autoExpandedThinkingKeysRef: { current: new Set<string>() },
    streamingTextSegmentsRef: { current: [] },
    activeTextSegmentIndexRef: { current: -1 },
    streamingThinkingSegmentsRef: { current: [] },
    activeThinkingSegmentIndexRef: { current: -1 },
    seenToolUseCountRef: { current: 0 },
    streamingMessageIndexRef: { current: -1 },
    streamingTurnIdRef: { current: -1 },
    turnIdCounterRef: { current: 0 },
    lastContentUpdateRef: { current: 0 },
    contentUpdateTimeoutRef: { current: null },
    lastThinkingUpdateRef: { current: 0 },
    thinkingUpdateTimeoutRef: { current: null },

    // Functions
    findLastAssistantIndex: (msgs: ClaudeMessage[]) =>
      msgs.reduce((acc, m, i) => (m.type === 'assistant' ? i : acc), -1),
    extractRawBlocks: () => [],
    getOrCreateStreamingAssistantIndex: () => 0,
    patchAssistantForStreaming: (msg: ClaudeMessage) => msg,
    syncActiveProviderModelMapping: vi.fn(),
    openPermissionDialog: vi.fn(),
    openAskUserQuestionDialog: vi.fn(),
    openPlanApprovalDialog: vi.fn(),

    // B-011
    customSessionTitleRef: { current: null },
    currentSessionIdRef: { current: null },
    updateHistoryTitle: vi.fn(),

    ...overrides,
  });

  beforeEach(() => {
    (window as any).__sessionTransitioning = false;
    (window as any).__sessionTransitionToken = null;
    (window as any).__deniedToolIds = new Set();
    window.sendToJava = vi.fn();
  });

  // ===== historyLoadComplete releases transition guard =====

  it('historyLoadComplete releases __sessionTransitioning guard', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Simulate: beginSessionTransition sets guard
    (window as any).__sessionTransitioning = true;
    (window as any).__sessionTransitionToken = 'transition-1';

    // Simulate: Java calls historyLoadComplete on success
    act(() => {
      (window as any).historyLoadComplete();
    });

    expect((window as any).__sessionTransitioning).toBe(false);
    expect((window as any).__sessionTransitionToken).toBeNull();
  });

  // ===== setSessionId releases transition guard =====

  it('setSessionId releases __sessionTransitioning guard', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    (window as any).__sessionTransitioning = true;
    (window as any).__sessionTransitionToken = 'transition-2';

    act(() => {
      (window as any).setSessionId('new-session-123');
    });

    expect((window as any).__sessionTransitioning).toBe(false);
    expect((window as any).__sessionTransitionToken).toBeNull();
    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('new-session-123');
  });

  // ===== updateMessages is blocked during transition =====

  it('updateMessages is silently dropped while __sessionTransitioning is true', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    (window as any).__sessionTransitioning = true;

    const staleMessages: ClaudeMessage[] = [
      { type: 'assistant', content: 'stale content', timestamp: new Date().toISOString() },
    ];

    act(() => {
      (window as any).updateMessages(JSON.stringify(staleMessages));
    });

    // setMessages should NOT be called because guard is active
    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  it('updateMessages works normally after guard is released', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Guard is NOT set
    expect((window as any).__sessionTransitioning).toBe(false);

    const freshMessages: ClaudeMessage[] = [
      { type: 'user', content: 'hello', timestamp: new Date().toISOString() },
    ];

    act(() => {
      (window as any).updateMessages(JSON.stringify(freshMessages));
    });

    // setMessages SHOULD be called
    expect(opts.setMessages).toHaveBeenCalled();
  });

  it('updateMessages keeps a fresh tool_use assistant instead of overwriting it with previous commentary', () => {
    const setMessages = vi.fn();
    const extractRawBlocks = (raw: unknown) => {
      if (!raw || typeof raw !== 'object') return [];
      const rawObj = raw as any;
      const blocks = rawObj.message?.content ?? rawObj.content;
      return Array.isArray(blocks) ? blocks : [];
    };
    const opts = createOptions({ setMessages, extractRawBlocks });
    renderHook(() => useWindowCallbacks(opts));

    const prompt: ClaudeMessage = {
      type: 'user',
      content: '检查代码',
      timestamp: '2024-01-01T10:00:00.000Z',
    };
    const commentary: ClaudeMessage = {
      type: 'assistant',
      content: '我会先按代码评审的方式看当前未提交改动。',
      timestamp: '2024-01-01T10:00:01.000Z',
      raw: {
        message: {
          content: [{ type: 'text', text: '我会先按代码评审的方式看当前未提交改动。' }],
        },
      } as any,
    };
    const toolUse: ClaudeMessage = {
      type: 'assistant',
      content: '',
      timestamp: '2024-01-01T10:00:02.000Z',
      raw: {
        message: {
          content: [{ type: 'tool_use', id: 'tool-1', name: 'shell', input: { command: 'git status --short' } }],
        },
      } as any,
    };

    act(() => {
      (window as any).updateMessages(JSON.stringify([prompt, commentary, toolUse]));
    });

    const updater = setMessages.mock.calls[0]?.[0] as ((prev: ClaudeMessage[]) => ClaudeMessage[]);
    expect(typeof updater).toBe('function');

    const result = updater([prompt, commentary]);
    expect(result).toHaveLength(3);
    expect(result[2].content).toBe('');
    expect(extractRawBlocks(result[2].raw)[0]?.type).toBe('tool_use');
  });

  it('updateMessages still preserves a longer previous text-only assistant snapshot', () => {
    const setMessages = vi.fn();
    const opts = createOptions({ setMessages });
    renderHook(() => useWindowCallbacks(opts));

    const prompt: ClaudeMessage = {
      type: 'user',
      content: 'hello',
      timestamp: '2024-01-01T10:00:00.000Z',
    };
    const shorterAssistant: ClaudeMessage = {
      type: 'assistant',
      content: 'partial',
      timestamp: '2024-01-01T10:00:01.000Z',
      raw: {
        message: {
          content: [{ type: 'text', text: 'partial' }],
        },
      } as any,
    };

    act(() => {
      (window as any).updateMessages(JSON.stringify([prompt, shorterAssistant]));
    });

    const updater = setMessages.mock.calls[0]?.[0] as ((prev: ClaudeMessage[]) => ClaudeMessage[]);
    expect(typeof updater).toBe('function');

    const previousLongerAssistant: ClaudeMessage = {
      type: 'assistant',
      content: 'partial response with more detail',
      timestamp: '2024-01-01T10:00:01.000Z',
      raw: {
        message: {
          content: [{ type: 'text', text: 'partial response with more detail' }],
        },
      } as any,
    };

    const result = updater([prompt, previousLongerAssistant]);
    expect(result[1]).toBe(previousLongerAssistant);
    expect(result[1].content).toBe('partial response with more detail');
  });

  it('updateStatus does not release an active transition token', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    (window as any).__sessionTransitioning = true;
    (window as any).__sessionTransitionToken = 'transition-status';

    act(() => {
      (window as any).updateStatus('warming runtime');
    });

    expect((window as any).__sessionTransitioning).toBe(true);
    expect((window as any).__sessionTransitionToken).toBe('transition-status');
    expect(opts.setStatus).toHaveBeenCalledWith('warming runtime');
  });

  // ===== addErrorMessage only shows toast (no status) =====

  it('addErrorMessage shows toast but does not set status', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).addErrorMessage('Something went wrong');
    });

    expect(opts.addToast).toHaveBeenCalledWith('Something went wrong', 'error');
    expect(opts.setStatus).not.toHaveBeenCalled();
  });

  // ===== clearMessages resets all transient UI state =====

  it('clearMessages resets streaming refs, loading, thinking, and status', () => {
    const isStreamingRef = { current: true };
    const streamingContentRef = { current: 'partial content' };
    const streamingMessageIndexRef = { current: 3 };
    const opts = createOptions({
      isStreamingRef,
      streamingContentRef,
      streamingMessageIndexRef,
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).clearMessages();
    });

    expect(opts.setMessages).toHaveBeenCalledWith([]);
    expect(opts.clearToasts).toHaveBeenCalled();
    expect(opts.setStatus).toHaveBeenCalledWith('');
    expect(opts.setLoading).toHaveBeenCalledWith(false);
    expect(opts.setIsThinking).toHaveBeenCalledWith(false);
    expect(opts.setStreamingActive).toHaveBeenCalledWith(false);
    expect(isStreamingRef.current).toBe(false);
    expect(streamingContentRef.current).toBe('');
    expect(streamingMessageIndexRef.current).toBe(-1);
  });

  // ===== clearMessages resets turn tracking refs =====

  it('clearMessages resets streamingTurnIdRef but preserves turnIdCounterRef', () => {
    const streamingTurnIdRef = { current: 5 };
    const turnIdCounterRef = { current: 10 };
    const opts = createOptions({
      streamingTurnIdRef,
      turnIdCounterRef,
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).clearMessages();
    });

    // Turn ID should be reset to -1 (no active streaming turn)
    expect(streamingTurnIdRef.current).toBe(-1);
    // Counter stays monotonically increasing (NOT reset) so React keys stay unique across sessions
    expect(turnIdCounterRef.current).toBe(10);
  });

  // ===== Full failure scenario: load history fails, guard is released, new messages work =====

  it('full flow: history load failure releases guard so new messages can arrive', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Step 1: Frontend begins session transition
    (window as any).__sessionTransitioning = true;

    // Step 2: During transition, stale messages are blocked
    act(() => {
      (window as any).updateMessages(JSON.stringify([{ type: 'assistant', content: 'stale' }]));
    });
    expect(opts.setMessages).not.toHaveBeenCalled();

    // Step 3: Java calls historyLoadComplete (failure path also calls this before addErrorMessage)
    act(() => {
      (window as any).historyLoadComplete();
    });
    expect((window as any).__sessionTransitioning).toBe(false);

    // Step 4: Java calls addErrorMessage
    act(() => {
      (window as any).addErrorMessage('Failed to load session: network error');
    });
    expect(opts.addToast).toHaveBeenCalledWith('Failed to load session: network error', 'error');

    // Step 5: After guard release, new messages work
    act(() => {
      (window as any).updateMessages(
        JSON.stringify([{ type: 'user', content: 'new message' }])
      );
    });
    expect(opts.setMessages).toHaveBeenCalled();
  });

  it('onStreamEnd finalizes the current turn even when streamingMessageIndexRef is stale', () => {
    const setMessages = vi.fn();
    const opts = createOptions({
      setMessages,
      streamingContentRef: { current: 'final summary content' },
      isStreamingRef: { current: true },
      streamingMessageIndexRef: { current: -1 },
      streamingTurnIdRef: { current: 42 },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).onStreamEnd();
    });

    const updater = setMessages.mock.calls[0]?.[0] as ((prev: ClaudeMessage[]) => ClaudeMessage[]);
    expect(typeof updater).toBe('function');

    const prev: ClaudeMessage[] = [
      { type: 'user', content: 'question', timestamp: new Date().toISOString() },
      {
        type: 'assistant',
        content: 'partial',
        timestamp: new Date().toISOString(),
        isStreaming: true,
        __turnId: 42,
        raw: { message: { content: [] } } as any,
      },
    ];
    const result = updater(prev);
    expect(result[1].content).toBe('final summary content');
    expect(result[1].isStreaming).toBe(false);
  });

  it('updateMessages does not reuse a previous-turn assistant as the current streaming target', () => {
    const setMessages = vi.fn();
    const opts = createOptions({
      setMessages,
      isStreamingRef: { current: true },
      useBackendStreamingRenderRef: { current: false },
      streamingContentRef: { current: 'current turn text' },
      streamingMessageIndexRef: { current: 3 },
      streamingTurnIdRef: { current: 2 },
      extractRawBlocks: (raw) => {
        if (!raw || typeof raw !== 'object') return [];
        const rawObj = raw as any;
        const blocks = rawObj.message?.content ?? rawObj.content;
        return Array.isArray(blocks) ? blocks : [];
      },
    });
    renderHook(() => useWindowCallbacks(opts));

    const previousTurnAssistant: ClaudeMessage = {
      type: 'assistant',
      content: 'previous summary',
      timestamp: '2024-01-01T10:00:01.000Z',
      raw: {
        message: {
          content: [{ type: 'tool_use', id: 'tool-1', name: 'shell', input: {} }],
        },
      } as any,
    };
    const currentPrompt: ClaudeMessage = {
      type: 'user',
      content: 'second prompt',
      timestamp: '2024-01-01T10:00:02.000Z',
    };

    act(() => {
      (window as any).updateMessages(JSON.stringify([
        { type: 'user', content: 'first prompt', timestamp: '2024-01-01T10:00:00.000Z' },
        previousTurnAssistant,
        currentPrompt,
      ]));
    });

    const updater = setMessages.mock.calls[0]?.[0] as ((prev: ClaudeMessage[]) => ClaudeMessage[]);
    expect(typeof updater).toBe('function');

    const prev: ClaudeMessage[] = [
      { type: 'user', content: 'first prompt', timestamp: '2024-01-01T10:00:00.000Z' },
      previousTurnAssistant,
      currentPrompt,
      {
        type: 'assistant',
        content: 'current turn text',
        timestamp: '2024-01-01T10:00:03.000Z',
        isStreaming: true,
        __turnId: 2,
      },
    ];

    const result = updater(prev);
    expect(result).toHaveLength(4);
    expect(result[1].content).toBe('previous summary');
    expect(result[1].__turnId).toBeUndefined();
    expect(result[3].content).toBe('current turn text');
    expect(result[3].__turnId).toBe(2);
  });

  it('updateMessages in non-streaming mode does not preserve previous-turn assistant identity', () => {
    const setMessages = vi.fn();
    const opts = createOptions({
      setMessages,
      isStreamingRef: { current: false },
    });
    renderHook(() => useWindowCallbacks(opts));

    const parsedSnapshot: ClaudeMessage[] = [
      { type: 'user', content: 'first prompt', timestamp: '2024-01-01T10:00:00.000Z' },
      { type: 'assistant', content: 'previous turn summary', timestamp: '2024-01-01T10:00:01.000Z' },
      { type: 'user', content: 'second prompt', timestamp: '2024-01-01T10:00:02.000Z' },
      { type: 'assistant', content: 'new turn assistant', timestamp: '2024-01-01T10:00:03.000Z' },
    ];

    act(() => {
      (window as any).updateMessages(JSON.stringify(parsedSnapshot));
    });

    const updater = setMessages.mock.calls[0]?.[0] as ((prev: ClaudeMessage[]) => ClaudeMessage[]);
    expect(typeof updater).toBe('function');

    const prev: ClaudeMessage[] = [
      { type: 'user', content: 'first prompt', timestamp: '2024-01-01T10:00:00.000Z' },
      {
        type: 'assistant',
        content: 'previous turn summary',
        timestamp: '2024-01-01T09:59:59.000Z',
      },
      { type: 'user', content: 'second prompt', timestamp: '2024-01-01T10:00:02.000Z' },
      {
        type: 'assistant',
        content: 'older different assistant',
        timestamp: '2024-01-01T10:00:02.500Z',
      },
    ];

    const result = updater(prev);
    expect(result).toHaveLength(4);
    expect(result[3].content).toBe('new turn assistant');
    expect(result[3].timestamp).toBe('2024-01-01T10:00:03.000Z');
  });
});
