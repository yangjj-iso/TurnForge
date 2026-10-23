#!/usr/bin/env python3
"""Acceptance test of the packaged JAR, real HTTP/SSE, restart, and stdio MCP.

No API key or external service is needed: the model endpoint is a deterministic
local protocol fixture, not an evaluation of real model quality.
"""
import argparse
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = {"completed", "failed", "cancelled"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ModelFixture(http.server.BaseHTTPRequestHandler):
    slow_started = threading.Event()
    slow_calls = 0
    counter_lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_POST(self):
        if self.path != "/v1/chat/completions" or self.headers.get("Authorization") != "Bearer fixture-only":
            self.send_error(401)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = request["messages"]
        last = messages[-1]
        text = next(m["content"] for m in reversed(messages) if m["role"] == "user")
        if text == "slow":
            with self.counter_lock:
                type(self).slow_calls += 1
            self.slow_started.set()
            time.sleep(8)
        if last["role"] == "tool":
            message = {"role": "assistant", "content": "Tool result: " + last["content"]}
        elif text in ("mcp echo", "approval"):
            name = "mcp__demo__echo" if text == "mcp echo" else "approval"
            catalog = {tool["function"]["name"] for tool in request["tools"]}
            require(name in catalog, "application/MCP tool not exposed to model")
            arguments = {"text": "hello from MCP"} if text == "mcp echo" else {"reason": "example"}
            message = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call_" + name, "type": "function", "function": {
                    "name": name, "arguments": json.dumps(arguments)}}]}
        else:
            message = {"role": "assistant", "content": "Reply: " + text}
        response = json.dumps({"choices": [{"index": 0, "finish_reason": "tool_calls"
                                            if "tool_calls" in message else "stop", "message": message}]}).encode()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        except (BrokenPipeError, ConnectionResetError):
            pass  # A cancelled/timed-out model request deliberately disconnects.


class Application:
    def __init__(self, jar, folder, provider_port):
        folder.mkdir(parents=True, exist_ok=True)
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        self.folder = folder
        self.process = None
        self.log = (folder / "server.log").open("ab")
        self.command = [shutil.which("java") or "java", "-jar", str(jar),
                        f"--server.port={self.port}", "--server.address=127.0.0.1",
                        f"--redy.data-dir={folder / 'sessions'}", "--redy.cube.enabled=false",
                        "--redy.runtime.turn-timeout=2s", "--redy.sse.heartbeat-seconds=1",
                        "--redy.runtime.max-concurrent-turns=1", "--redy.runtime.max-queued-turns=1"]
        if provider_port is not None:
            self.command += [
                        f"--redy.model.base-url=http://127.0.0.1:{provider_port}/v1",
                        "--redy.model.api-key=fixture-only", "--redy.model.request-timeout=20s",
                        "--spring.profiles.active=example-tools", "--redy.mcp.servers[0].name=demo",
                        "--redy.mcp.servers[0].transport=stdio",
                        f"--redy.mcp.servers[0].command={sys.executable}",
                        f"--redy.mcp.servers[0].args[0]={ROOT / 'examples/mcp_echo.py'}"]

    def start(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("REDY_", "SPRING_", "CUBE_", "E2B_"))}
        self.process = subprocess.Popen(self.command, cwd=ROOT, env=env,
                                        stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("JAR exited during startup; see " + str(self.folder / "server.log"))
            try:
                if self.request("/healthz")[1] == {"status": "ok"}:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.15)
        raise TimeoutError("JAR did not become healthy")

    def stop(self, crash=False):
        if self.process and self.process.poll() is None:
            self.process.kill() if crash else self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)

    def request(self, path, payload=None, headers=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(self.base + path, data=data,
                                     headers={"Content-Type": "application/json", **(headers or {})})
        try:
            response = urllib.request.urlopen(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            return response.status, json.loads(body) if body else None

    def create(self):
        code, session = self.request("/v1/agents/sessions", {"agent": {
            "name": "smoke", "model": "fixture", "context_window_tokens": 16384,
            "max_output_tokens": 2048}})
        require(code == 201, f"create failed: {code}: {session}")
        return "/v1/agents/sessions/" + session["id"]

    def turn(self, path, text):
        code, turn = self.request(path + "/turns", {"input": text})
        require(code == 202, f"start failed: {code}: {turn}")
        return turn["id"]

    def await_turn(self, path, turn_id, statuses=TERMINAL):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            code, turn = self.request(path + "/turns/" + turn_id)
            require(code == 200, f"get turn failed: {code}")
            if turn["status"] in statuses:
                return turn
            time.sleep(0.05)
        raise TimeoutError("turn did not reach " + repr(statuses))


def run(jar, folder):
    provider = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
    threading.Thread(target=provider.serve_forever, daemon=True).start()
    app = Application(jar, folder, provider.server_port)
    results = []

    def passed(name):
        results.append(name)
        print("PASS " + name, flush=True)

    try:
        demo = Application(jar, folder / "demo", None)
        try:
            demo.start()
            result = subprocess.run([sys.executable, str(ROOT / "examples/chat.py"),
                                     "--base-url", demo.base, "--once", "Hello"],
                                    text=True, capture_output=True, timeout=15)
            require(result.returncode == 0 and result.stdout.strip() == "Demo response: Hello",
                    "default demo CLI failed: " + result.stderr)
            session_id = result.stderr.strip().split("Session: ")[-1]
            result = subprocess.run([sys.executable, str(ROOT / "examples/chat.py"),
                                     "--base-url", demo.base, "--session", session_id,
                                     "--once", "Continue"], text=True, capture_output=True, timeout=15)
            require(result.returncode == 0 and result.stdout.strip() == "Demo response: Continue",
                    "CLI session continuation failed: " + result.stderr)
            passed("default offline startup, CLI conversation and session continuation")
        finally:
            demo.stop()
            demo.log.close()
        app.start()
        path = app.create()
        first = app.turn(path, "hello")
        require(app.await_turn(path, first).get("output") == "Reply: hello", "model output mismatch")
        passed("packaged JAR startup and remote-model HTTP adapter")

        turn = app.turn(path, "mcp echo")
        require(app.await_turn(path, turn).get("output") == "Tool result: hello from MCP", "MCP result mismatch")
        _, items = app.request(path + "/items")
        calls = [item for item in items["data"] if item.get("call_id") == "call_mcp__demo__echo"]
        require({item["type"] for item in calls} == {"function_call", "function_call_output"}, "unpaired MCP items")
        passed("real stdio MCP initialization, discovery, tool execution and persisted result")

        _, replay = app.request(path + "/events")
        events = replay["events"]
        require([e["sequence"] for e in events] == list(range(1, len(events) + 1)), "noncontiguous event sequence")
        require(any(e["type"] == "model.call.completed" for e in events), "missing model observation")
        cursor = events[2]["sequence"]
        _, tail = app.request(path + "/events", headers={"Last-Event-ID": str(cursor)})
        require(tail["events"] == events[3:], "JSON cursor replay mismatch")
        req = urllib.request.Request(app.base + path + "/events", headers={
            "Accept": "text/event-stream", "Last-Event-ID": str(cursor)})
        with urllib.request.urlopen(req, timeout=5) as stream:
            require(stream.readline().decode().strip() == f"id:{cursor + 1}", "SSE resume ID mismatch")
            require(stream.readline().decode().startswith("event:"), "missing SSE event name")
            payload = json.loads(stream.readline().decode().removeprefix("data:"))
            require(payload["event_id"] == events[3]["event_id"], "SSE stable event ID mismatch")
        passed("JSON and SSE Last-Event-ID replay with stable event identities")

        slow = app.create()
        ModelFixture.slow_started.clear()
        turn = app.turn(slow, "slow")
        require(ModelFixture.slow_started.wait(5), "slow model not entered")
        code, _ = app.request(slow + "/turns/" + turn + "/cancel", {})
        require(code == 200, "cancel rejected")
        require(app.await_turn(slow, turn)["status"] == "cancelled", "cancel not terminal")
        passed("active model request cancellation")

        ModelFixture.slow_started.clear()
        turn = app.turn(slow, "slow")
        timed_out = app.await_turn(slow, turn)
        require(timed_out["status"] == "failed" and timed_out.get("error") == "turn execution timed out", "deadline mismatch")
        passed("execution deadline produces a failed turn and releases the worker")

        ModelFixture.slow_started.clear()
        busy_a, busy_b = app.create(), app.create()
        first_busy = app.turn(busy_a, "slow")
        require(ModelFixture.slow_started.wait(5), "capacity test did not start worker")
        second_busy = app.turn(busy_b, "slow")
        code, rejection = app.request("/v1/agents/sessions", {
            "agent": {"name": "overloaded", "model": "fixture"}, "input": "slow"})
        require(code == 503, "overloaded creation did not return 503")
        details = rejection["error"]
        rejected_path = "/v1/agents/sessions/" + details["session_id"]
        require(app.await_turn(rejected_path, details["turn_id"])["status"] == "failed",
                "rejection lost session/turn identity")
        require(app.await_turn(busy_a, first_busy)["status"] == "failed", "busy turn did not time out")
        require(app.await_turn(busy_b, second_busy)["status"] == "failed", "queued turn did not time out")
        passed("bounded worker/queue overload with inspectable session and turn IDs")

        waiting = app.create()
        waiting_turn = app.turn(waiting, "approval")
        app.await_turn(waiting, waiting_turn, {"waiting"})
        _, pending_before = app.request(waiting)
        app.stop()
        app.start()
        _, pending_after = app.request(waiting)
        require(pending_after["status"] == "requires_action", "waiting action lost across restart")
        require(pending_after["required_actions"] == pending_before["required_actions"], "action identity changed")
        result = {"events": [{"type": "agent.session.input.tool_result", "turn_id": waiting_turn,
                              "call_id": "call_approval", "success": True, "output": "approved"}]}
        require(app.request(waiting + "/events", result)[0] == 202, "external result rejected")
        require(app.await_turn(waiting, waiting_turn).get("output") == "Tool result: approved", "continuation failed")
        require(app.request(waiting + "/events", result)[0] == 202, "duplicate result not idempotent")
        result["events"][0]["output"] = "different"
        require(app.request(waiting + "/events", result)[0] == 409, "conflicting result must fail")
        _, after = app.request(path + "/events")
        require(after == replay, "completed history changed after restart")
        passed("restart during external handoff, continuation and result deduplication")

        ModelFixture.slow_started.clear()
        interrupted = app.create()
        turn = app.turn(interrupted, "slow")
        require(ModelFixture.slow_started.wait(5), "interrupted model not entered")
        with ModelFixture.counter_lock:
            before_calls = ModelFixture.slow_calls
        app.stop(crash=True)
        app.start()
        recovered = app.await_turn(interrupted, turn)
        require(recovered["status"] == "failed" and "restarted" in recovered.get("error", ""), "unsafe recovery state")
        with ModelFixture.counter_lock:
            require(ModelFixture.slow_calls == before_calls, "interrupted model request was replayed")
        followup = app.turn(interrupted, "after recovery")
        require(app.await_turn(interrupted, followup).get("output") == "Reply: after recovery", "session unusable after recovery")
        passed("process crash recovery without replaying uncertain effects")
        (folder / "result.json").write_text(json.dumps({"passed": results, "count": len(results)}, indent=2) + "\n")
        print(f"Acceptance passed: {len(results)} scenarios", flush=True)
    finally:
        app.stop()
        app.log.close()
        provider.shutdown()
        provider.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/turnforge.jar")
    parser.add_argument("--output-dir", type=Path, help="Keep logs, snapshots and result JSON here")
    args = parser.parse_args()
    require(args.jar.is_file(), "Build first: ./mvnw verify")
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        require(not (args.output_dir / "sessions").exists(), "Use a fresh output directory")
        run(args.jar.resolve(), args.output_dir.resolve())
    else:
        with tempfile.TemporaryDirectory(prefix="turnforge-smoke-") as folder:
            run(args.jar.resolve(), Path(folder))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
