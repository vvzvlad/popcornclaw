package xyz.vvzvlad.popcornclaw;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import okhttp3.Call;
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

    // mpv-android on the box (src/tv.py 45, 51, 56, 61, 65): the activity to start, the REORDER_TO_FRONT intent
    // flag that brings it to the front without reloading, and the play timeouts (seconds).
    private static final String MPV_ACTIVITY = "is.xyz.mpv/.MPVActivity";
    private static final String REORDER_TO_FRONT = "0x00020000";
    private static final int MPV_START_TIMEOUT = 30;
    private static final int PLAYER_TIMEOUT = 120;
    private static final int MPV_STOP_TIMEOUT = 10;
    // Follower timing (src/tv.py 68-69): retry a reconnect every FOLLOW_INTERVAL s, give up after FOLLOW_IDLE
    // s without a connection to mpv (mpv-android closes its activity at the end of the playlist or on stop).
    private static final int FOLLOW_INTERVAL = 2;
    private static final int FOLLOW_IDLE = 30;
    // Seconds one `am start` (su) run may take (Python ADB_TIMEOUT = 20).
    private static final long AM_TIMEOUT_MS = 20_000L;
    // _apply's default: both kinds (Python kinds=("subtitles", "audio")).
    private static final String[] DEFAULT_KINDS = {"subtitles", "audio"};
    // shlex.quote's set of shell-safe characters; anything else forces single-quoting.
    private static final Pattern SHLEX_SAFE = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

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

    // The release the last play loaded into mpv (src/tv.py __init__, 627-631): every tracker topic and info
    // hash its plays have had, the entry URLs of its playlist, the subtitle languages (bottom, then top) and
    // the audio language chosen for that release. A play of any of those topics or hashes is the same release
    // and keeps the chosen languages. Guarded by releaseLock because the follower thread reads these from
    // another thread; play writes them and _apply reads them.
    private final Object releaseLock = new Object();
    private final Set<String> relTopics = new HashSet<>();
    private final Set<String> relHashes = new HashSet<>();
    private List<String> relPlaylist = new ArrayList<>();
    private List<String> relSubs = new ArrayList<>();
    private String relAudio;
    // The follower (src/tv.py 627): the daemon thread that keeps the chosen languages on each next item of
    // relPlaylist (chunk 3c). Set, replaced and nulled under releaseLock, like the rest of the release-state;
    // null when none runs. Cancelled by interrupting it (Java's thread cancellation).
    private Thread follower;

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

    /**
     * Port of Tv.prepare (the tool): load the episode into TorrServer as play would, start TorrServer's preload
     * of it and tell whether the release keeps up; the box is not touched. Ready once the download speed
     * averaged over the last SPEED_WINDOW s is SPEED_MARGIN times the episode's rate, or once the preload is
     * over (preloaded_bytes reaches min(preload_size, the file's length), counted only when the first poll's
     * fill was below that target), or once TorrServer answered the preload request (unless the torrent was
     * already running a preload as prepare started). Not ready, a ToolError with the numbers, after
     * PREPARE_TIMEOUT s or without an active peer DEAD_TIMEOUT s after the preload started.
     */
    String prepare(int resultId, Integer season, Integer episode, Integer file) throws ToolError {
        LoadResult loaded = load(resultId, season, episode, file);
        String torrentHash = loaded.hash;
        JSONObject picked = loaded.file;
        long fileLength = picked.optLong("length", 0);
        int fileId = picked.optInt("id");
        Log.i(TAG, "prepare " + torrentHash + " file " + picked.optString("path"));
        // Read before the preload starts: once it has, the state is this preload's own.
        JSONObject listed = list().get(torrentHash);
        boolean busy = listed != null && listed.optInt("stat", 0) == TvFormat.TORRENT_PRELOAD;
        // Neither is awaited: the preload answers once it is over, and /ffp once TorrServer's ffprobe has read
        // the file, which on a slow swarm outlasts PREPARE_TIMEOUT.
        // The live OkHttp calls, so finally can abort them: Future.cancel(true) only interrupts the worker
        // thread, and OkHttp's blocking socket read (Okio) ignores interrupts, so a stuck preload/ffprobe would
        // hold its thread and socket until TorrServer answers (the preload client has no timeout). Call.cancel()
        // aborts the in-flight request at once, matching the Python asyncio cancel.
        AtomicReference<Call> preloadCall = new AtomicReference<>();
        AtomicReference<Call> probeCall = new AtomicReference<>();
        Future<Boolean> preload = executor.submit(() -> preload(torrentHash, fileId, preloadCall));
        Future<JSONObject> probe = executor.submit(() -> ffprobe(torrentHash, fileId, probeCall));
        try {
            long started = System.nanoTime();
            List<double[]> speeds = new ArrayList<>();  // {elapsed s, download_speed} of each poll
            boolean peered = false;
            Double firstFill = null;
            while (true) {
                Object got = torrents(obj("action", "get", "hash", torrentHash));
                JSONObject torrent = got instanceof JSONObject ? (JSONObject) got : new JSONObject();
                double elapsed = (System.nanoTime() - started) / 1e9;
                // TorrServer's download_speed is the rate over its last second.
                List<double[]> kept = new ArrayList<>();
                for (double[] ts : speeds) if (elapsed - ts[0] < TvFormat.SPEED_WINDOW) kept.add(ts);
                kept.add(new double[]{elapsed, torrent.optDouble("download_speed", 0)});
                speeds = kept;
                double sum = 0;
                for (double[] ts : speeds) sum += ts[1];
                double speed = sum / speeds.size();
                Double rate = null;
                if (probe.isDone()) {
                    Object pr = settle(probe);
                    rate = TvFormat.neededRate(pr instanceof JSONObject ? (JSONObject) pr : null, fileLength);
                }
                peered = peered || torrent.optInt("active_peers", 0) != 0;
                long preloaded = torrent.optLong("preloaded_bytes", 0);
                if (firstFill == null) firstFill = (double) preloaded;
                long target = Math.min(torrent.optLong("preload_size", 0), fileLength);
                // Named only while the fill is this episode's: see the docstring.
                String cached = firstFill < target
                        ? TvFormat.fmt0(preloaded / TvFormat.MIB) + " из " + TvFormat.fmt0(target / TvFormat.MIB) + " МБ"
                        : null;
                if ((target != 0 && firstFill < target && target <= preloaded)
                        || (rate != null && rate != 0.0 && elapsed >= TvFormat.SPEED_WINDOW
                                && speed >= TvFormat.SPEED_MARGIN * rate)) {
                    return "Готово к запуску: " + TvFormat.basename(picked.optString("path")) + " — "
                            + TvFormat.describeSpeed(speed, rate) + (cached != null ? "; в кэше " + cached : "");
                }
                if (!busy && preload.isDone() && Boolean.TRUE.equals(settle(preload))) {
                    return "Готово к запуску: " + TvFormat.basename(picked.optString("path"))
                            + " — прелоад серии в кэше TorrServer";
                }
                if ((!peered && elapsed >= TvFormat.DEAD_TIMEOUT) || elapsed >= TvFormat.PREPARE_TIMEOUT) {
                    throw new ToolError("Раздача не успеет: пиров " + torrent.optInt("active_peers", 0) + ", "
                            + TvFormat.describeSpeed(speed, rate) + (cached != null ? "; скачано " + cached : "")
                            + " — выбери другую");
                }
                sleep(TvFormat.POLL_INTERVAL);
            }
        } finally {
            cancelCall(preloadCall);
            cancelCall(probeCall);
            preload.cancel(true);
            probe.cancel(true);
        }
    }

    /** Abort an in-flight OkHttp call published to a holder, if any; Call.cancel() unblocks its socket read. */
    private static void cancelCall(AtomicReference<Call> ref) {
        Call call = ref.get();
        if (call != null) call.cancel();
    }

    // --- play (ported from tv.py play/_start/_front/_wait_playing/_apply/_switch/_am_start) -------------

    /**
     * Port of Tv.play (the tool): start mpv on the box with the release's playlist, wait for playback and set
     * the chosen subtitle/audio languages on the played item. The follower that keeps those languages across
     * later playlist items is a separate chunk, so the final {@code self._follow(...)} of the Python play is
     * intentionally omitted here.
     */
    String play(int resultId, Integer season, Integer episode, String subtitles, String audio, Integer file)
            throws ToolError {
        List<String> subsArg = TvFormat.languages(subtitles);
        LoadResult loaded = load(resultId, season, episode, file);
        JSONObject result = loaded.result;
        String torrentHash = loaded.hash;
        JSONObject picked = loaded.file;
        String playlistUrl = torrserverUrl + "/playlist/" + torrentHash + ".m3u?hash=" + torrentHash;
        // The same entries mpv gets: TorrServer builds them from the host it was asked on.
        byte[] body = request("TorrServer", new Request.Builder().url(playlistUrl).get().build());
        List<String> entries = TvFormat.m3uEntries(new String(body, StandardCharsets.UTF_8));
        int position = TvFormat.playlistPosition(entries, picked.optInt("id"));
        // Before anything changes, so a play that fails here leaves what plays and the chosen languages alone.
        MpvIpc ipc = mpv();
        boolean running = ipc != null;
        try {
            if (!running) ipc = start(playlistUrl);
            try {
                if (running) front();
                // mpv is taken over from here on. Another episode of the same release keeps the languages
                // chosen for it; another release clears each one this play does not give.
                String releaseTopic = TvFormat.topic(result);
                synchronized (releaseLock) {
                    boolean same = relHashes.contains(torrentHash) || relTopics.contains(releaseTopic);
                    List<String> subsChoice = !subsArg.isEmpty() ? subsArg
                            : (same ? new ArrayList<>(relSubs) : new ArrayList<String>());
                    String audioChoice = audio != null ? audio : (same ? relAudio : null);
                    // Stop the previous play's follower before loadfile (mirror tv.py:1002): otherwise it is
                    // still alive on its own IPC when mpv broadcasts the new item's file-loaded and would apply
                    // the tracks concurrently with this play's own apply below. follow() at the end restarts it.
                    unfollowLocked();
                    if (!same) {
                        relTopics.clear();
                        relHashes.clear();
                    }
                    relHashes.add(torrentHash);
                    if (releaseTopic != null) relTopics.add(releaseTopic);
                    relPlaylist = new ArrayList<>(entries);
                    relSubs = subsChoice;
                    relAudio = audioChoice;
                }
                Log.i(TAG, "play " + torrentHash + " file " + picked.optString("path")
                        + " at playlist position " + position);
                Object reply = ipc.command("loadfile", playlistUrl, "replace", -1, "playlist-start=" + position);
                // Paused mpv stays paused for the next file; mpv-android pauses when sent to the background.
                ipc.set("pause", false);
                int since = reply instanceof JSONObject ? ((JSONObject) reply).optInt("playlist_entry_id", 0) : 0;
                waitPlaying(ipc, torrentHash, position, since, true);
                save(torrentHash);
                List<String> lines = new ArrayList<>();
                lines.add("Играет: " + TvFormat.basename(picked.optString("path")));
                lines.addAll(apply(ipc));
                // Also when this item has none of them: the languages are chosen for the following ones.
                Object playingPath = ipc.get("path");
                follow(playingPath == null ? null : playingPath.toString());
                return join(lines);
            } finally {
                if (ipc != null) ipc.close();
            }
        } catch (MpvIpc.MpvError e) {
            throw new ToolError(e.getMessage());
        }
    }

    /**
     * Port of Tv._apply: put the chosen languages on the playing item, switching only the slots that do not
     * already show the right track, and return a line for each of `kinds` (default both). A kind without a
     * chosen language is left as it is and its line says what is on. Reads the release-state choice under
     * releaseLock, as the follower thread will too.
     */
    private List<String> apply(MpvIpc ipc, String... kinds) throws MpvIpc.MpvError {
        if (kinds.length == 0) kinds = DEFAULT_KINDS;
        boolean wantSubs = false;
        boolean wantAudio = false;
        for (String k : kinds) {
            if ("subtitles".equals(k)) wantSubs = true;
            else if ("audio".equals(k)) wantAudio = true;
        }
        List<String> subsChoice;
        String audioChoice;
        synchronized (releaseLock) {
            subsChoice = new ArrayList<>(relSubs);
            audioChoice = relAudio;
        }
        JSONArray tracks = TvFormat.asArray(ipc.get("track-list"));
        List<JSONObject> subs = TvFormat.tracksOfType(tracks, "sub");
        List<JSONObject> voices = TvFormat.tracksOfType(tracks, "audio");
        JSONObject curSid = TvFormat.selected(tracks, "sub", 0);
        JSONObject curSecondary = TvFormat.selected(tracks, "sub", 1);
        JSONObject curAid = TvFormat.selected(tracks, "audio", 0);
        Map<String, JSONObject> current = new HashMap<>();
        current.put("sid", curSid);
        current.put("secondary-sid", curSecondary);
        current.put("aid", curAid);
        Map<String, JSONObject> wanted = new HashMap<>();
        List<String> lines = new ArrayList<>();
        if (wantSubs && !subsChoice.isEmpty()) {
            String bottom = subsChoice.get(0);
            JSONObject wantedSid = TvFormat.pickSubtitle(subs, bottom, curSid, false);
            wanted.put("sid", wantedSid);
            String top = subsChoice.size() > 1 ? subsChoice.get(1) : null;
            JSONObject wantedSecondary = top != null ? TvFormat.pickSubtitle(subs, top, curSecondary, true) : null;
            wanted.put("secondary-sid", wantedSecondary);
            JSONObject track = wantedSid;
            if (TvFormat.fullTrack(track, bottom)) {
                lines.add(TvFormat.describeEnabled(track));
            } else if (track != null) {
                lines.add("Полных сабов " + bottom + " нет; включены: " + TvFormat.describeSubtitle(track));
            } else {
                lines.add(TvFormat.noFullSubtitles(subs, bottom));
            }
            if (top != null) {
                track = wantedSecondary;
                if (track != null) {
                    lines.add("Сабы сверху: " + TvFormat.describeFull(track));
                } else {
                    lines.add("Текстовых сабов " + top + " для верха нет; есть: " + TvFormat.describeSubtitles(subs));
                }
            }
        } else if (wantSubs) {
            lines.add(curSid != null ? TvFormat.describeEnabled(curSid) : "Сабы: выкл");
            if (curSecondary != null) {
                lines.add("Сабы сверху: " + TvFormat.describeFull(curSecondary));
            }
        }
        if (wantAudio && audioChoice != null) {
            JSONObject track = TvFormat.pickAudio(voices, audioChoice, curAid);
            if (track != null) wanted.put("aid", track);
            lines.add(track != null ? "Звук: " + TvFormat.describeAudio(track)
                    : "Звука " + audioChoice + " нет; есть: " + TvFormat.describeAudios(voices));
        } else if (wantAudio) {
            lines.add(curAid != null ? "Звук: " + TvFormat.describeAudio(curAid) : "Звук: выкл");
        }
        switchTracks(ipc, current, wanted);
        return lines;
    }

    /**
     * Port of Tv._switch: set each slot of `wanted` (sid, secondary-sid, aid; null means off) that `current`
     * does not already show. The top slot lets go of its track before the bottom one takes any, so mpv does
     * not ignore a switch to a track the other subtitle slot shows.
     */
    private static void switchTracks(MpvIpc ipc, Map<String, JSONObject> current, Map<String, JSONObject> wanted)
            throws MpvIpc.MpvError {
        String[] slots = {"sid", "secondary-sid", "aid"};
        List<String> changed = new ArrayList<>();
        for (String slot : slots) {
            if (wanted.containsKey(slot) && !sameId(trackId(wanted.get(slot)), trackId(current.get(slot)))) {
                changed.add(slot);
            }
        }
        Map<String, JSONObject> cur = new HashMap<>(current);
        if (changed.contains("secondary-sid") && cur.get("secondary-sid") != null) {
            ipc.set("secondary-sid", Boolean.FALSE);
            cur.put("secondary-sid", null);
        }
        for (String slot : changed) {
            if (!sameId(trackId(wanted.get(slot)), trackId(cur.get(slot)))) {
                JSONObject w = wanted.get(slot);
                ipc.set(slot, w != null ? (Object) w.optInt("id") : (Object) Boolean.FALSE);
            }
        }
    }

    /** `(track or {}).get("id")`: the track's id, or null when the track is null or has none. */
    private static Integer trackId(JSONObject t) {
        if (t == null || !t.has("id")) return null;
        return t.optInt("id");
    }

    private static boolean sameId(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    // --- Follower (src/tv.py 1414-1485) ----------------------------------------------------------------

    /**
     * Port of Tv._follow: (re)start the follower so the chosen languages stay on through the playlist the last
     * play loaded. Without a chosen language it only stops the running follower. {@code handled} is the path
     * of the item the caller has just set the tracks of, which the follower then leaves as they are. The
     * follower reads the release-state, so it is (re)started under releaseLock like the rest of it.
     */
    private void follow(String handled) {
        synchronized (releaseLock) {
            unfollowLocked();
            if ((!relSubs.isEmpty() || relAudio != null) && !relPlaylist.isEmpty()) {
                List<String> playlist = new ArrayList<>(relPlaylist);
                Thread t = new Thread(() -> followPlaylist(playlist, handled), "pcx-tv-follower");
                t.setDaemon(true);
                // Stored before start() so the thread's finally, which nulls the field only if it is still this
                // thread, sees it (the write happens-before the thread runs); the thread itself takes
                // releaseLock only briefly, in apply() and its finally, never across a blocking call.
                follower = t;
                t.start();
            }
        }
    }

    /** Port of Tv._unfollow: stop the follower; the chosen languages stay. Callable from TvMcpServer. */
    void unfollow() {
        synchronized (releaseLock) {
            unfollowLocked();
        }
    }

    /** _unfollow's body, with releaseLock already held; interrupting the thread is how it is cancelled. */
    private void unfollowLocked() {
        if (follower != null) {
            follower.interrupt();
            follower = null;
        }
    }

    /**
     * Port of Tv._follow_playlist: on each file-loaded mpv sends (it has chosen the item's tracks by then) put
     * the chosen languages on the item, switching only what is not right already: mpv carries each slot's
     * track over by its number, the right one only while the items' tracks are laid out alike. It does the
     * same for the item playing whenever it connects, as an item may have loaded while it had no connection,
     * apart from the {@code handled} item as long as no other has loaded since: a track the caller put on by
     * number stays.
     *
     * <p>Ends when an item outside {@code playlist} loads, or after FOLLOW_IDLE s without a connection to mpv;
     * the languages stay chosen either way. A bad answer does not end it. Runs on its own daemon thread with
     * its own mpv connection; cancellation is an interrupt, which unwinds it at any blocking point.
     */
    private void followPlaylist(List<String> playlist, String handled) {
        MpvIpc ipc = null;
        Long lostNanos = null;  // System.nanoTime() when the connection was first lost (time.monotonic)
        try {
            while (true) {
                JSONObject event;
                if (ipc == null) {
                    // _mpv() raises no ToolError on the box (no adb forward); MpvIpc.open returns null when mpv
                    // is not running, which the Python _mpv does too.
                    ipc = mpv();
                    if (ipc == null) {
                        long now = System.nanoTime();
                        if (lostNanos == null) lostNanos = now;
                        if (now - lostNanos >= FOLLOW_IDLE * 1_000_000_000L) {
                            Log.i(TAG, "follower: no mpv for " + FOLLOW_IDLE + " s; done");
                            return;
                        }
                        Thread.sleep(FOLLOW_INTERVAL * 1000L);
                        continue;
                    }
                    // First connection: apply to the item playing now too, bar the handled one.
                    lostNanos = null;
                    event = handled != null ? obj("event", "file-loaded", "handled", handled)
                            : obj("event", "file-loaded");
                } else {
                    event = ipc.takeEvent();
                    if (event != null && "file-loaded".equals(event.optString("event"))) {
                        handled = null;  // another item has loaded since the caller set the tracks
                    }
                }
                if (event == null) {  // the connection closed (END sentinel): reconnect
                    ipc.close();
                    ipc = null;
                    continue;
                }
                if (!"file-loaded".equals(event.optString("event"))) continue;
                try {
                    Object pathObj = ipc.get("path");
                    String path = pathObj == null ? null : pathObj.toString();
                    String eventHandled = event.has("handled") ? event.optString("handled") : null;
                    if (path == null || path.equals(eventHandled)) continue;
                    handled = null;  // another item plays: it has loaded since the caller set the tracks
                    if (!playlist.contains(path)) {
                        Log.i(TAG, "follower: " + path + " plays, not this playlist; done");
                        return;
                    }
                    Log.i(TAG, "follower: " + TvFormat.urlPathBasename(path) + ": "
                            + joinSemicolon(apply(ipc)));
                } catch (MpvIpc.MpvError | RuntimeException exc) {
                    // A bad answer must not end the task; a closed connection comes back as null from takeEvent.
                    Log.w(TAG, "follower: " + exc.getClass().getSimpleName() + ": " + exc.getMessage());
                }
            }
        } catch (InterruptedException cancelled) {
            // Cancelled (unfollow interrupted the thread): exit cleanly, keeping the interrupt flag.
            Thread.currentThread().interrupt();
        } finally {
            if (ipc != null) ipc.close();
            synchronized (releaseLock) {
                if (follower == Thread.currentThread()) follower = null;
            }
        }
    }

    /** `"; ".join(parts)` for the follower's log line. */
    private static String joinSemicolon(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // --- mpv on the box (src/tv.py 1083-1213) ----------------------------------------------------------

    /** Port of Tv._mpv: a connection to mpv's IPC, or null when mpv is not running (no adb bridge here). */
    private MpvIpc mpv() {
        return MpvIpc.open();
    }

    /** Port of Tv._start: start mpv-android with `playlistUrl` and wait until it has loaded it. */
    private MpvIpc start(String playlistUrl) throws ToolError, MpvIpc.MpvError {
        amStart("-a", "android.intent.action.VIEW", "-t", "video/any", "-d", shellQuote(playlistUrl));
        long deadline = System.nanoTime() + MPV_START_TIMEOUT * 1_000_000_000L;
        MpvIpc ipc = null;
        try {
            while (true) {
                if (ipc == null) ipc = MpvIpc.open();
                if (ipc != null && TvFormat.truthy(ipc.get("playlist-count"))) return ipc;
                if (System.nanoTime() - deadline >= 0) {
                    throw new ToolError("mpv не запустился за " + MPV_START_TIMEOUT + " с");
                }
                sleep(TvFormat.POLL_INTERVAL);
            }
        } catch (ToolError | MpvIpc.MpvError | RuntimeException e) {
            if (ipc != null) ipc.close();
            throw e;
        }
    }

    /** Port of Tv._front: bring the running mpv to the front without reloading what it plays. */
    private void front() throws ToolError {
        amStart("-f", REORDER_TO_FRONT);
    }

    /**
     * Port of Tv._am_start: {@code am start -n <activity> <args>} as root through su; am reports a failure as
     * an "Error" line yet exits 0, so the combined output is scanned for one. The playlist URL that `start`
     * passes is already shell-quoted.
     */
    private void amStart(String... args) throws ToolError {
        StringBuilder cmd = new StringBuilder("am start -n ").append(MPV_ACTIVITY);
        for (String a : args) cmd.append(' ').append(a);
        String output = su(cmd.toString(), AM_TIMEOUT_MS);
        for (String line : output.split("\n")) {
            // "adb:" for parity with the Python _am_start text and the su() messages in this file (the model
            // sees these strings; keep them 1:1 even though this port runs am through su, not adb).
            if (line.startsWith("Error")) throw new ToolError("adb: " + line);
        }
    }

    /**
     * Runs one shell command as root through {@code su --mount-master -c <script>} and returns its combined
     * stdout, trimmed; a watchdog destroys the process past the deadline. ToolError when su cannot start,
     * times out or exits non-zero. The app runs on the box, so `am` runs locally as root, replacing the
     * Python side's adb bridge (parity with AgentService/ControlServer).
     */
    private String su(String script, long timeoutMs) throws ToolError {
        Process proc = null;
        try {
            proc = new ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start();
            proc.getOutputStream().close();
            final Process p = proc;
            final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            final AtomicBoolean killed = new AtomicBoolean(false);
            Thread wd = new Thread(() -> {
                while (p.isAlive()) {
                    if (System.nanoTime() - deadline > 0) {
                        killed.set(true);
                        p.destroy();
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "pcx-tv-su");
            wd.setDaemon(true);
            wd.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            }
            int code = proc.waitFor();
            wd.interrupt();
            if (killed.get()) throw new ToolError("adb не ответил за " + (timeoutMs / 1000) + " с");
            String output = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (code != 0) {
                throw new ToolError("adb: " + (output.isEmpty() ? "код выхода " + code : lastLine(output)));
            }
            return output;
        } catch (IOException e) {
            if (proc != null) proc.destroy();
            throw new ToolError("adb не запустился: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroy();
            throw new ToolError("adb: прервано");
        }
    }

    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        return i < 0 ? s : s.substring(i + 1);
    }

    /** shlex.quote: return {@code s} unchanged when it is all shell-safe chars, else single-quote it. */
    private static String shellQuote(String s) {
        if (s.isEmpty()) return "''";
        if (SHLEX_SAFE.matcher(s).matches()) return s;
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Port of Tv._wait_playing: return once mpv plays `position` of its playlist (at that position with
     * time-pos advancing between two polls and, with `peer`, the torrent having an active peer). mpv failed
     * to open the item on an end-file error for an entry id &ge; `since`; a stream that never delivers ends the
     * wait at PLAYER_TIMEOUT; with `peer`, no active peer DEAD_TIMEOUT s after the start means the swarm is
     * gone. Either way mpv is stopped and a ToolError raised.
     */
    private void waitPlaying(MpvIpc ipc, String torrentHash, int position, int since, boolean peer)
            throws ToolError, MpvIpc.MpvError {
        long started = System.nanoTime();
        boolean peers = !peer;
        Double last = null;  // time-pos at `position` on the previous poll
        while (true) {
            double elapsed = (System.nanoTime() - started) / 1e9;
            if (!peers) {
                Object got = torrents(obj("action", "get", "hash", torrentHash));
                JSONObject t = got instanceof JSONObject ? (JSONObject) got : new JSONObject();
                peers = t.optInt("active_peers", 0) != 0;
            }
            for (JSONObject e : ipc.drain()) {
                if ("end-file".equals(e.optString("event")) && "error".equals(e.optString("reason"))
                        && e.optInt("playlist_entry_id", since) >= since) {
                    Log.i(TAG, torrentHash + ": mpv failed to open entry " + e.optInt("playlist_entry_id")
                            + ": " + e.optString("file_error"));
                    abortOpen(ipc, torrentHash, "mpv не смог открыть серию");
                }
            }
            Object atObj = ipc.get("playlist-pos");
            Object nowObj = ipc.get("time-pos");
            boolean atPos = atObj instanceof Number && ((Number) atObj).intValue() == position;
            if (atPos && nowObj != null) {
                double now = ((Number) nowObj).doubleValue();
                if (last != null && now > last && peers) return;
                last = now;
            } else {
                last = null;
            }
            if (!peers && elapsed >= TvFormat.DEAD_TIMEOUT) {
                stop(ipc);
                throw new ToolError(DEAD);
            }
            if (elapsed >= PLAYER_TIMEOUT) {
                abortOpen(ipc, torrentHash, "mpv не включил серию за " + PLAYER_TIMEOUT + " с");
            }
            sleep(TvFormat.POLL_INTERVAL);
        }
    }

    /** Port of Tv._stop: stop mpv and wait until it is gone (idle=once closes the activity and the IPC socket). */
    private static void stop(MpvIpc ipc) throws MpvIpc.MpvError {
        try {
            ipc.command("stop");
        } catch (MpvIpc.IpcClosed ignored) {
            // mpv may close before the reply.
        }
        if (!ipc.waitClosed(MPV_STOP_TIMEOUT * 1000L)) {
            Log.w(TAG, "mpv still answers " + MPV_STOP_TIMEOUT + " s after stop");
        }
    }

    /** Port of Tv._abort_open: stop mpv and raise `why` with how the torrent is doing in TorrServer. */
    private void abortOpen(MpvIpc ipc, String torrentHash, String why) throws ToolError, MpvIpc.MpvError {
        stop(ipc);
        JSONObject torrent;
        try {
            Object got = torrents(obj("action", "get", "hash", torrentHash));
            torrent = got instanceof JSONObject ? (JSONObject) got : new JSONObject();
        } catch (ToolError e) {
            torrent = new JSONObject();
        }
        int peers = torrent.optInt("active_peers", 0);
        double speed = torrent.optDouble("download_speed", 0);
        throw new ToolError(why + ": пиров " + peers + ", раздача качается "
                + TvFormat.fmt0(speed / 1024.0) + " КБ/с");
    }

    // --- TorrServer / Jackett --------------------------------------------------------------------------

    /**
     * Port of Tv._request: the response body bytes of one HTTP call; the failure messages are the Python
     * ones, and never carry the URL (Jackett's URL carries the API key).
     */
    private byte[] request(String service, Request req) throws ToolError {
        return request(client, service, req);
    }

    /** As {@link #request(String, Request)}, but on a given client (the preload uses a no-timeout one). */
    private byte[] request(OkHttpClient c, String service, Request req) throws ToolError {
        return request(c, service, req, null);
    }

    /** As above, publishing the live {@link Call} to {@code callOut} (when non-null) so a canceller can abort it. */
    private byte[] request(OkHttpClient c, String service, Request req, AtomicReference<Call> callOut) throws ToolError {
        Call call = c.newCall(req);
        if (callOut != null) callOut.set(call);
        try (Response response = call.execute()) {
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
        return ffprobe(torrentHash, fileId, null);
    }

    /** As above, publishing the live {@link Call} to {@code callOut} (when non-null) so prepare can abort it. */
    private JSONObject ffprobe(String torrentHash, int fileId, AtomicReference<Call> callOut) {
        OkHttpClient probeClient = client.newBuilder()
                .readTimeout(TvFormat.FFPROBE_TIMEOUT, TimeUnit.SECONDS).build();
        Request req = new Request.Builder()
                .url(torrserverUrl + "/ffp/" + torrentHash + "/" + fileId).get().build();
        Call call = probeClient.newCall(req);
        if (callOut != null) callOut.set(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) return null;
            ResponseBody body = response.body();
            return body == null ? null : new JSONObject(body.string());
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Port of Tv._preload: have TorrServer preload one file of a torrent into its cache; true once TorrServer
     * has answered. Run as a task and cancelled when prepare is done, so it carries no timeout (Python
     * timeout=None): the preload takes no request context and goes on inside TorrServer's /stream handler.
     */
    private boolean preload(String torrentHash, int fileId, AtomicReference<Call> callOut) {
        HttpUrl url = HttpUrl.parse(torrserverUrl + "/stream").newBuilder()
                .addQueryParameter("link", torrentHash)
                .addQueryParameter("index", String.valueOf(fileId))
                .addQueryParameter("preload", "")
                .build();
        // No timeout: the task lives no longer than prepare, which cancels it (through callOut).
        OkHttpClient preloadClient = client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        try {
            request(preloadClient, "TorrServer", new Request.Builder().url(url).get().build(), callOut);
            return true;
        } catch (ToolError exc) {
            Log.w(TAG, "preload " + torrentHash + ": " + exc.getMessage());
            return false;
        }
    }

    /**
     * Port of Tv._save: save a torrent the player has started to TorrServer's database (/stream with `save`
     * and neither `index` nor `play`). A failure is logged and fails nothing.
     */
    private void save(String torrentHash) {
        HttpUrl url = HttpUrl.parse(torrserverUrl + "/stream").newBuilder()
                .addQueryParameter("link", torrentHash)
                .addQueryParameter("save", "")
                .build();
        try {
            request("TorrServer", new Request.Builder().url(url).get().build());
        } catch (ToolError exc) {
            Log.w(TAG, "save " + torrentHash + ": " + exc.getMessage());
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

    /** Port of Tv._load: resolveFiles, plus the file `file` or the one of the episode asked for (pick_file). */
    private LoadResult load(int resultId, Integer season, Integer episode, Integer file) throws ToolError {
        FilesResult fr = resolveFiles(resultId);
        return new LoadResult(fr.result, fr.hash, TvFormat.pickFile(fr.files, season, episode, file));
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

    /** The Python _load return: (result, info hash, picked file). */
    private static final class LoadResult {
        final JSONObject result;
        final String hash;
        final JSONObject file;

        LoadResult(JSONObject result, String hash, JSONObject file) {
            this.result = result;
            this.hash = hash;
            this.file = file;
        }
    }
}
