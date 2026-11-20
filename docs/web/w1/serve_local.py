"""Local harness server for the W1 wasm spike (docs/web/w1/README.md).

Serves this directory with the content types a static host must produce:

  .wasm  application/wasm   (WebAssembly.instantiateStreaming rejects anything else)
  .mjs   text/javascript    (module scripts are MIME-sniffed strictly)

Usage:
  python docs/web/w1/serve_local.py [port]        # default 8765
  python docs/web/w1/serve_local.py 8766 application/octet-stream   # negative control:
      shows the exact browser error a host without the .wasm mapping produces.
"""
import functools
import http.server
import os
import socketserver
import sys

root = os.path.dirname(os.path.abspath(__file__))
port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
wasm_mime = sys.argv[2] if len(sys.argv) > 2 else "application/wasm"


class W1Handler(http.server.SimpleHTTPRequestHandler):
    def guess_type(self, path):
        name = str(path)
        if name.endswith(".wasm"):
            return wasm_mime
        if name.endswith(".mjs"):
            return "text/javascript"
        return super().guess_type(path)


Handler = functools.partial(W1Handler, directory=root)

with socketserver.TCPServer(("127.0.0.1", port), Handler) as httpd:
    print("serving %s on http://127.0.0.1:%d/ (wasm as %s)" % (root, port, wasm_mime), flush=True)
    httpd.serve_forever()
