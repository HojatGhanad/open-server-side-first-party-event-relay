"""Stand-in for the real sGTM container, so the relay can be tested without one.

Answers collect requests with 204 and an FPID cookie, and echoes the forwarded
visitor IP, host and preview header back in X-Mock-Saw-* response headers.

    python3 tools/mock_sgtm.py        # listens on 127.0.0.1:9999
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def respond(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        if self.path.startswith("/healthz"):
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(204)
        self.send_header("Set-Cookie",
                         "FPID=abc123; Path=/; HttpOnly; SameSite=Lax; Max-Age=63072000")
        self.send_header("X-Mock-Saw-Xff", self.headers.get("X-Forwarded-For") or "-")
        self.send_header("X-Mock-Saw-Host", self.headers.get("X-Forwarded-Host") or "-")
        self.send_header("X-Mock-Saw-Preview", self.headers.get("X-Gtm-Server-Preview") or "-")
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 9999), Handler).serve_forever()
