package xyz.vvzvlad.popcornclaw;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The dialog's turn as the assistant screen sees it: the gateway's frames, the screen's own questions and a
 * lost connection in, events for the screen out, under the id the screen shows the turn by. The events are
 * those of the paired-PC bridge: "question" (a Telegram message, a turn the screen joins or a voice note from
 * the remote, whose text is not known yet), "answer" (each step and interim text a line, the turn's answer as
 * done with final, the turn end as done without it) and "answer_error". No Android main loop here: PicoLink
 * feeds it on the main thread and keeps the screen's timeout from firing while {@link #id} is not null.
 */
final class PicoTurn {
    /** A gateway frame as the screen reads it. */
    static final class Frame {
        enum Kind { USER, STEP, TEXT, FINAL, TURN_END, ERROR }

        final Kind kind;
        final String text;

        Frame(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        /** Null for a frame the screen does not show: typing, thoughts, anything unreadable. */
        static Frame parse(String json) {
            JSONObject f;
            try {
                f = new JSONObject(json);
            } catch (JSONException e) {
                return null;
            }
            JSONObject p = f.optJSONObject("payload");
            if (p == null) return null;
            if ("error".equals(f.optString("type"))) return new Frame(Kind.ERROR, p.optString("message"));
            if (!"message.create".equals(f.optString("type"))) return null;
            String content = p.optString("content");
            switch (p.optString("kind")) {
                case "user":
                    return new Frame(Kind.USER, content);
                case "step":
                    return new Frame(Kind.STEP, content);
                case "final":
                    return new Frame(Kind.FINAL, content);
                case "turn_end":
                    return new Frame(Kind.TURN_END, "");
                case "":
                    return content.isEmpty() ? null : new Frame(Kind.TEXT, content);
                default:
                    return null;
            }
        }
    }

    /** An event for the screen: "question", "answer" or "answer_error" with its data. */
    static final class Event {
        final String type;
        final JSONObject data;

        Event(String type, JSONObject data) {
            this.type = type;
            this.data = data;
        }
    }

    private final Supplier<String> newId;
    // The running turn's id; null between turns.
    private String id;
    // A line of this turn is on the screen, so the next one starts on a new line.
    private boolean lineShown;

    /** {@code newId} names the turns the screen did not begin. */
    PicoTurn(Supplier<String> newId) {
        this.newId = newId;
    }

    /** The running turn's id, null between turns. */
    String id() {
        return id;
    }

    /**
     * The screen sent its question {@code questionId}, or PicoLink a voice note from the remote. The gateway
     * steers it into a running turn, which the screen then shows under the new id.
     */
    void ask(String questionId) {
        id = questionId;
        lineShown = false;
    }

    /** The screen's question did not leave: its turn ends with {@code message}. */
    List<Event> failed(String message) {
        return end(new Event("answer_error", error(message)));
    }

    /** A screen that opens in the middle of a turn joins it. */
    List<Event> join() {
        if (id == null) return Collections.emptyList();
        lineShown = false;
        return Collections.singletonList(new Event("question", question("")));
    }

    List<Event> frame(String json) {
        Frame f = Frame.parse(json);
        if (f == null) return Collections.emptyList();
        switch (f.kind) {
            case USER:
                // A Telegram message: a turn begins, or the running one picks it up.
                if (id == null) id = newId.get();
                lineShown = false;
                return Collections.singletonList(new Event("question", question(f.text)));
            case STEP:
            case TEXT:
                if (id == null) return alone(f.text);
                Event line = new Event("answer", answer(lineShown ? "\n" + f.text : f.text, false));
                lineShown = true;
                return Collections.singletonList(line);
            case FINAL:
                if (id == null) return alone(f.text);
                return end(new Event("answer", done(f.text)));
            case TURN_END:
                return id == null ? Collections.emptyList() : end(new Event("answer", answer("", true)));
            case ERROR:
                return id == null ? Collections.emptyList() : end(new Event("answer_error", error(f.text)));
        }
        return Collections.emptyList();
    }

    /** The connection to the gateway dropped; the running turn's frames are lost with it. */
    List<Event> lost(String why) {
        return id == null ? Collections.emptyList() : end(new Event("answer_error", error("нет связи с агентом: " + why)));
    }

    /** The empty answer that keeps the screen's own timeout from firing; null between turns. */
    Event keepalive() {
        return id == null ? null : new Event("answer", answer("", false));
    }

    /** A message outside a turn the screen saw begin: shown as a turn of its own, already over. */
    private List<Event> alone(String text) {
        id = newId.get();
        Event q = new Event("question", question(""));
        Event a = new Event("answer", done(text));
        id = null;
        return Arrays.asList(q, a);
    }

    private List<Event> end(Event e) {
        id = null;
        return Collections.singletonList(e);
    }

    private JSONObject question(String text) {
        JSONObject q = new JSONObject();
        put(q, "id", id);
        put(q, "text", text);
        return q;
    }

    private JSONObject answer(String text, boolean done) {
        JSONObject a = new JSONObject();
        put(a, "id", id);
        put(a, "text", text);
        put(a, "done", done);
        return a;
    }

    private JSONObject done(String text) {
        JSONObject a = answer("", true);
        put(a, "final", text);
        return a;
    }

    private JSONObject error(String message) {
        JSONObject e = new JSONObject();
        put(e, "id", id);
        put(e, "message", message);
        return e;
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
