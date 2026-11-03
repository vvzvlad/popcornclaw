package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * How the assistant screen reads the agent's pico channel. The frames are those the gateway on the box sent
 * for two questions asked from the TV, recorded on the box; the Telegram and error frames are
 * the shapes shared-session.patch sends.
 */
public class PicoTurnTest {
    private static final String TYPING_START = "{\"type\": \"typing.start\", \"session_id\": \"tv\", \"timestamp\": 1793663512532}";
    private static final String TYPING_STOP = "{\"type\": \"typing.stop\", \"session_id\": \"tv\", \"timestamp\": 1793663515830}";
    private static final String STEP_KERNEL = "{\"type\": \"message.create\", \"session_id\": \"tv\", \"timestamp\": 1793663515831, "
            + "\"payload\": {\"content\": \"Смотрю версию ядра (`exec`)\", \"kind\": \"step\", "
            + "\"message_id\": \"2b2ad499-43db-44aa-a318-b35dbc8160f4\"}}";
    private static final String FINAL_KERNEL = "{\"type\": \"message.create\", \"session_id\": \"tv\", \"timestamp\": 1793663517468, "
            + "\"payload\": {\"content\": \"Ядро — 4.9.113.\", \"context_usage\": {\"compress_at_tokens\": 98304, "
            + "\"history_tokens\": 400, \"summarize_at_tokens\": 98304, \"total_tokens\": 131072, \"used_percent\": 17, "
            + "\"used_tokens\": 17670}, \"kind\": \"final\", \"message_id\": \"d9515f84-e20a-4dc2-a6a2-685e590af444\"}}";
    private static final String TURN_END = "{\"type\": \"message.create\", \"session_id\": \"tv\", \"timestamp\": 1793663517470, "
            + "\"payload\": {\"content\": \"\", \"kind\": \"turn_end\", \"message_id\": \"8e1022ea-2689-431b-80ad-7f044c42aa5a\"}}";
    private static final String STEP_UPTIME = "{\"type\": \"message.create\", \"session_id\": \"tv\", \"timestamp\": 1793663553662, "
            + "\"payload\": {\"content\": \"Проверяю аптайм после паузы (`exec`)\", \"kind\": \"step\", "
            + "\"message_id\": \"51593499-ad24-4bf6-bfbd-f311583ec8f0\"}}";
    private static final String FINAL_UPTIME = "{\"type\": \"message.create\", \"session_id\": \"tv\", \"timestamp\": 1793663563791, "
            + "\"payload\": {\"content\": \"up 10:43, load 4.33.\", \"context_usage\": {\"compress_at_tokens\": 98304, "
            + "\"history_tokens\": 556, \"summarize_at_tokens\": 98304, \"total_tokens\": 131072, \"used_percent\": 18, "
            + "\"used_tokens\": 17826}, \"kind\": \"final\", \"message_id\": \"9f2ee5da-bec5-4a9d-a109-f872df54ebe7\"}}";
    private static final String TELEGRAM_STOP = "{\"type\": \"message.create\", \"session_id\": \"tv\", "
            + "\"payload\": {\"content\": \"хватит, просто скажи версию\", \"kind\": \"user\", \"message_id\": \"u1\"}}";
    private static final String INTERIM = "{\"type\": \"message.create\", \"session_id\": \"tv\", "
            + "\"payload\": {\"content\": \"Context window exceeded. Compressing history and retrying...\", \"message_id\": \"i1\"}}";
    private static final String GATEWAY_ERROR = "{\"type\": \"error\", \"payload\": {\"code\": \"empty_content\", \"message\": \"empty content\"}}";

    // Turns the screen did not begin are named t1, t2, ...
    private int named;
    private final PicoTurn turn = new PicoTurn(() -> "t" + ++named);
    private final List<String> screen = new ArrayList<>();

    /** Feeds a frame and records the events as "type id text|final|message done". */
    private void frame(String json) {
        record(turn.frame(json));
    }

    private void record(List<PicoTurn.Event> events) {
        for (PicoTurn.Event e : events) screen.add(describe(e));
    }

    private static String describe(PicoTurn.Event e) {
        JSONObject d = e.data;
        switch (e.type) {
            case "question":
                return "question " + d.optString("id") + " " + d.optString("text");
            case "answer":
                if (!d.optBoolean("done")) return "answer " + d.optString("id") + " " + d.optString("text");
                return d.has("final") ? "done " + d.optString("id") + " " + d.optString("final") : "done " + d.optString("id");
            default:
                return e.type + " " + d.optString("id") + " " + d.optString("message");
        }
    }

    private void assertScreen(String... want) {
        List<String> w = new ArrayList<>();
        for (String s : want) w.add(s);
        assertEquals(w, screen);
        screen.clear();
    }

    @Test
    public void twoQuestionsFromTheTV() {
        turn.ask("q1");
        frame(TYPING_START);
        frame(TYPING_STOP);
        frame(STEP_KERNEL);
        frame(FINAL_KERNEL);
        frame(TURN_END);
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "done q1 Ядро — 4.9.113.");
        assertNull(turn.id());

        turn.ask("q2");
        frame(TYPING_START);
        frame(TYPING_STOP);
        frame(STEP_UPTIME);
        frame(FINAL_UPTIME);
        frame(TURN_END);
        assertScreen("answer q2 Проверяю аптайм после паузы (`exec`)", "done q2 up 10:43, load 4.33.");
        assertNull(turn.id());
    }

    // Rule: the answer closes the turn, and the turn end that follows it is not a second ending.
    @Test
    public void answerClosesTheTurnAndTheTurnEndAfterItIsIgnored() {
        turn.ask("q1");
        frame(STEP_KERNEL);
        frame(FINAL_KERNEL);
        assertNull(turn.id());
        assertNull(turn.keepalive());
        frame(TURN_END);
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "done q1 Ядро — 4.9.113.");
    }

    // Rule: a Telegram message in the middle of a turn is picked up by that turn, under the same id.
    @Test
    public void telegramMessageMidTurnKeepsTheTurnId() {
        turn.ask("q1");
        frame(STEP_KERNEL);
        frame(TELEGRAM_STOP);
        assertEquals("q1", turn.id());
        frame(STEP_UPTIME);
        frame(FINAL_UPTIME);
        frame(TURN_END);
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "question q1 хватит, просто скажи версию",
                "answer q1 Проверяю аптайм после паузы (`exec`)", "done q1 up 10:43, load 4.33.");
    }

    // Rule: a question from the screen in the middle of a turn moves the turn to that question's id.
    @Test
    public void screenQuestionMidTurnMovesTheTurnToItsId() {
        turn.ask("q1");
        frame(STEP_KERNEL);
        turn.ask("q2");
        assertEquals("q2", turn.id());
        frame(STEP_UPTIME);
        frame(FINAL_UPTIME);
        frame(TURN_END);
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "answer q2 Проверяю аптайм после паузы (`exec`)",
                "done q2 up 10:43, load 4.33.");
    }

    // Rule: a step outside a turn the screen knows is shown as an answer of its own, already over.
    @Test
    public void stepOutsideAKnownTurnIsAFinishedAnswer() {
        frame(STEP_KERNEL);
        assertNull(turn.id());
        frame(FINAL_KERNEL);
        frame(TURN_END);
        assertScreen("question t1 ", "done t1 Смотрю версию ядра (`exec`)", "question t2 ", "done t2 Ядро — 4.9.113.");
    }

    @Test
    public void turnBegunInTelegram() {
        frame(TELEGRAM_STOP);
        assertEquals("t1", turn.id());
        frame(TYPING_START);
        frame(STEP_KERNEL);
        frame(INTERIM);
        frame(FINAL_KERNEL);
        frame(TURN_END);
        assertScreen("question t1 хватит, просто скажи версию", "answer t1 Смотрю версию ядра (`exec`)",
                "answer t1 \nContext window exceeded. Compressing history and retrying...", "done t1 Ядро — 4.9.113.");
    }

    @Test
    public void turnEndWithoutAnAnswerClosesTheTurn() {
        turn.ask("q1");
        frame(STEP_KERNEL);
        frame(TURN_END);
        assertNull(turn.id());
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "done q1");
    }

    @Test
    public void gatewayErrorEndsTheTurn() {
        turn.ask("q1");
        frame(GATEWAY_ERROR);
        assertNull(turn.id());
        frame(GATEWAY_ERROR);
        assertScreen("answer_error q1 empty content");
    }

    @Test
    public void lostConnectionEndsTheTurn() {
        record(turn.lost("closed 1001"));
        turn.ask("q1");
        record(turn.lost("closed 1001"));
        assertNull(turn.id());
        assertScreen("answer_error q1 нет связи с агентом: closed 1001");
    }

    @Test
    public void questionThatDidNotLeave() {
        turn.ask("q1");
        record(turn.failed("вопрос не ушёл агенту: соединение закрывается"));
        assertNull(turn.id());
        assertScreen("answer_error q1 вопрос не ушёл агенту: соединение закрывается");
    }

    @Test
    public void screenOpenedMidTurnJoinsIt() {
        record(turn.join());
        frame(TELEGRAM_STOP);
        frame(STEP_KERNEL);
        record(turn.join());
        frame(STEP_UPTIME);
        assertScreen("question t1 хватит, просто скажи версию", "answer t1 Смотрю версию ядра (`exec`)", "question t1 ",
                "answer t1 Проверяю аптайм после паузы (`exec`)");
    }

    @Test
    public void keepaliveOnlyWhileATurnRuns() {
        assertNull(turn.keepalive());
        turn.ask("q1");
        PicoTurn.Event e = turn.keepalive();
        assertEquals("answer", e.type);
        assertEquals("q1", e.data.optString("id"));
        assertEquals("", e.data.optString("text"));
        assertFalse(e.data.optBoolean("done"));
    }

    @Test
    public void linesOfATurnGoOnNewLines() {
        turn.ask("q1");
        frame(STEP_KERNEL);
        frame(STEP_UPTIME);
        assertScreen("answer q1 Смотрю версию ядра (`exec`)", "answer q1 \nПроверяю аптайм после паузы (`exec`)");
    }

    private static void assertFrame(PicoTurn.Frame.Kind kind, String text, String json) {
        PicoTurn.Frame f = PicoTurn.Frame.parse(json);
        assertEquals(json, kind, f == null ? null : f.kind);
        assertEquals(json, text, f.text);
    }

    @Test
    public void framesTheScreenReads() {
        assertFrame(PicoTurn.Frame.Kind.STEP, "Смотрю версию ядра (`exec`)", STEP_KERNEL);
        assertFrame(PicoTurn.Frame.Kind.FINAL, "Ядро — 4.9.113.", FINAL_KERNEL);
        assertFrame(PicoTurn.Frame.Kind.TURN_END, "", TURN_END);
        assertFrame(PicoTurn.Frame.Kind.USER, "хватит, просто скажи версию", TELEGRAM_STOP);
        assertFrame(PicoTurn.Frame.Kind.TEXT, "Context window exceeded. Compressing history and retrying...", INTERIM);
        assertFrame(PicoTurn.Frame.Kind.ERROR, "empty content", GATEWAY_ERROR);
        // A failed turn publishes its error as the answer, without context_usage.
        assertFrame(PicoTurn.Frame.Kind.FINAL, "Error processing message: LLM call failed after retries",
                "{\"type\":\"message.create\",\"payload\":{\"content\":\"Error processing message: LLM call failed after retries\","
                        + "\"kind\":\"final\",\"message_id\":\"b3\"}}");
    }

    @Test
    public void framesTheScreenDoesNotShow() {
        assertNull(PicoTurn.Frame.parse(TYPING_START));
        assertNull(PicoTurn.Frame.parse(TYPING_STOP));
        assertNull(PicoTurn.Frame.parse("{\"type\":\"message.create\",\"payload\":{\"content\":\"hmm\",\"kind\":\"thought\"}}"));
        assertNull(PicoTurn.Frame.parse("not json"));
        assertTrue(new PicoTurn(() -> "x").frame("not json").isEmpty());
    }
}
