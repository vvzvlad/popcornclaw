package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * What the command popup shows of a turn and whether it stays up (running: a spinner, no hide) or hides
 * SHOW_MS later (not running). The events have the shapes PicoTurn emits.
 */
public class PopupStateTest {
    private final CommandPopup.State s = new CommandPopup.State();

    private static JSONObject json(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    private boolean question(String text) {
        return s.event("question", json("id", "t1", "text", text));
    }

    private boolean step(String text) {
        return s.event("answer", json("id", "t1", "text", text, "done", false));
    }

    private boolean answer(String text) {
        return s.event("answer", json("id", "t1", "text", "", "done", true, "final", text));
    }

    private boolean turnEnd() {
        return s.event("answer", json("id", "t1", "text", "", "done", true));
    }

    private boolean error(String message) {
        return s.event("answer_error", json("id", "t1", "message", message));
    }

    private void assertShown(String command, String agent, boolean running) {
        assertEquals(command, s.command);
        assertEquals(agent, s.agent);
        assertEquals(running, s.running);
    }

    @Test
    public void stepKeepsTheWindowUpWithTheSpinner() {
        s.command("включи фильм", false);
        assertShown("включи фильм", "", false);
        assertTrue(step("Ищу фильм (`tv_search`)"));
        assertShown("включи фильм", "Ищу фильм (`tv_search`)", true);
        // The next line of the turn comes after a newline.
        assertTrue(step("\nВключаю (`tv_play`)"));
        assertShown("включи фильм", "Включаю (`tv_play`)", true);
    }

    @Test
    public void answerShowsAndSchedulesTheHide() {
        assertTrue(question("а сабы?"));
        assertTrue(step("Ищу субтитры (`exec`)"));
        assertTrue(answer("Субтитры включены."));
        assertShown("а сабы?", "Субтитры включены.", false);
    }

    @Test
    public void turnEndWithoutAnAnswerKeepsTheLastLineAndSchedulesTheHide() {
        assertTrue(question("а сабы?"));
        assertTrue(step("Ищу субтитры (`exec`)"));
        assertTrue(turnEnd());
        assertShown("а сабы?", "Ищу субтитры (`exec`)", false);
    }

    @Test
    public void keepaliveChangesNothing() {
        assertFalse(step(""));
        assertShown("", "", false);
        assertTrue(question("а сабы?"));
        assertTrue(step("Ищу субтитры (`exec`)"));
        assertFalse(step(""));
        assertShown("а сабы?", "Ищу субтитры (`exec`)", true);
    }

    @Test
    public void errorEndsTheTurn() {
        assertTrue(question("а сабы?"));
        assertTrue(step("Ищу субтитры (`exec`)"));
        assertTrue(error("no connection to the agent: closed 1001"));
        assertShown("а сабы?", "no connection to the agent: closed 1001", false);
    }

    @Test
    public void newCommandAfterAFinishedTurnDropsTheOldAnswer() {
        assertTrue(question("а сабы?"));
        assertTrue(answer("Субтитры включены."));
        s.command("громче", false);
        assertShown("громче", "", false);
    }

    @Test
    public void commandSteeredIntoARunningTurnKeepsTheStep() {
        s.command("включи фильм", false);
        assertTrue(step("Ищу фильм (`tv_search`)"));
        s.command("а сабы?", false);
        assertShown("а сабы?", "Ищу фильм (`tv_search`)", true);
    }

    // A Telegram message comes as a question event first and from the session files up to a poll later.
    @Test
    public void questionBeforeTheFileScanDoesNotLoseTheStepInBetween() {
        assertTrue(question("включи фильм"));
        assertShown("включи фильм", "", true);
        assertTrue(step("Ищу фильм (`tv_search`)"));
        s.command("включи фильм", false);
        assertShown("включи фильм", "Ищу фильм (`tv_search`)", true);
    }

    // A turn that fails at once can end before the poll that finds its message in the session files.
    @Test
    public void turnEndedBeforeTheFileScanKeepsItsLastLine() {
        assertTrue(question("включи фильм"));
        assertTrue(error("Error processing message: 429"));
        s.command("включи фильм", false);
        assertShown("включи фильм", "Error processing message: 429", false);
    }

    @Test
    public void repeatedTelegramMessageStartsANewTurn() {
        assertTrue(question("да"));
        assertTrue(answer("Включил."));
        assertTrue(question("да"));
        assertShown("да", "", true);
    }

    // The TV's assistant screen sends no question event: PicoLink has the turn open from the question on.
    @Test
    public void questionAskedOnTheTVRunsTheSpinnerBeforeTheFirstStep() {
        s.command("громче", true);
        assertShown("громче", "", true);
    }

    // A voice note from the remote opens the turn before its text is known; the old command must not spin.
    @Test
    public void voiceNoteShowsNothingUntilItsTextComes() {
        assertTrue(question("громче"));
        assertTrue(answer("Сделал громче."));
        assertTrue(question(""));
        assertShown("", "", true);
        assertTrue(s.empty());
        s.command("[voice: тише]", true);
        assertShown("[voice: тише]", "", true);
    }

    @Test
    public void repeatedTVQuestionDropsTheOldAnswer() {
        s.command("громче", true);
        assertTrue(answer("Сделал громче."));
        s.command("громче", true);
        assertShown("громче", "", true);
    }
}
