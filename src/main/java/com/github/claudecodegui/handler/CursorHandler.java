package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

import java.awt.Cursor;
import java.util.Map;
import javax.swing.JComponent;

/**
 * Handler for cursor change messages from the JS cursor tracker.
 * Maps CSS cursor values to Swing cursor types so JCEF on macOS
 * shows the correct mouse pointer.
 */
public class CursorHandler extends BaseMessageHandler {

    private static final String[] SUPPORTED_TYPES = {"cursor_change"};

    private static final Map<String, Integer> CSS_TO_SWING_CURSOR = Map.ofEntries(
        Map.entry("text", Cursor.TEXT_CURSOR),
        Map.entry("pointer", Cursor.HAND_CURSOR),
        Map.entry("crosshair", Cursor.CROSSHAIR_CURSOR),
        Map.entry("wait", Cursor.WAIT_CURSOR),
        Map.entry("progress", Cursor.WAIT_CURSOR),
        Map.entry("move", Cursor.MOVE_CURSOR),
        Map.entry("grab", Cursor.MOVE_CURSOR),
        Map.entry("grabbing", Cursor.MOVE_CURSOR),
        Map.entry("col-resize", Cursor.E_RESIZE_CURSOR),
        Map.entry("ew-resize", Cursor.E_RESIZE_CURSOR),
        Map.entry("e-resize", Cursor.E_RESIZE_CURSOR),
        Map.entry("w-resize", Cursor.E_RESIZE_CURSOR),
        Map.entry("row-resize", Cursor.N_RESIZE_CURSOR),
        Map.entry("ns-resize", Cursor.N_RESIZE_CURSOR),
        Map.entry("n-resize", Cursor.N_RESIZE_CURSOR),
        Map.entry("s-resize", Cursor.N_RESIZE_CURSOR),
        Map.entry("nesw-resize", Cursor.NE_RESIZE_CURSOR),
        Map.entry("ne-resize", Cursor.NE_RESIZE_CURSOR),
        Map.entry("sw-resize", Cursor.NE_RESIZE_CURSOR),
        Map.entry("nwse-resize", Cursor.NW_RESIZE_CURSOR),
        Map.entry("nw-resize", Cursor.NW_RESIZE_CURSOR),
        Map.entry("se-resize", Cursor.NW_RESIZE_CURSOR),
        Map.entry("not-allowed", Cursor.DEFAULT_CURSOR),
        Map.entry("no-drop", Cursor.DEFAULT_CURSOR),
        Map.entry("help", Cursor.HAND_CURSOR),
        Map.entry("zoom-in", Cursor.HAND_CURSOR),
        Map.entry("zoom-out", Cursor.HAND_CURSOR)
    );

    public CursorHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"cursor_change".equals(type)) {
            return false;
        }
        if (content == null || content.isEmpty()) {
            return true;
        }
        int swingCursorType = CSS_TO_SWING_CURSOR.getOrDefault(content, Cursor.DEFAULT_CURSOR);
        var browser = context.getBrowser();
        if (browser != null) {
            JComponent comp = browser.getComponent();
            if (comp != null) {
                comp.setCursor(Cursor.getPredefinedCursor(swingCursorType));
            }
        }
        return true;
    }
}
