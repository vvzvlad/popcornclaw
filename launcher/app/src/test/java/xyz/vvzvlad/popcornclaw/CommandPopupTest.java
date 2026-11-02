package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;

/** Which lines of a PicoClaw session file count as a new command for the popup. */
public class CommandPopupTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final List<String> NONE = Collections.emptyList();

    private static String line(String role, String content, String createdAt) {
        return "{\"role\":\"" + role + "\",\"content\":" + JSONObject.quote(content)
                + ",\"created_at\":\"" + createdAt + "\"}\n";
    }

    private static final String OLD_USER = line("user", "включи фильм", "2026-11-02T19:00:00.100000001Z");
    private static final String OLD_CALL = line("assistant", "", "2026-11-02T19:00:02.5Z");
    private static final String OLD_RESULT = line("tool", "ok", "2026-11-02T19:00:03Z");
    private static final String OLD_ANSWER = line("assistant", "Включаю", "2026-11-02T19:00:05.2Z");
    private static final String HISTORY = OLD_USER + OLD_CALL + OLD_RESULT + OLD_ANSWER;
    private static final String NEW_USER = line("user", "а сабы?", "2026-11-02T19:10:00.000000123Z");

    private File write(String text) throws IOException {
        File f = new File(tmp.getRoot(), "sk_v1_test.jsonl");
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static void append(File f, String text) throws IOException {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    @Test
    public void firstPassOnlySetsTheBaseline() throws IOException {
        File f = write(HISTORY);
        CommandPopup.Seen s = new CommandPopup.Seen();
        assertEquals(NONE, CommandPopup.advance(f, 1, s, true));
        assertEquals(NONE, CommandPopup.advance(f, 1, s, false));
    }

    @Test
    public void appendedUserMessagePopsUp() throws IOException {
        File f = write(HISTORY);
        CommandPopup.Seen s = new CommandPopup.Seen();
        CommandPopup.advance(f, 1, s, true);
        append(f, NEW_USER);
        assertEquals(Collections.singletonList("а сабы?"), CommandPopup.advance(f, 1, s, false));
        assertEquals(NONE, CommandPopup.advance(f, 1, s, false));
    }

    @Test
    public void rewriteWithANewUserLinePopsItOnce() throws IOException {
        File f = write(HISTORY);
        CommandPopup.Seen s = new CommandPopup.Seen();
        CommandPopup.advance(f, 1, s, true);
        // PicoClaw compacts the history into a new file (temp file + rename: a new inode) and appends the
        // command milliseconds later, within one poll.
        write(OLD_RESULT + OLD_ANSWER + NEW_USER);
        assertEquals(Collections.singletonList("а сабы?"), CommandPopup.advance(f, 2, s, false));
        assertEquals(NONE, CommandPopup.advance(f, 2, s, false));
    }

    @Test
    public void rewriteOfOldHistoryPopsNothing() throws IOException {
        File f = write(HISTORY);
        CommandPopup.Seen s = new CommandPopup.Seen();
        CommandPopup.advance(f, 1, s, true);
        write(OLD_RESULT + OLD_ANSWER);
        assertEquals(NONE, CommandPopup.advance(f, 2, s, false));
    }

    @Test
    public void rewriteKeepingAShownCommandPopsNothing() throws IOException {
        File f = write(HISTORY);
        CommandPopup.Seen s = new CommandPopup.Seen();
        CommandPopup.advance(f, 1, s, true);
        append(f, NEW_USER);
        assertEquals(Collections.singletonList("а сабы?"), CommandPopup.advance(f, 1, s, false));
        // Compaction in the middle of the turn rewrites the file and keeps the command already shown.
        write(OLD_ANSWER + NEW_USER + line("assistant", "Ищу", "2026-11-02T19:10:03Z"));
        assertEquals(NONE, CommandPopup.advance(f, 2, s, false));
    }
}
