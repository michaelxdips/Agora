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

Binds 0.0.0.0 so an emulator can reach it on 10.0.2.2.
"""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8077
requests_seen = []
fail_mode = False
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
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
            failing = fail_mode

        if failing:
            # Same shape a real provider uses for an error; the client turns any non-2xx into a
            # typed failure, which is what drives the offline queue.
            self._send(503, {"error": {"message": "mock provider is simulating offline"}})
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
        global fail_mode
        if self.path == "/__requests":
            with lock:
                self._send(200, requests_seen)
        elif self.path == "/__reset":
            with lock:
                requests_seen.clear()
            self._send(200, {"ok": True})
        elif self.path == "/__fail":
            with lock:
                fail_mode = True
            self._send(200, {"fail_mode": True})
        elif self.path == "/__ok":
            with lock:
                fail_mode = False
            self._send(200, {"fail_mode": False})
        else:
            with lock:
                self._send(200, {"fail_mode": fail_mode, "requests": len(requests_seen)})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"mock provider listening on 0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
