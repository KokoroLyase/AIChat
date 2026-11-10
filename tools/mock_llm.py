"""仅本机模拟接口：不记录请求正文或 Authorization，可用 adb reverse 验证 debug 包。

0.5.0：/stats 里每次请求只记下几个是/否和条数（系统提示词里有没有全局记忆和回复要求的开头、
最后一条消息的角色、消息条数），用来核对请求内容，不记录任何文字。--reply 可以给多行回复（用 \\n 表示换行）。

0.6.0：--reply 可以重复给几次，聊天请求按顺序轮流返回（例如 --reply 在忙吗 --reply [SKIP] 交替返回正常回复和 [SKIP]）；
--fail-every N 让每第 N 个聊天请求返回 500，用来验证失败不重试。/stats 多记几项是/否：系统提示词里有没有约定时间的写法、
这次是不是主动请求（最后一条 user 消息里有主动聊天的临时指令）、这次有没有按 --fail-every 返回失败。
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18731)
parser.add_argument('--delay', type=float, default=1)
parser.add_argument('--reply', action='append', default=[])
parser.add_argument('--fail-every', type=int, default=0)
GLOBAL_HEADER = '下面是对方亲自写下的关于自己的信息'
STYLE_HEADER = '回复方式：'
REMINDER_HEADER = '约定时间：'
PROACTIVE_HEADER = '（这是 App 的提示，不是对方发来的消息'
args = parser.parse_args()
records = []
chat_count = 0
lock = threading.Lock()


def text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return ''.join(part.get('text', '') for part in content if isinstance(part, dict))
    return ''


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
        global chat_count
        if self.path != '/chat/completions':
            self.send_error(404)
            return
        data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        record = {key: data[key] for key in ('model', 'thinking', 'enable_thinking', 'reasoning_effort', 'reasoning', 'temperature', 'stream_options') if key in data}
        messages = data.get('messages', [])
        system = text_of(next((m.get('content', '') for m in messages if m.get('role') == 'system'), ''))
        last = text_of(messages[-1].get('content', '')) if messages else ''
        memory = data.get('model') == 'mock-memory'
        with lock:
            index = chat_count
            if not memory:
                chat_count += 1
            failed = not memory and args.fail_every > 0 and (index + 1) % args.fail_every == 0
            record.update({
                'messages': len(messages),
                'has_system': any(m.get('role') == 'system' for m in messages),
                'has_global_memory': GLOBAL_HEADER in system,
                'has_reply_style': STYLE_HEADER in system,
                'last_role': messages[-1].get('role') if messages else None,
                'has_reminder_hint': REMINDER_HEADER in system,
                'proactive': PROACTIVE_HEADER in last,
                'failed': failed,
            })
            records.append(record)
        time.sleep(args.delay)
        if failed:
            self.send_error(500)
            return
        replies = [reply.replace('\\n', '\n') for reply in args.reply] or ['模拟回复，参数校验已通过。']
        text = '这是一条模拟总结：对方喜欢咖啡，周末愿意散步。' if memory else replies[index % len(replies)]
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
