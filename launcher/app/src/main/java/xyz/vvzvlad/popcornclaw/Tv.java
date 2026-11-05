package xyz.vvzvlad.popcornclaw;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The search and files logic, the Java port of the Python {@code Tv} class (src/tv.py). It drives Jackett
 * (torrent search) and TorrServer (adds a release, lists its files, reads one file with ffprobe) over HTTP,
 * and holds the search results in memory: an id from {@link #search} stays valid until the process restarts.
 *
 * <p>This is chunk 2 of the port: only the {@link #search} and {@link #files} tools and the machinery they
 * need. The play/prepare/follower fields and methods of the Python class are not ported here.
 *
 * <p>Concurrency: where the Python side used asyncio (one event loop, awaited coroutines), this side uses
 * blocking OkHttp calls on a shared thread pool. The probe fan-out ({@link #probeAll}) submits {@link #source}
 * and {@link #probe} tasks to {@link #executor}; a {@link Semaphore} caps the probes in flight at
 * {@code PROBE_CONCURRENCY}, a per-hash {@link ReentrantLock} serializes a hash's list/add/remove so no hash
 * is added to TorrServer twice, and {@link #probing}/{@link #taken}/{@link #results}/{@link #ids} are shared
 * instance state across calls: a search blocks until its own probes finish, but another request running
 * meanwhile (a {@link #files}, or a concurrent {@link #search}) must see the torrents this search's probes
 * added, so that state and the per-hash locks live on the instance, not in one call.
 */
final class Tv {
    private static final String TAG = "PopcornClaw-tv";

    private static final String DEAD = "Раздача мёртвая, пиров нет — выбери другую";
    private static final String PLAYING = "сейчас играет";
    private static final String PROBED_ELSEWHERE = "проверяется другим поиском";

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");
    private static final MediaType BITTORRENT_MEDIA = MediaType.parse("application/x-bittorrent");

    private final String jackettUrl;
    private final String jackettApiKey;
    private final String torrserverUrl;
    private final OkHttpClient client;

    // Search results live in memory, keyed by the id search hands out; shared across calls.
    private final Map<Integer, JSONObject> results = new ConcurrentHashMap<>();
    // The id counter, mirror itertools.count(1): the first id is 1.
    private final AtomicInteger ids = new AtomicInteger(1);
    // A per-hash lock, held from TorrServer's `list` to the `add` decided on it and around a probe's `rem`,
    // so no hash is added twice.
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    // Hashes probes added and have not removed yet, and those of them a later load took over for the player.
    private final Set<String> probing = ConcurrentHashMap.newKeySet();
    private final Set<String> taken = ConcurrentHashMap.newKeySet();
    // Shared by all searches, so concurrent calls together keep to the probe limit.
    private final Semaphore probeSlots = new Semaphore(TvFormat.PROBE_CONCURRENCY);
    // Fans out the source downloads and the probes; shared across calls, so a probe runs concurrently with
    // any other request in flight (files, another search).
    private final ExecutorService executor = Executors.newCachedThreadPool();

    Tv(String jackettUrl, String jackettApiKey, String torrserverUrl) {
        this.jackettUrl = rstripSlash(jackettUrl);
        this.jackettApiKey = jackettApiKey;
        this.torrserverUrl = rstripSlash(torrserverUrl);
        // Proxy.NO_PROXY mirrors httpx trust_env=False: all services are on the LAN, and a workstation's
        // proxy variables would route these requests through the proxy.
        this.client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(TvFormat.REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(TvFormat.REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(TvFormat.REQUEST_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }

    // --- tools -----------------------------------------------------------------------------------------

    /** Port of Tv.search. */
    String search(String query, String subtitles, String audio) throws ToolError {
        List<String> langs = TvFormat.languages(subtitles);
        HttpUrl url = HttpUrl.parse(jackettUrl + "/api/v2.0/indexers/all/results").newBuilder()
                .addQueryParameter("apikey", jackettApiKey)
                .addQueryParameter("Query", query)
                .build();
        byte[] content = request("Jackett", new Request.Builder().url(url).get().build());
        JSONObject root = parseObject(content);
        JSONArray resultsArr = root.optJSONArray("Results");
        List<JSONObject> found = new ArrayList<>();
        if (resultsArr != null) {
            for (int i = 0; i < resultsArr.length(); i++) {
                JSONObject r = resultsArr.optJSONObject(i);
                if (r != null && r.optInt("Seeders", 0) != 0) found.add(r);
            }
        }
        // Stable sort by Seeders, descending; equal seeders keep Jackett's order (Python reverse=True).
        Collections.sort(found, (a, b) -> Integer.compare(b.optInt("Seeders", 0), a.optInt("Seeders", 0)));
        Log.i(TAG, "search " + query + ": " + found.size() + " results with seeders");
        List<Item> top = new ArrayList<>();
        int limit = Math.min(found.size(), TvFormat.SEARCH_LIMIT);
        for (int i = 0; i < limit; i++) {
            JSONObject result = found.get(i);
            int resultId = ids.getAndIncrement();
            results.put(resultId, result);
            top.add(new Item(resultId, result));
        }
        // Listed before the probes, so the torrents they add for a moment are not among them.
        List<String> saved = saved();
        if (found.isEmpty()) {
            List<String> lines = new ArrayList<>(saved);
            lines.add("По запросу «" + query + "» ничего с сидами не нашлось");
            return join(lines);
        }
        // `top` is sorted by seeders, so this is the best PROBE_LIMIT of the well-seeded ones.
        List<Item> probed = new ArrayList<>();
        for (Item it : top) {
            if (it.result.optInt("Seeders", 0) >= TvFormat.PROBE_MIN_SEEDERS) {
                probed.add(it);
                if (probed.size() >= TvFormat.PROBE_LIMIT) break;
            }
        }
        boolean wantProbe = !langs.isEmpty() || (audio != null && !audio.isEmpty());
        final Map<Integer, TvFormat.Note> notes = wantProbe ? probeAll(probed, langs, audio) : new HashMap<>();
        // Stable sort: probed results with every language asked for first, the rest keep the seeders order.
        Collections.sort(top, (a, b) -> {
            boolean af = notes.containsKey(a.id) && notes.get(a.id).found;
            boolean bf = notes.containsKey(b.id) && notes.get(b.id).found;
            return Boolean.compare(!af, !bf);
        });
        List<String> lines = new ArrayList<>(saved);
        for (Item it : top) {
            String line = TvFormat.formatResult(it.id, it.result);
            if (notes.containsKey(it.id)) line += " — " + notes.get(it.id).text;
            lines.add(line);
        }
        return join(lines);
    }

    /** Port of Tv.files (the tool): the video files of the search result, the torrent put into TorrServer. */
    String files(int resultId) throws ToolError {
        FilesResult fr = resolveFiles(resultId);
        Log.i(TAG, "files " + fr.hash);
        return TvFormat.describeFiles(fr.files);
    }

    // --- TorrServer / Jackett --------------------------------------------------------------------------

    /**
     * Port of Tv._request: the response body bytes of one HTTP call; the failure messages are the Python
     * ones, and never carry the URL (Jackett's URL carries the API key).
     */
    private byte[] request(String service, Request req) throws ToolError {
        try (Response response = client.newCall(req).execute()) {
            if (!response.isSuccessful()) {
                throw new ToolError(service + " ответил HTTP " + response.code());
            }
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } catch (IOException exc) {
            throw new ToolError(service + " не отвечает: " + exc.getClass().getSimpleName() + " " + exc.getMessage());
        }
    }

    /** Port of Tv._torrents: POST a body to /torrents; the parsed JSON, or null when the body is empty. */
    private Object torrents(JSONObject body) throws ToolError {
        Request req = new Request.Builder().url(torrserverUrl + "/torrents")
                .post(RequestBody.create(body.toString(), JSON_MEDIA)).build();
        byte[] content = request("TorrServer", req);
        String text = new String(content, StandardCharsets.UTF_8);
        return text.trim().isEmpty() ? null : parseValue(text);
    }

    /**
     * Port of Tv._source: the result's info hash and the .torrent to add, computed anew on every call. With
     * Jackett's Link the .torrent is downloaded and the hash is the sha1 of its `info` dict; without one, the
     * magnet's btih.
     */
    private Source source(JSONObject result) throws ToolError {
        String link = optStr(result, "Link");
        if (link != null) {
            byte[] content = request("Jackett", new Request.Builder().url(link).get().build());
            try {
                return new Source(TvFormat.torrentInfoHash(content), content);
            } catch (TvFormat.BencodeException | StackOverflowError exc) {
                throw new ToolError("Jackett отдал не .torrent: " + exc.getMessage());
            }
        }
        String hash = TvFormat.magnetHash(optStr(result, "MagnetUri"));
        if (hash == null) throw new ToolError("У раздачи нет ни .torrent, ни magnet-ссылки с хешем");
        return new Source(hash, null);
    }

    /** Port of Tv._lock. */
    private ReentrantLock lock(String torrentHash) {
        return locks.computeIfAbsent(torrentHash, k -> new ReentrantLock());
    }

    /** Port of Tv._list: every torrent TorrServer has, active or only in its database, by hash. */
    private Map<String, JSONObject> list() throws ToolError {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        Object res = torrents(obj("action", "list"));
        if (res instanceof JSONArray) {
            JSONArray arr = (JSONArray) res;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null) map.put(t.optString("hash"), t);
            }
        }
        return map;
    }

    /** Port of Tv._add: add a release not in TorrServer, from the .torrent or else its magnet; its files. */
    private List<JSONObject> add(JSONObject result, String torrentHash, byte[] torrent) throws ToolError {
        JSONObject added;
        if (torrent == null) {
            Object res = torrents(obj("action", "add", "link", result.optString("MagnetUri")));
            added = res instanceof JSONObject ? (JSONObject) res : new JSONObject();
        } else {
            MultipartBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "release.torrent", RequestBody.create(torrent, BITTORRENT_MEDIA))
                    .build();
            Request req = new Request.Builder().url(torrserverUrl + "/torrent/upload").post(multipart).build();
            added = parseObject(request("TorrServer", req));
        }
        List<JSONObject> files = toList(added.optJSONArray("file_stats"));
        Log.d(TAG, "add " + added.optString("hash") + ", " + files.size() + " files");
        if (!added.optString("hash").equals(torrentHash)) {
            Log.w(TAG, "TorrServer added " + torrentHash + " as " + added.optString("hash"));
        }
        return files;
    }

    /** Port of Tv._wait_files: the torrent's file list, or [] when none came DEAD_TIMEOUT s after add. */
    private List<JSONObject> waitFiles(String torrentHash, List<JSONObject> files) throws ToolError {
        long deadline = System.nanoTime() + TvFormat.DEAD_TIMEOUT * 1_000_000_000L;
        while (files.isEmpty() && System.nanoTime() < deadline) {
            sleep(TvFormat.POLL_INTERVAL);
            Object res = torrents(obj("action", "get", "hash", torrentHash));
            JSONObject got = res instanceof JSONObject ? (JSONObject) res : new JSONObject();
            files = toList(got.optJSONArray("file_stats"));
        }
        return files;
    }

    /**
     * Port of Tv._ffprobe: ffprobe's answer for one file; null when TorrServer could not read it. Uses the
     * longer FFPROBE_TIMEOUT read timeout for this one call.
     */
    private JSONObject ffprobe(String torrentHash, int fileId) {
        OkHttpClient probeClient = client.newBuilder()
                .readTimeout(TvFormat.FFPROBE_TIMEOUT, TimeUnit.SECONDS).build();
        Request req = new Request.Builder()
                .url(torrserverUrl + "/ffp/" + torrentHash + "/" + fileId).get().build();
        try (Response response = probeClient.newCall(req).execute()) {
            if (!response.isSuccessful()) return null;
            ResponseBody body = response.body();
            return body == null ? null : new JSONObject(body.string());
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Port of Tv._files: the search result, its info hash and its files, with the torrent in TorrServer: one
     * already there is used as it is, any other is added. Nothing here touches the player.
     */
    private FilesResult resolveFiles(int resultId) throws ToolError {
        JSONObject result = results.get(resultId);
        if (result == null) throw new ToolError("Нет результата " + resultId + ": сначала search");
        Source src = source(result);
        String torrentHash = src.hash;
        List<JSONObject> files;
        ReentrantLock lock = lock(torrentHash);
        lock.lock();
        try {
            JSONObject listed = list().get(torrentHash);
            if (listed == null && !probing.contains(torrentHash)) {
                files = add(result, torrentHash, src.torrent);
            } else {
                // Maybe the torrent the player is on now (another episode of it): used as it is.
                // One a probe added stays: the probe leaves a taken hash in TorrServer.
                if (probing.contains(torrentHash)) taken.add(torrentHash);
                files = listed == null ? new ArrayList<>() : toList(listed.optJSONArray("file_stats"));
            }
        } finally {
            lock.unlock();
        }
        files = waitFiles(torrentHash, files);
        if (files.isEmpty()) throw new ToolError(DEAD);
        return new FilesResult(result, torrentHash, files);
    }

    /** Port of Tv._probe: probe one hash for the languages asked for; (found, note). */
    private TvFormat.Note probe(String torrentHash, JSONObject result, byte[] torrent, List<String> langs,
                                String audio) throws ToolError, InterruptedException {
        probeSlots.acquire();
        try {
            boolean listedWasNull = false;
            List<JSONObject> files = new ArrayList<>();
            ReentrantLock lock = lock(torrentHash);
            lock.lock();
            try {
                if (probing.contains(torrentHash)) return new TvFormat.Note(false, PROBED_ELSEWHERE);
                // Listed inside the slot: a probe that waited for one starts minutes after the search.
                JSONObject listed = list().get(torrentHash);
                if (TvFormat.streaming(listed)) return new TvFormat.Note(false, PLAYING);
                if (listed == null) {
                    files = add(result, torrentHash, torrent);
                    probing.add(torrentHash);
                    listedWasNull = true;
                } else {
                    files = toList(listed.optJSONArray("file_stats"));
                }
            } finally {
                lock.unlock();
            }
            try {
                files = waitFiles(torrentHash, files);
                if (files.isEmpty()) return new TvFormat.Note(false, "мёртвая");
                List<JSONObject> videos = TvFormat.videoFiles(files);
                if (videos.isEmpty()) return new TvFormat.Note(false, "нет видеофайлов");
                JSONObject probe = ffprobe(torrentHash, videos.get(0).optInt("id"));
                List<JSONObject> streams = probe == null ? null : toList(probe.optJSONArray("streams"));
                return TvFormat.describeProbe(streams, langs, audio);
            } finally {
                // Only here, once /ffp has returned and TorrServer's ffprobe no longer reads the torrent, and
                // only a torrent this probe added and the player did not take over: one that was there may be
                // playing.
                if (listedWasNull) {
                    lock.lock();
                    try {
                        probing.remove(torrentHash);
                        if (taken.contains(torrentHash)) {
                            taken.remove(torrentHash);
                        } else {
                            torrents(obj("action", "rem", "hash", torrentHash));
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        } finally {
            probeSlots.release();
        }
    }

    /**
     * Port of Tv._probe_all: notes by result id. Each info hash is probed once (the same release on two
     * trackers gets that one probe's note); a failed source or probe becomes its own note and does not take
     * the others' results with it (Python return_exceptions=True).
     */
    private Map<Integer, TvFormat.Note> probeAll(List<Item> items, List<String> langs, String audio) {
        // Sources, concurrently; each outcome is a Source or the Throwable that computing it raised.
        List<Future<Source>> sourceFutures = new ArrayList<>();
        for (Item it : items) sourceFutures.add(executor.submit(() -> source(it.result)));
        List<Object> sources = new ArrayList<>();
        for (Future<Source> f : sourceFutures) sources.add(settle(f));
        // The first result for each distinct hash.
        LinkedHashMap<String, HashSource> first = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Object s = sources.get(i);
            if (s instanceof Source) {
                Source src = (Source) s;
                if (!first.containsKey(src.hash)) first.put(src.hash, new HashSource(items.get(i).result, src.torrent));
            }
        }
        // Probe each distinct hash, concurrently.
        List<String> hashes = new ArrayList<>(first.keySet());
        List<Future<TvFormat.Note>> probeFutures = new ArrayList<>();
        for (String h : hashes) {
            HashSource hs = first.get(h);
            probeFutures.add(executor.submit(() -> probe(h, hs.result, hs.torrent, langs, audio)));
        }
        Map<String, Object> byHash = new HashMap<>();
        for (int i = 0; i < hashes.size(); i++) byHash.put(hashes.get(i), settle(probeFutures.get(i)));
        // Notes by result id: a result keyed by its source's hash gets that hash's outcome, a failed source
        // gets the source's own exception.
        Map<Integer, TvFormat.Note> notes = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Object s = sources.get(i);
            Object outcome = (s instanceof Source) ? byHash.get(((Source) s).hash) : s;
            notes.put(items.get(i).id, outcome instanceof TvFormat.Note
                    ? (TvFormat.Note) outcome
                    : new TvFormat.Note(false, "не проверилась: " + describeThrowable(outcome)));
        }
        return notes;
    }

    /**
     * Port of Tv._saved: a search line for each torrent TorrServer has, in its order (newest first), with
     * the highest file index it holds a watched mark for; [] when TorrServer does not answer. Each is
     * registered as a search result of its magnet, so files takes its id as any other.
     */
    private List<String> saved() {
        List<JSONObject> torrentList;
        List<JSONObject> marks;
        try {
            torrentList = new ArrayList<>();
            for (Map.Entry<String, JSONObject> e : list().entrySet()) {
                String h = e.getKey();
                if (!probing.contains(h) || taken.contains(h)) torrentList.add(e.getValue());
            }
            marks = torrentList.isEmpty() ? new ArrayList<>() : viewedMarks();
        } catch (ToolError exc) {
            Log.w(TAG, "search: no TorrServer list: " + exc.getMessage());
            return new ArrayList<>();
        }
        Map<String, Integer> last = new HashMap<>();
        for (JSONObject mark : marks) {
            String h = mark.optString("hash");
            int idx = mark.optInt("file_index");  // file indexes start at 1
            Integer cur = last.get(h);
            last.put(h, Math.max(cur == null ? 0 : cur, idx));
        }
        List<String> lines = new ArrayList<>();
        for (JSONObject torrent : torrentList) {
            int resultId = ids.getAndIncrement();
            String hash = torrent.optString("hash");
            // "Link" is omitted (an absent key reads as falsy, like the Python None); torrent_size is left
            // as 0 while TorrServer knows no size.
            JSONObject saved = obj(
                    "Title", torrent.optString("title"),
                    "Size", torrent.optLong("torrent_size", 0),
                    "TrackerId", "torrserver",
                    "MagnetUri", "magnet:?xt=urn:btih:" + hash);
            results.put(resultId, saved);
            lines.add(TvFormat.formatSaved(resultId, saved, last.get(hash)));
        }
        return lines;
    }

    /** Port of Tv._viewed_marks: TorrServer's watched marks of every torrent. */
    private List<JSONObject> viewedMarks() throws ToolError {
        Request req = new Request.Builder().url(torrserverUrl + "/viewed")
                .post(RequestBody.create(obj("action", "list").toString(), JSON_MEDIA)).build();
        byte[] content = request("TorrServer", req);
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) return new ArrayList<>();
        Object val = parseValue(text);
        return val instanceof JSONArray ? toList((JSONArray) val) : new ArrayList<>();
    }

    // --- internal helpers ------------------------------------------------------------------------------

    /** The settled outcome of a future: its value, or the Throwable it raised (Python return_exceptions). */
    private static Object settle(Future<?> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    private static String describeThrowable(Object t) {
        if (t instanceof Throwable) {
            String m = ((Throwable) t).getMessage();
            return m != null ? m : t.toString();
        }
        return String.valueOf(t);
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rstripSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private static List<JSONObject> toList(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** Builds a JSONObject from key/value pairs; the puts are static and never fail. */
    private static JSONObject obj(Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject parseObject(byte[] content) {
        try {
            return new JSONObject(new String(content, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            // Mirrors an uncaught response.json() decode error: surfaced as an internal error result.
            throw new RuntimeException(e);
        }
    }

    private static Object parseValue(String text) {
        try {
            return new JSONTokener(text).nextValue();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** A JSON string value, or null when absent, JSON null or empty (so Python's truthy `or` chains match). */
    private static String optStr(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    // --- small holders ---------------------------------------------------------------------------------

    /** The Python _source return: (info hash, .torrent bytes or null). */
    private static final class Source {
        final String hash;
        final byte[] torrent;

        Source(String hash, byte[] torrent) {
            this.hash = hash;
            this.torrent = torrent;
        }
    }

    /** A search candidate: a result id and its Jackett result object (one of `top`). */
    private static final class Item {
        final int id;
        final JSONObject result;

        Item(int id, JSONObject result) {
            this.id = id;
            this.result = result;
        }
    }

    /** The first (result, .torrent) seen for a distinct hash in probeAll. */
    private static final class HashSource {
        final JSONObject result;
        final byte[] torrent;

        HashSource(JSONObject result, byte[] torrent) {
            this.result = result;
            this.torrent = torrent;
        }
    }

    /** The Python _files return: (result, info hash, file list). */
    private static final class FilesResult {
        final JSONObject result;
        final String hash;
        final List<JSONObject> files;

        FilesResult(JSONObject result, String hash, List<JSONObject> files) {
            this.result = result;
            this.hash = hash;
            this.files = files;
        }
    }
}
