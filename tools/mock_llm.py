"""仅本机模拟接口：不记录请求正文或 Authorization，可用 adb reverse 验证 debug 包。

0.5.0：/stats 里每次请求只记下几个是/否和条数（系统提示词里有没有全局记忆和回复要求的开头、
最后一条消息的角色、消息条数），用来核对请求内容，不记录任何文字。--reply 可以给多行回复（用 \\n 表示换行）。

0.6.0：--reply 可以重复给几次，聊天请求按顺序轮流返回（例如 --reply 在忙吗 --reply [SKIP] 交替返回正常回复和 [SKIP]）；
--fail-every N 让每第 N 个聊天请求返回 500，用来验证失败不重试。/stats 多记几项是/否：系统提示词里有没有约定时间的写法、
这次是不是主动请求（最后一条 user 消息里有主动聊天的临时指令）、这次有没有按 --fail-every 返回失败。

0.7.0：两种格式的语音合成和识别。
- 聊天接口带音频（按 MiMo 文档）：POST /chat/completions，消息里有 input_audio 的是识别，带 audio 字段或模型名含 tts 的是合成；
- OpenAI 标准音频接口：POST /audio/speech（合成，返回音频二进制）、POST /audio/transcriptions（识别，multipart，返回 text）。
合成返回一段 WAV 正弦音（长度随文字变，最长 8 秒），识别返回 --asr-text。
--tts-mode / --asr-mode 可选 ok、fail（500）、empty（200 但没有音频或文字）、timeout（--timeout 秒后才返回）、
fail-once（第一次 500，之后正常）。/stats 里语音请求只记是/否和数字：kind、api、有没有音色、有没有语气要求、文字字数、音频字节数，
不记文字和音频内容。
"""
import argparse
import base64
import io
import json
import math
import struct
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18731)
parser.add_argument('--delay', type=float, default=1)
parser.add_argument('--reply', action='append', default=[])
parser.add_argument('--fail-every', type=int, default=0)
parser.add_argument('--tts-mode', default='ok', choices=['ok', 'fail', 'empty', 'timeout', 'fail-once'])
parser.add_argument('--asr-mode', default='ok', choices=['ok', 'fail', 'empty', 'timeout', 'fail-once'])
parser.add_argument('--asr-text', default='这是模拟识别出的文字')
parser.add_argument('--timeout', type=float, default=150)
GLOBAL_HEADER = '下面是对方亲自写下的关于自己的信息'
STYLE_HEADER = '回复方式：'
REMINDER_HEADER = '约定时间：'
PROACTIVE_HEADER = '（这是 App 的提示，不是对方发来的消息'
args = parser.parse_args()
records = []
chat_count = 0
voice_counts = {'tts': 0, 'asr': 0}
lock = threading.Lock()


def tone_wav(chars):
    """一段 440 Hz 正弦音的 WAV（16 kHz 单声道 16 位），每个字 0.25 秒，0.6～8 秒。"""
    seconds = min(8.0, max(0.6, chars * 0.25))
    frames = int(16000 * seconds)
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b''.join(struct.pack('<h', int(3000 * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(frames)))
    return buf.getvalue()


def voice_outcome(kind):
    """按 --tts-mode / --asr-mode 决定这次怎么回：ok、fail、empty、timeout"""
    mode = args.tts_mode if kind == 'tts' else args.asr_mode
    with lock:
        voice_counts[kind] += 1
        count = voice_counts[kind]
    if mode == 'fail-once':
        return 'fail' if count == 1 else 'ok'
    return mode


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

    def send_body(self, raw, content_type):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def voice(self, kind, api, record_extra, ok_body):
        """记一条语音请求的统计（不记内容），按模式返回"""
        outcome = voice_outcome(kind)
        record = {'kind': kind, 'api': api, 'outcome': outcome}
        record.update(record_extra)
        with lock:
            records.append(record)
        time.sleep(args.timeout if outcome == 'timeout' else args.delay)
        if outcome == 'fail':
            self.send_error(500)
            return
        raw, content_type = ok_body(outcome == 'empty')
        self.send_body(raw, content_type)

    def do_POST(self):
        global chat_count
        length = int(self.headers.get('Content-Length', '0'))
        if self.path == '/audio/speech':
            data = json.loads(self.rfile.read(length))
            text = data.get('input', '')
            self.voice('tts', 'openai', {'model': data.get('model'), 'voice_set': bool(data.get('voice')),
                                         'format': data.get('response_format'), 'chars': len(text)},
                       lambda empty: (b'' if empty else tone_wav(len(text)), 'audio/wav'))
            return
        if self.path == '/audio/transcriptions':
            body = self.rfile.read(length)
            self.voice('asr', 'openai', {'multipart': b'name="file"' in body, 'has_model': b'name="model"' in body, 'audio_bytes': len(body)},
                       lambda empty: (json.dumps({'text': '' if empty else args.asr_text}, ensure_ascii=False).encode(), 'application/json'))
            return
        if self.path != '/chat/completions':
            self.send_error(404)
            return
        data = json.loads(self.rfile.read(length))
        messages_in = data.get('messages', [])
        audio_parts = [part for m in messages_in if isinstance(m.get('content'), list) for part in m['content']
                       if isinstance(part, dict) and part.get('type') == 'input_audio']
        if audio_parts:
            audio = audio_parts[0].get('input_audio', {})
            self.voice('asr', 'chat', {'model': data.get('model'), 'parts': len(audio_parts),
                                       'data_url': str(audio.get('data', '')).startswith('data:audio/wav;base64,'),
                                       'format': audio.get('format'), 'language': data.get('asr_options', {}).get('language'),
                                       'audio_bytes': len(str(audio.get('data', '')))},
                       lambda empty: (json.dumps({'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': '' if empty else args.asr_text},
                                                               'finish_reason': 'stop'}],
                                                  'usage': {'prompt_tokens': 10, 'completion_tokens': 5, 'seconds': 3}}, ensure_ascii=False).encode(),
                                      'application/json'))
            return
        if 'audio' in data or 'tts' in str(data.get('model', '')):
            spoken = next((m.get('content', '') for m in messages_in if m.get('role') == 'assistant'), '')
            audio_opts = data.get('audio', {})
            self.voice('tts', 'chat', {'model': data.get('model'), 'voice_set': bool(audio_opts.get('voice')),
                                       'style_set': any(m.get('role') == 'user' for m in messages_in), 'format': audio_opts.get('format'),
                                       'last_role': messages_in[-1].get('role') if messages_in else None, 'chars': len(spoken)},
                       lambda empty: (json.dumps({'choices': [{'index': 0, 'message': {
                           'role': 'assistant', 'content': '',
                           'audio': {'id': 'mock', 'data': '' if empty else base64.b64encode(tone_wav(len(spoken))).decode(),
                                     'expires_at': None, 'transcript': None}}, 'finish_reason': 'stop'}]}).encode(), 'application/json'))
            return
        record = {key: data[key] for key in ('model', 'thinking', 'enable_thinking', 'reasoning_effort', 'reasoning', 'temperature', 'stream_options') if key in data}
        messages = messages_in
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
