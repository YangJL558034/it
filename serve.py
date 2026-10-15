#!/usr/bin/env python3
"""Serve the static MES UI locally, using only the Python standard library."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from functools import partial
from pathlib import Path
import argparse

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='MES UI local preview')
    parser.add_argument('--port', type=int, default=8000)
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent / 'dist'
    handler = partial(SimpleHTTPRequestHandler, directory=str(directory))
    try:
        with ThreadingHTTPServer(('127.0.0.1', args.port), handler) as server:
            print(f'MES UI: http://localhost:{args.port}  (Ctrl+C to stop)', flush=True)
            server.serve_forever()
    except KeyboardInterrupt:
        pass
