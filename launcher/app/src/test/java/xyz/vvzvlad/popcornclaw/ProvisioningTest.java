package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The config gate that holds the gateway back until the model is set. Two configured model_lists, one with the
 * model behind a proxy and one without; the fresh box's config is the APK's own default, read from the module dir
 * Gradle runs the tests in.
 */
public class ProvisioningTest {
    private static final String PROXIED = "{\"version\": 3, \"model_list\": ["
            + "{\"model_name\": \"main\", \"provider\": \"openai\", \"model\": \"z-ai/glm-5.3-flash\","
            + " \"api_base\": \"https://nano-gpt.com/api/v1\", \"proxy\": \"socks5://192.0.2.1:1080\"},"
            + "{\"model_name\": \"groq-asr\", \"model\": \"groq/whisper-large-v3-turbo\","
            + " \"proxy\": \"socks5://192.0.2.1:1080\"}]}";
    private static final String DIRECT = "{\"version\": 3, \"model_list\": ["
            + "{\"model_name\": \"main\", \"provider\": \"openai\", \"model\": \"z-ai/glm-5.3-flash\","
            + " \"api_base\": \"https://nano-gpt.com/api/v1\"},"
            + "{\"model_name\": \"groq-asr\", \"model\": \"groq/whisper-large-v3-turbo\"}]}";

    /** The layout ControlServer.buildSecurityYml writes. */
    private static String yml(String mainKey, String asrKey) {
        return "model_list:\n"
                + "  main:\n"
                + "    api_keys:\n"
                + "      - \"" + mainKey + "\"\n"
                + "  groq-asr:\n"
                + "    api_keys:\n"
                + "      - \"" + asrKey + "\"\n"
                + "channel_list:\n"
                + "  telegram:\n"
                + "    settings:\n"
                + "      token: \"tg-token\"\n"
                + "web:\n"
                + "  brave:\n"
                + "    api_keys:\n"
                + "      - \"\"\n";
    }

    private static String freshBox() throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/assets/defaults/config.json")), StandardCharsets.UTF_8);
    }

    @Test
    public void configuredBoxesWithTheKeyAreConfigured() {
        assertNull(Provisioning.unconfigured(PROXIED, yml("sk-main", "gsk-asr")));
        assertNull(Provisioning.unconfigured(DIRECT, yml("sk-main", "")));
    }

    @Test
    public void emptyMainKeyIsNotConfigured() {
        // The ASR key alone does not count: the gate reads the main model's.
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(PROXIED, yml("", "gsk-asr")));
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(DIRECT, yml("", "")));
    }

    @Test
    public void noYmlIsNotConfigured() {
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(PROXIED, ""));
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(DIRECT, "web:\n  brave:\n    api_keys:\n      - \"k\"\n"));
    }

    @Test
    public void freshBoxIsNotConfiguredEvenWithAKey() throws IOException {
        String fresh = freshBox();
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(fresh, yml("sk-main", "")));
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(fresh, yml("", "")));
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured(fresh, ""));
    }

    @Test
    public void freshBoxWithTheModelFilledInIsConfigured() throws IOException {
        String fresh = freshBox()
                .replace("\"model\": \"\"", "\"model\": \"z-ai/glm-5.3-flash\"")
                .replace("\"api_base\": \"\"", "\"api_base\": \"https://nano-gpt.com/api/v1\"");
        assertNull(Provisioning.unconfigured(fresh, yml("sk-main", "")));
    }

    @Test
    public void missingOrBrokenConfigIsNotConfigured() {
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured("", yml("sk-main", "")));
        assertEquals(Provisioning.NOT_CONFIGURED, Provisioning.unconfigured("{\"model_list\": [", yml("sk-main", "")));
    }
}
