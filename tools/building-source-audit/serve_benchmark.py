"""Loopback-only device benchmark server; accepts only task-specific result files."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import json
import time

ROOT=Path(__file__).resolve().parents[2]
DIR=ROOT/'docs/terrain-ahead/building-source-audit/2026-09-27-siedlce/phone-benchmark'
class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(DIR),**kwargs)
    def log_message(self,*args):pass
    def do_GET(self):
        if self.path.split('?')[0] not in ('/','/index.html','/benchmark.js','/scene.json','/base.bin','/buildings.bin'):
            self.send_error(404);return
        super().do_GET()
    def do_POST(self):
        if self.path not in ('/trial','/environment','/event','/complete','/error'):self.send_error(404);return
        size=int(self.headers.get('Content-Length','0'))
        if not 0<size<1000000:self.send_error(413);return
        data=json.loads(self.rfile.read(size));name=self.path[1:]
        if name=='trial':name=f"trial-{int(data['index']):02d}"
        if name=='event':name=f"event-{int(data['index']):02d}"
        (DIR/(name+'.json')).write_text(json.dumps(data,indent=2),encoding='utf-8')
        print(name,flush=True)
        self.send_response(200);self.end_headers();self.wfile.write(b'ok')
print('READY 127.0.0.1:18764',flush=True)
ThreadingHTTPServer(('127.0.0.1',18764),Handler).serve_forever()
