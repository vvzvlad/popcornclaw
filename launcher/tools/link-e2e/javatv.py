"""JavaTV: the launcher's real Omarchy Link server (LinkServer.java, run on the JVM by LinkHarness) behind
the same interface as omarchy-link's mock/mock_tv.py MockTV, so the PC test-suite can run against it.

The JVM harness is the real protocol code with a fake launcher (FakeHost): config edits go to
<data>/files/omarchytv.json, secrets to <data>/internal/link.json, exactly like the mock's layout.
"""
from __future__ import annotations

import base64
import json
import os
import queue
import secrets
import subprocess
import threading
import time
from pathlib import Path

LAUNCHER = Path(__file__).resolve().parents[2]
BUILD = LAUNCHER / "app/build/intermediates/javac"
MAIN = BUILD / "debug/compileDebugJavaWithJavac/classes"
TEST = BUILD / "debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"
JAVA = os.environ.get("JAVA", "/workspace/toolchain/jdk-17.0.20.1+1/bin/java")


# Resolved at import: the PC tests replace HOME with a temporary one.
_JARS = sorted(str(p) for p in (Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").rglob("json-*.jar"))


def _json_jar() -> str:
    if _JARS:
        return _JARS[-1]
    raise RuntimeError("org.json jar not in the Gradle cache: run ./gradlew testDebugUnitTest once")


class _Conn:
    def __init__(self, tv):
        self.tv = tv

    def send_text(self, text):
        self.tv._cmd("rawframe " + text)

    def close(self):
        pass


class JavaTV:
    def __init__(self, data_dir, code: str = "", name: str = "Harness BRAVIA", host: str = "127.0.0.1",
                 port: int = 0, pairing_open: bool = True, cast: bool = True, cast_upload_max: int = 64 << 20,
                 cast_kinds=("url", "youtube", "video", "audio", "image", "text"), cast_play: bool = True):
        self.data_dir = Path(data_dir)
        self.files = self.data_dir / "files"
        (self.data_dir / "internal").mkdir(parents=True, exist_ok=True)
        self.files.mkdir(parents=True, exist_ok=True)
        self.name = name
        self.host = host
        self._code = code or f"{secrets.randbelow(10**6):06d}"
        self._port = port
        self._pairing = pairing_open
        self.responder = None
        self.proc = None
        self._replies: queue.Queue = queue.Queue()
        self.received: queue.Queue = queue.Queue()
        self.seen: list = []
        self._lock = threading.Lock()
        self._seq = [0]
        self._cast = (cast, cast_upload_max, list(cast_kinds), cast_play)
        self.cast_live_bytes = 4096         # a live stream is read this far, then "stopped" (as in mock_tv)

    # -- lifecycle
    def start(self, mdns_responder: bool = False, **_kw) -> "JavaTV":
        cp = os.pathsep.join([str(MAIN), str(TEST), _json_jar()])
        env = dict(os.environ, OTV_LIMITS=os.environ.get("OTV_LIMITS", "0"))
        self.proc = subprocess.Popen([JAVA, "-cp", cp, "com.omarchytv.home.link.LinkHarness", str(self.files), str(self._port),
                                      self._code, str(self.data_dir / "internal/link.json")],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env)
        first = self.proc.stdout.readline().split()
        assert first and first[0] == "READY", first
        self.port, self.tv_id = int(first[1]), first[3]
        threading.Thread(target=self._reader, daemon=True).start()
        if not self._pairing:
            self._cmd("pairing off")
        on, upmax, kinds, play = self._cast
        self._cmd("cast " + ("on" if on else "off"))
        self._cmd(f"castmax {int(upmax)}")
        self._cmd("castkinds " + ",".join(kinds))
        self._cmd("castplay " + ("on" if play else "off"))
        self._cmd(f"castlive {int(self.cast_live_bytes)}")
        if mdns_responder:
            raise NotImplementedError("mDNS is NsdManager on the TV; not part of the JVM harness")
        return self

    def _reader(self):
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            if line.startswith("EVENT "):
                _, typ, data = line.split(" ", 2)
                msg = {"type": typ, "data": json.loads(data)}
                self.seen.append(msg)   # every PC event, in order (tests index into it)
                self.received.put(msg)
            else:
                self._replies.put(line)

    def _cmd(self, line: str) -> str:
        with self._lock:
            self.proc.stdin.write(line + "\n")
            self.proc.stdin.flush()
            return self._replies.get(timeout=10)

    def close(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.stdin.close()
            except OSError:
                pass
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.responder:
            self.responder.close()

    # -- what the tests read
    @property
    def code(self) -> str:
        return self._cmd("code")

    @property
    def pairing_open(self) -> bool:
        return self._pairing

    @pairing_open.setter
    def pairing_open(self, v: bool):
        self._pairing = v
        self._cmd("pairing " + ("on" if v else "off"))

    @property
    def pcs(self) -> dict:
        try:
            return json.loads((self.data_dir / "internal/link.json").read_text()).get("pcs", {})
        except (OSError, ValueError):
            return {}

    @property
    def log(self) -> list:
        try:
            lines = (self.files / "requests.log").read_text().splitlines()
        except OSError:
            return []
        out = []
        for l in lines:
            m, p, n = l.split(" ")
            out.append((m, p, int(n)))
        return out

    @property
    def clients(self) -> list:
        n = int(self._cmd("clients"))
        return [(_Conn(self), b"", self._seq, threading.Lock()) for _ in range(n)]

    def config(self) -> dict:
        try:
            return json.loads((self.files / "omarchytv.json").read_text())
        except (OSError, ValueError):
            return {}

    # -- cast (what tests read, same names as mock_tv.MockTV)
    def _jsonl(self, name: str) -> list:
        try:
            return [json.loads(l) for l in (self.files / name).read_text().splitlines() if l.strip()]
        except OSError:
            return []

    @property
    def casts(self) -> list:
        return self._jsonl("casts.jsonl")

    @property
    def cast_controls(self) -> list:
        return self._jsonl("controls.jsonl")

    @property
    def cast_fetched(self) -> dict:
        d = self.files / "fetched"
        return {p.name: p.read_bytes() for p in d.iterdir()} if d.is_dir() else {}

    @property
    def cast_enabled(self) -> bool:
        return self._cast[0]

    @cast_enabled.setter
    def cast_enabled(self, v: bool):
        self._cast = (v,) + self._cast[1:]
        self._cmd("cast " + ("on" if v else "off"))

    def status(self) -> dict:
        return json.loads(self._cmd("status"))

    # -- what the tests do
    def set_state(self, screensaver=None, **_kw):
        if screensaver is not None:
            self._cmd("screensaver " + ("on" if screensaver else "off"))

    def open_app(self, package: str) -> bool:
        return self._cmd("launch " + package) == "true"

    def broadcast(self, msg_type: str, data: dict):
        if msg_type == "shortcut":
            self._cmd("shortcut " + data["command"])
        elif msg_type == "agent_snapshot":
            self._cmd("agentsnap")
        elif msg_type == "agent_ask":
            self._cmd(f"agentask {data['id']} {data['agentId']} {data.get('text', '')}")
        else:
            raise NotImplementedError(msg_type)

    def ask(self, text: str, provider=None, session=None, lang=None, timeout: float = 30) -> dict:
        while not self.received.empty():
            self.received.get_nowait()
        r = self._cmd(f"ask {provider or '-'} {session or '-'} {text}")
        if not r.startswith("SENT "):
            return {"chunks": [], "error": {"error": r.split(" ", 1)[-1]}}
        rid = r.split()[1]
        chunks, deadline = [], time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.received.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                break
            d = msg.get("data") or {}
            if d.get("id") != rid:
                continue
            if msg["type"] == "answer_error":
                return {"chunks": chunks, "error": d}
            if msg["type"] == "answer":
                if d.get("done"):
                    return {"chunks": chunks, "final": d}
                chunks.append(d.get("text", ""))
        return {"chunks": chunks, "error": {"error": "timeout"}}

    def wait_event(self, kind: str, pred=lambda d: True, timeout: float = 15, after: int = 0):
        """First PC event of `kind` (from index `after` of seen) whose data matches pred."""
        end = time.time() + timeout
        while time.time() < end:
            for m in list(self.seen[after:]):
                if m.get("type") == kind and pred(m.get("data") or {}):
                    return m["data"]
            time.sleep(0.05)
        return None

    def agents(self) -> dict:
        """GET /v1/agents as the TV serves it."""
        return json.loads(self._cmd("agents"))

    def agent_ask(self, agent_id: str, text: str, timeout: float = 20) -> list:
        """What the TV's agent view does (LinkServer.agentAsk): send "agent_ask", collect replies until
        agent_answer / agent_ask_error. A request the TV refuses itself (empty text, busy, offline) comes
        back as a local agent_ask_error with the same code, since it never reaches the PC."""
        rid = secrets.token_hex(8)
        start = len(self.seen)
        r = self._cmd(f"agentask {rid} {agent_id} {text}".rstrip())
        if not r.startswith("SENT "):
            return [("agent_ask_error", {"id": rid, "agentId": agent_id, "error": r.split(" ", 1)[-1], "message": "refused on the TV"})]
        end, out = time.time() + timeout, []
        while time.time() < end:
            out = [m for m in self.seen[start:] if (m.get("data") or {}).get("id") == rid]
            if any(m["type"] in ("agent_answer", "agent_ask_error") for m in out):
                break
            time.sleep(0.05)
        return [(m["type"], m["data"]) for m in out]

    def set_ai_sources(self, enabled, timeout: float = 15) -> dict:
        """What the TV's AI-pane switches do (LinkServer.aiSources): send "ai_sources", wait for the reply."""
        arg = ("raw:" + enabled) if isinstance(enabled, str) else (",".join(enabled) or "-")
        r = self._cmd("aisources " + arg)
        if not r.startswith("SENT "):
            return {"type": "not_sent", "data": {"error": r.split(" ", 1)[-1]}}
        rid = r.split()[1]
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.received.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                break
            if (msg.get("data") or {}).get("id") == rid and msg.get("type") in ("ai_sources_ok", "ai_sources_error"):
                return msg
        return {"type": "timeout"}


MockTV = JavaTV
