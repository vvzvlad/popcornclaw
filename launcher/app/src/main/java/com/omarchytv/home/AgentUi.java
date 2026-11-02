package com.omarchytv.home;

import android.content.Context;

/** Status glyphs, colours and words for the agent monitor, from the active Omarchy theme. */
final class AgentUi {
    private AgentUi() {
    }

    static String glyph(String status) {
        switch (status) {
            case "done": return "✓";
            case "error": return "✗";
            case "idle": return "○";
            case "waiting": return "!";
            default: return "●";   // thinking, running
        }
    }

    static int color(String status) {
        Theme t = Theme.cur();
        switch (status) {
            case "thinking": return t.accent2;
            case "running": return t.accent;
            case "waiting": return t.yellow;
            case "done": return t.green;
            case "error": return t.red;
            default: return t.fgDim;   // idle
        }
    }

    static String word(Context c, String status) {
        int r;
        switch (status) {
            case "thinking": r = R.string.agent_st_thinking; break;
            case "running": r = R.string.agent_st_running; break;
            case "waiting": r = R.string.agent_st_waiting; break;
            case "done": r = R.string.agent_st_done; break;
            case "error": r = R.string.agent_st_error; break;
            default: r = R.string.agent_st_idle;
        }
        return c.getString(r);
    }

    /** An agent_ask_error code in plain words (the PC's own message for codes the TV does not know). */
    static String error(Context c, String code, String message, boolean local) {
        switch (code) {
            case "disabled": return c.getString(R.string.agent_err_disabled);
            case "unsupported": return c.getString(R.string.agent_err_unsupported);
            case "busy": return c.getString(local ? R.string.agent_err_wait : R.string.agent_err_busy);
            case "not_found": return c.getString(R.string.agent_err_not_found);
            case "not_approved": return c.getString(R.string.agent_err_not_approved);
            case "cannot_confirm": return c.getString(R.string.agent_err_cannot_confirm);
            case "bad_request": return c.getString(R.string.agent_err_bad_request);
            case "timeout": return c.getString(R.string.agent_err_timeout);
            case "not_connected": return c.getString(R.string.agent_err_not_connected);
            case "failed": return message.isEmpty() ? c.getString(R.string.agent_err_failed) : c.getString(R.string.agent_err_failed) + " " + message;
            default: return message.isEmpty() ? code : message;
        }
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
