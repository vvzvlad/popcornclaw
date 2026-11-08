package xyz.vvzvlad.popcornclaw;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Subtitle search in OpenSubtitles and SubDL behind the {@code find_subtitles} tool, the Java port of the
 * Python {@code Subtitles} class (src/subtitles.py). Only the search: the reply lists what the two databases
 * have, nothing is downloaded. Like every tool reply it goes into the voice assistant's context, so it is
 * short plain text.
 *
 * <p>Concurrency: the Python side used {@code asyncio.gather} to run the two HTTP searches on one event loop;
 * this side submits both to an {@link ExecutorService} and awaits them, so they run concurrently, and joins
 * their line lists in order (OpenSubtitles first).
 */
final class Subtitles {
    // Public third-party APIs, so the addresses are constants rather than settings.
    private static final String OPENSUBTITLES_URL = "https://api.opensubtitles.com/api/v1/subtitles";
    private static final String SUBDL_URL = "https://api.subdl.com/api/v1/subtitles";
    // OpenSubtitles refuses a request without a User-Agent naming the application (HTTP 403).
    private static final String USER_AGENT = "tv_mcp v1.0";
    private static final int REQUEST_TIMEOUT = 15;
    // Subtitles listed per source.
    private static final int LIST_LIMIT = 5;
    // SubDL's largest page. It returns only the number of pages, no count of subtitles, so the count in the
    // reply is that of the first page.
    private static final int SUBDL_PAGE = 30;

    private final String opensubtitlesApiKey;
    private final String subdlApiKey;
    private final OkHttpClient client;
    // Fans out the two searches so they run concurrently (the Python asyncio.gather); shared for the instance.
    private final ExecutorService executor = Executors.newCachedThreadPool();

    Subtitles(String opensubtitlesApiKey, String subdlApiKey) {
        this.opensubtitlesApiKey = opensubtitlesApiKey;
        this.subdlApiKey = subdlApiKey;
        // Proxy.NO_PROXY mirrors httpx trust_env=False (as in Tv): a workstation's proxy variables would
        // route these requests through the proxy. Redirects off mirrors httpx's default follow_redirects=False,
        // which _opensubtitles relies on: OpenSubtitles 301s any other spelling of a search to the canonical
        // one, and the client must not follow it.
        this.client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }

    // --- module functions (src/subtitles.py, lines 26-48) ----------------------------------------------

    /** Port of titled: {@code "{name} ({year})"} when year is truthy, else name. */
    private static String titled(String name, Object year) {
        return TvFormat.truthy(year) ? name + " (" + String.valueOf(year) + ")" : name;
    }

    /** Port of episode_label: {@code "Sxx"} plus {@code "Eyy"} when episode is not null. */
    private static String episodeLabel(int season, Integer episode) {
        return String.format(Locale.ROOT, "S%02d", season)
                + (episode != null ? String.format(Locale.ROOT, "E%02d", episode) : "");
    }

    /** Port of marked: {@code "{line}, SDH"} when hearing_impaired is truthy, else line. */
    private static String marked(String line, Object hearingImpaired) {
        return TvFormat.truthy(hearingImpaired) ? line + ", SDH" : line;
    }

    /** Port of feature_key: an episode's own id differs from episode to episode; the series' id groups a season. */
    private static int featureKey(JSONObject item) throws JSONException {
        JSONObject feature = item.getJSONObject("attributes").getJSONObject("feature_details");
        // feature.get("parent_feature_id") or feature["feature_id"]: absent/null/0 falls through to feature_id.
        int parent = feature.optInt("parent_feature_id", 0);
        return parent != 0 ? parent : feature.getInt("feature_id");
    }

    /** Port of failure. Never the exception's message: an OkHttp/IO error carries the request URL, and SubDL's
     *  URL carries the API key. */
    private static String failure(String source, Exception exc) {
        if (exc instanceof HttpStatus) {
            return source + " did not answer: HTTP " + ((HttpStatus) exc).code;
        }
        return source + " did not answer: " + exc.getClass().getSimpleName();
    }

    // --- find (src/subtitles.py, lines 59-81) ------------------------------------------------------------

    /** Port of Subtitles.find. */
    String find(String query, String language, Integer season, Integer episode) {
        // Both branches must stay Integer: a bare int literal here unboxes `season`, so a movie
        // query (season == null) would NPE before it ever reaches a source.
        Integer seasonResolved = (episode != null && season == null) ? Integer.valueOf(1) : season;
        String code = TvFormat.languageCode(language);
        Future<List<String>> opensubtitles = executor.submit(
                () -> answer("OpenSubtitles", () -> opensubtitles(query, code, seasonResolved, episode)));
        Future<List<String>> subdl = executor.submit(
                () -> answer("SubDL", () -> subdl(query, code, seasonResolved, episode)));
        List<String> lines = new ArrayList<>();
        lines.addAll(await(opensubtitles));
        lines.addAll(await(subdl));
        return TvFormat.join(lines);
    }

    /**
     * Port of Subtitles._answer: one source's lines, or one line saying it failed, so a failure hides nothing
     * the other source found. Catches the HTTP, transport and non-JSON-body errors (Python httpx.HTTPError and
     * ValueError); a missing field (Python KeyError) is not caught and propagates as a JSONException, which
     * {@link #await} turns into the internal-error result the tool layer shows.
     */
    private List<String> answer(String source, Search search) throws JSONException {
        try {
            return search.run();
        } catch (HttpStatus | IOException | NotJson exc) {
            return Collections.singletonList(failure(source, exc));
        }
    }

    /** Port of Subtitles._get: the JSON body of one GET; raise_for_status, then response.json(). */
    private JSONObject get(HttpUrl url, Map<String, String> headers) throws IOException, HttpStatus, NotJson {
        Request.Builder rb = new Request.Builder().url(url).get();
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) rb.addHeader(h.getKey(), h.getValue());
        }
        try (Response response = client.newCall(rb.build()).execute()) {
            if (!response.isSuccessful()) throw new HttpStatus(response.code());  // raise_for_status
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            try {
                return new JSONObject(text);  // response.json()
            } catch (JSONException e) {
                throw new NotJson(e);  // ValueError: a body that is not JSON
            }
        }
    }

    // --- OpenSubtitles (src/subtitles.py, lines 83-116) --------------------------------------------------

    /** Port of Subtitles._opensubtitles. */
    private List<String> opensubtitles(String query, String code, Integer season, Integer episode)
            throws IOException, HttpStatus, NotJson, JSONException {
        // A TreeMap iterates sorted by key: OpenSubtitles answers any other spelling of a search with a 301 to
        // the canonical one (sorted keys, lowercase values, spaces as '+'), which this client does not follow.
        TreeMap<String, String> params = new TreeMap<>();
        params.put("languages", code);
        params.put("order_by", "download_count");
        params.put("query", query.toLowerCase(Locale.ROOT));
        if (season != null) {
            params.put("type", "episode");
            params.put("season_number", String.valueOf(season));
        }
        if (episode != null) {
            params.put("episode_number", String.valueOf(episode));
        }
        HttpUrl.Builder ub = HttpUrl.parse(OPENSUBTITLES_URL).newBuilder();
        // Encode values as application/x-www-form-urlencoded (space -> '+'), matching Python httpx. OkHttp's
        // addQueryParameter encodes a space as %20, which OpenSubtitles 301-redirects to the '+' form; a
        // multi-word query (most series and many films) would never reach the search otherwise.
        for (Map.Entry<String, String> e : params.entrySet()) {
            ub.addEncodedQueryParameter(formEncode(e.getKey()), formEncode(e.getValue()));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Api-Key", opensubtitlesApiKey);
        headers.put("User-Agent", USER_AGENT);
        JSONObject body = get(ub.build(), headers);
        JSONArray data = body.getJSONArray("data");
        if (data.length() == 0) {
            return Collections.singletonList("OpenSubtitles, " + code + ": nothing found");
        }
        List<String> lines = new ArrayList<>();
        lines.add("OpenSubtitles, " + code + " — " + body.getInt("total_count") + ":");
        // A text search does not follow `order_by`. Its own order puts the title asked for first and its
        // sequels and namesakes after, so the titles keep that order and only the subtitles of each title are
        // ranked here, most downloaded first.
        LinkedHashMap<Integer, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < data.length(); i++) {
            int key = featureKey(data.getJSONObject(i));
            if (!order.containsKey(key)) order.put(key, order.size());
        }
        // The sort keys are computed here, outside the comparator, because feature_key reads JSON (throws).
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            int downloads = item.getJSONObject("attributes").getInt("download_count");
            ranked.add(new Ranked(item, order.get(featureKey(item)), downloads));
        }
        // Stable sort by (title order, -download_count): equal keys keep the data order, as Python's sorted().
        Collections.sort(ranked, (a, b) -> {
            if (a.orderIndex != b.orderIndex) return Integer.compare(a.orderIndex, b.orderIndex);
            return Integer.compare(b.downloads, a.downloads);
        });
        int n = Math.min(ranked.size(), LIST_LIMIT);
        for (int i = 0; i < n; i++) {
            JSONObject item = ranked.get(i).item;
            JSONObject attributes = item.getJSONObject("attributes");
            JSONObject feature = attributes.getJSONObject("feature_details");
            String title;
            // An episode's `title` is the episode's own, `parent_title` the series'.
            String parentTitle = optStr(feature, "parent_title");
            if (parentTitle != null) {
                String label = episodeLabel(feature.getInt("season_number"), feature.getInt("episode_number"));
                title = parentTitle + " " + label;
            } else {
                title = titled(feature.getString("title"), feature.opt("year"));
            }
            String line = title + ": " + attributes.getString("release") + ", downloads "
                    + attributes.getInt("download_count");
            lines.add(marked(line, attributes.get("hearing_impaired")));
        }
        return lines;
    }

    // --- SubDL (src/subtitles.py, lines 118-143) ---------------------------------------------------------

    /** Port of Subtitles._subdl. */
    private List<String> subdl(String query, String code, Integer season, Integer episode)
            throws IOException, HttpStatus, NotJson, JSONException {
        // A LinkedHashMap keeps the Python dict's insertion order (SubDL does not canonicalize like OpenSubtitles).
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("api_key", subdlApiKey);
        params.put("film_name", query);
        params.put("languages", code.toUpperCase(Locale.ROOT));
        params.put("subs_per_page", String.valueOf(SUBDL_PAGE));
        if (season != null) {
            params.put("type", "tv");
            params.put("season_number", String.valueOf(season));
        }
        if (episode != null) {
            params.put("episode_number", String.valueOf(episode));
        }
        HttpUrl.Builder ub = HttpUrl.parse(SUBDL_URL).newBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        JSONObject body = get(ub.build(), null);
        // A title SubDL does not know comes with HTTP 200 and status false ("can't find movie or tv"); a
        // refused key with HTTP 403.
        if (!TvFormat.truthy(body.get("status"))) {
            return Collections.singletonList("SubDL, " + code + ": " + String.valueOf(body.get("error")));
        }
        JSONArray results = body.getJSONArray("results");
        if (results.length() == 0) {
            return Collections.singletonList("SubDL, " + code + ": nothing found");
        }
        // `subtitles` are those of the first title in `results`. The season and episode are the ones asked for;
        // the release names tell what each subtitle covers.
        JSONObject result = results.getJSONObject(0);
        String title = titled(result.getString("name"), result.opt("year"));
        if (season != null) {
            title += " " + episodeLabel(season, episode);
        }
        JSONArray found = body.getJSONArray("subtitles");
        if (found.length() == 0) {
            return Collections.singletonList("SubDL, " + code + " — " + title + ": nothing found");
        }
        List<String> lines = new ArrayList<>();
        lines.add("SubDL, " + code + " — " + title + ", " + found.length() + ":");
        int n = Math.min(found.length(), LIST_LIMIT);
        for (int i = 0; i < n; i++) {
            JSONObject subtitle = found.getJSONObject(i);
            lines.add(marked(subtitle.getString("release_name"), subtitle.get("hi")));
        }
        return lines;
    }

    // --- internal helpers --------------------------------------------------------------------------------

    /** The settled lines of a search future: its value, or the error it raised rethrown unchecked (the Python
     *  asyncio.gather propagates the first exception, which the tool layer turns into an internal-error result). */
    private static List<String> await(Future<List<String>> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** application/x-www-form-urlencoded, like Python httpx: space -> '+', other reserved chars percent-escaped. */
    private static String formEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);  // UTF-8 is always present
        }
    }

    /** A JSON string value, or null when absent, JSON null or empty (so Python's truthy `.get()` chains match). */
    private static String optStr(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    /** One source's search, throwing the errors _answer catches plus the field-access JSONException it does not. */
    private interface Search {
        List<String> run() throws IOException, HttpStatus, NotJson, JSONException;
    }

    /** A non-2xx response: the stand-in for the httpx.HTTPStatusError that raise_for_status raises. */
    private static final class HttpStatus extends Exception {
        final int code;

        HttpStatus(int code) {
            this.code = code;
        }
    }

    /** A body that is not JSON: the stand-in for the ValueError that httpx's response.json() raises. */
    private static final class NotJson extends Exception {
        NotJson(Throwable cause) {
            super(cause);
        }
    }

    /** A data item with its precomputed sort keys (title order, download count). */
    private static final class Ranked {
        final JSONObject item;
        final int orderIndex;
        final int downloads;

        Ranked(JSONObject item, int orderIndex, int downloads) {
            this.item = item;
            this.orderIndex = orderIndex;
            this.downloads = downloads;
        }
    }
}
