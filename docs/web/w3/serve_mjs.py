"""W3 record 19 harness: serve fireline-web with the module MIME types Chrome demands.

Python's http.server maps an unknown `.mjs` to text/plain, and Chrome refuses a module script
with a non-JS MIME ("Failed to fetch dynamically imported module"), which makes the console shell
look empty. Serve .mjs as text/javascript, .html as text/html, .wasm as application/wasm.

Usage: python serve_mjs.py <port> [root]
"""
import functools
import http.server
import socketserver
import sys

EXTENSIONS = {
    ".html": "text/html",
    ".mjs": "text/javascript",
    ".js": "text/javascript",
    ".wasm": "application/wasm",
    ".map": "application/json",
    ".css": "text/css",
    ".json": "application/json",
}


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**EXTENSIONS, "": "application/octet-stream"}

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def main() -> None:
    port = int(sys.argv[1])
    root = sys.argv[2] if len(sys.argv) > 2 else "."
    handler = functools.partial(Handler, directory=root)
    with socketserver.TCPServer(("127.0.0.1", port), handler) as httpd:
        print("serving %s on http://127.0.0.1:%d/" % (root, port), flush=True)
        httpd.serve_forever()


if __name__ == "__main__":
    main()
