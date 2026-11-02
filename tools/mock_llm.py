"""仅本机模拟接口：不记录请求正文或 Authorization，可用 adb reverse 验证 debug 包。"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18731)
parser.add_argument('--delay', type=float, default=1)
args = parser.parse_args()
records = []
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == '/models':
            data = {'data': [{'id': 'mock-chat', 'type': 'chat', 'input_modalities': ['text'],
                              'effort': {'supported_levels': ['low', 'high', 'max']}},
                             {'id': 'candidate-audio', 'type': 'tts'}]}
        elif self.path == '/stats':
            with lock:
                data = list(records)
        else:
            self.send_error(404)
            return
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        if self.path != '/chat/completions':
            self.send_error(404)
            return
        data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        record = {key: data[key] for key in ('model', 'thinking', 'enable_thinking', 'reasoning_effort', 'reasoning', 'temperature', 'stream_options') if key in data}
        with lock:
            records.append(record)
        time.sleep(args.delay)
        text = '这是一条模拟总结：对方喜欢咖啡，周末愿意散步。' if data.get('model') == 'mock-memory' else '模拟回复，参数校验已通过。'
        chunks = [
            {'choices': [{'delta': {'reasoning_content': '模拟思考，不应写入记忆正文。'}}]},
            {'choices': [{'delta': {'content': text}, 'finish_reason': 'stop'}]},
            {'choices': [], 'usage': {'prompt_tokens': 70, 'completion_tokens': 20}},
        ]
        raw = ''.join('data: ' + json.dumps(chunk, ensure_ascii=False) + '\n\n' for chunk in chunks) + 'data: [DONE]\n\n'
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(raw.encode())))
        self.end_headers()
        self.wfile.write(raw.encode())


print(f'Local mock listening on 127.0.0.1:{args.port}', flush=True)
ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
