#!/usr/bin/env python3
"""Talk to a running TurnForge server using only Python's standard library."""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--model", default="demo")
    parser.add_argument("--session", help="Continue a persisted session")
    parser.add_argument("--once", help="Send one message and exit")
    parser.add_argument("--timeout", type=float, default=330, help="Maximum wait in seconds")
    args = parser.parse_args()

    def request(path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(args.base_url.rstrip("/") + path, data=data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as response:
            body = response.read()
            return json.loads(body) if body else None

    sessions = "/v1/agents/sessions"
    session_id = args.session or request(sessions, {"agent": {
        "name": "chat", "model": args.model, "instructions": "Be helpful and concise.",
        "context_window_tokens": 32768, "max_output_tokens": 4096}})["id"]
    path = sessions + "/" + session_id
    print(f"Session: {session_id}", file=sys.stderr)
    while True:
        try:
            text = args.once if args.once is not None else input("You: ")
        except EOFError:
            return
        if not text.strip():
            if args.once is not None:
                parser.error("--once must not be blank")
            continue
        turn = request(path + "/turns", {"input": text})
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            current = request(path + "/turns/" + turn["id"])
            if current["status"] in ("completed", "failed", "cancelled"):
                if current["status"] != "completed":
                    raise RuntimeError(current.get("error") or current["status"])
                print(current.get("output", ""))
                break
            if current["status"] == "waiting":
                action = request(path)["required_actions"][0]
                print(json.dumps(action, ensure_ascii=False), file=sys.stderr)
                print("Submit this action's result through POST /events, then continue waiting.", file=sys.stderr)
                # Leave the action waiting; application tools may have arbitrary result schemas.
                return
            time.sleep(0.15)
        else:
            raise TimeoutError(f"Still running: {session_id}/{turn['id']}; inspect or cancel the turn")
        if args.once is not None:
            return


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, urllib.error.HTTPError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
