"""Mock OpenAI-compatible endpoint, so the watch's real HTTP path can be exercised end-to-end.

Why this exists: every previous wear verification stopped at the app UI, because the model call
needs a real API key (HS2, owner-deferred). A local server that speaks the same
`/chat/completions` contract removes that blocker for the transport half — the watch still makes a
real OkHttp call over a real socket, parses a real response, and drives the real offline queue. What
it does NOT prove is any specific provider's behaviour; that still needs HS2.

The `/__fail` toggle exists so "offline" can be simulated without killing the server or changing the
watch's config: a 500 makes `WearChatClient.ask` return a failure, which is the same code path a
dropped connection takes, and the question is held. Flipping it back to 200 lets the SAME config
succeed — which is what makes the auto-drain test meaningful, since the config (and therefore the
queue) is never cleared.

## Adversarial modes (added for the Pass-B device audit)

A provider that is merely *offline* is the easy case. These modes answer with something a real
provider, a captive portal or a broken proxy really does return, and each one is a distinct way for a
client to crash or to silently show the wrong thing:

| toggle | what it answers | what it is testing |
|---|---|---|
| `/__html` | a 200 with an HTML page | a captive portal. A client that trusts `Content-Type` or assumes JSON dies here |
| `/__broken` | a 200 with truncated JSON | a connection cut mid-body |
| `/__empty` | a 200 with `choices: []` | a provider that reports success and no content |
| `/__nocontent` | a 200 with a choice whose `content` is `null` | the same, one level deeper |
| `/__slow` | a 200 after `SLOW_SECONDS` | the read timeout. The watch's is 60 s, so this is the case that decides whether a hang is possible |
| `/__huge` | a 200 with a 200 KB answer | an answer that cannot fit on a 384 px watch |
| `/__reset` | clears the mode and the request log | — |

Binds 0.0.0.0 so an emulator can reach it on 10.0.2.2.
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8077
SLOW_SECONDS = 8
requests_seen = []
fail_mode = False
mode = "ok"
lock = threading.Lock()

VALID_MODES = ("ok", "fail", "html", "broken", "empty", "nocontent", "slow", "huge")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_raw(self, status, body: bytes, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global fail_mode
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8", "replace")
        auth = self.headers.get("Authorization", "")
        try:
            body = json.loads(raw)
            user = next((m.get("content") for m in body.get("messages", [])
                         if m.get("role") == "user"), "")
            has_system = any(m.get("role") == "system" for m in body.get("messages", []))
            model = body.get("model", "")
        except Exception as exc:                                    # noqa: BLE001
            user, has_system, model = f"<unparseable: {exc}>", False, ""

        with lock:
            requests_seen.append({"path": self.path, "auth": auth, "user": user,
                                  "has_system": has_system, "model": model})
            active = "fail" if fail_mode else mode

        if active == "fail":
            # Same shape a real provider uses for an error; the client turns any non-2xx into a
            # typed failure, which is what drives the offline queue.
            self._send(503, {"error": {"message": "mock provider is simulating offline"}})
            return

        if active == "html":
            # A captive portal answers 200 with a login page. A client that does not check the body
            # will try to parse this as JSON.
            self._send_raw(200, b"<html><body><h1>Sign in to Wi-Fi</h1></body></html>", "text/html")
            return

        if active == "broken":
            # 200, correct Content-Length, but the JSON is truncated: the body arrives and does not parse.
            truncated = b'{"id":"chatcmpl-mock","choices":[{"message":{"role":"assistant","con'
            self._send_raw(200, truncated)
            return

        if active == "empty":
            self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion",
                             "model": model, "choices": []})
            return

        if active == "nocontent":
            self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion", "model": model,
                             "choices": [{"index": 0, "finish_reason": "stop",
                                          "message": {"role": "assistant", "content": None}}]})
            return

        if active == "slow":
            time.sleep(SLOW_SECONDS)
            self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion", "model": model,
                             "choices": [{"index": 0, "finish_reason": "stop",
                                          "message": {"role": "assistant",
                                                      "content": "SLOW-ANSWER"}}]})
            return

        if active == "huge":
            self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion", "model": model,
                             "choices": [{"index": 0, "finish_reason": "stop",
                                          "message": {"role": "assistant",
                                                      "content": "HUGE " + ("Z" * 200_000)}}]})
            return

        reply = f"MOCK-ANSWER to: {user}"
        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": model,
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": reply}}],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
        })

    def do_GET(self):
        global fail_mode, mode
        if self.path == "/__requests":
            with lock:
                self._send(200, requests_seen)
        elif self.path == "/__reset":
            with lock:
                requests_seen.clear()
                mode = "ok"
                fail_mode = False
            self._send(200, {"ok": True})
        elif self.path == "/__fail":
            with lock:
                fail_mode = True
            self._send(200, {"fail_mode": True})
        elif self.path == "/__ok":
            with lock:
                fail_mode = False
                mode = "ok"
            self._send(200, {"fail_mode": False, "mode": "ok"})
        elif self.path.startswith("/__"):
            name = self.path[3:]   # strip the leading "/__" — [2:] leaves a stray underscore
            if name not in VALID_MODES:
                self._send(404, {"error": f"unknown mode {name}", "valid": list(VALID_MODES)})
                return
            with lock:
                mode = name
                fail_mode = (name == "fail")
            self._send(200, {"mode": name})
        else:
            with lock:
                self._send(200, {"fail_mode": fail_mode, "mode": mode,
                                 "requests": len(requests_seen)})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"mock provider listening on 0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
