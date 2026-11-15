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

0.9.2：
- DeepSeek 语音：POST …/tools/tts（JSON，返回 WAV）、POST …/tools/asr（multipart 只有 file，返回 text 和 duration_ms），
  和上面一样按 --tts-mode / --asr-mode 返回；
- MinerU 精准解析：POST …/file-urls/batch 给出指回本机的上传地址，PUT /upload/<id> 收文件（只记字节数），
  GET …/extract-results/batch/<id> 查 --parse-polls 次后 done，结果 zip 在 /result/<id>.zip，里面的 full.md 是 --parse-text；
  不认识的任务返回 -60012（“测试连接”用）。--parse-mode 可选 ok、fail（state failed）、auth（A0202）、timeout（一直 running）；
- 聊天请求的 /stats 多记三项是/否：最后一条消息开头有没有时间附注、有没有文件文字（“[文件：”）、有没有图片识别文字（“[图片：”）；
  另记时间附注本身（只有 App 生成的日期、时间和间隔，到第一个“）”为止，不含聊天内容）、系统提示词里有没有“某年某月某日”的日期，
  以及整个请求里有几处“[文件：”和几段 --parse-text 的第一行之后的文字（带全文的文件数，文件在更早的轮次里时也算）。
"""
import argparse
import base64
import io
import json
import math
import re
import struct
import threading
import time
import wave
import zipfile
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
parser.add_argument('--parse-mode', default='ok', choices=['ok', 'fail', 'auth', 'timeout'])
parser.add_argument('--parse-polls', type=int, default=2)
parser.add_argument('--parse-text', default='# 模拟解析\n\n这是 MinerU 模拟解析出的文字。\n\n![](images/a.jpg)')
GLOBAL_HEADER = '下面是对方亲自写下的关于自己的信息'
STYLE_HEADER = '回复方式：'
REMINDER_HEADER = '约定时间：'
PROACTIVE_HEADER = '（这是 App 的提示，不是对方发来的消息'
META_HEADER = '（App 附上的时间信息'
args = parser.parse_args()
_parse_lines = [line for line in args.parse_text.replace('\\n', '\n').split('\n') if line.strip()]
PARSE_MARK = _parse_lines[1] if len(_parse_lines) > 1 else ''
records = []
chat_count = 0
voice_counts = {'tts': 0, 'asr': 0}
batches = {}
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

    def parse_json(self, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_body(raw, 'application/json')

    def do_PUT(self):
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length)
        batch = self.path.split('?')[0].rsplit('/', 1)[-1]
        if not self.path.startswith('/upload/') or batch not in batches:
            self.send_error(404)
            return
        with lock:
            batches[batch]['uploaded'] = len(body)
            records.append({'kind': 'parse', 'api': 'upload', 'bytes': len(body),
                            'has_auth': 'Authorization' in self.headers, 'has_content_type': 'Content-Type' in self.headers})
        self.send_body(b'', 'text/plain')

    def do_GET(self):
        if '/extract-results/batch/' in self.path:
            batch = self.path.rsplit('/', 1)[-1]
            info = batches.get(batch)
            if info is None:
                self.parse_json({'code': -60012, 'msg': 'task not found'})
                return
            with lock:
                info['polls'] += 1
                polls = info['polls']
                records.append({'kind': 'parse', 'api': 'poll', 'poll': polls})
            if args.parse_mode == 'fail':
                state = {'state': 'failed', 'err_msg': 'mock failure'}
            elif args.parse_mode == 'timeout' or polls < args.parse_polls or not info.get('uploaded'):
                state = {'state': 'running', 'extract_progress': {'extracted_pages': 1, 'total_pages': 3}}
            else:
                port = self.server.server_address[1]
                state = {'state': 'done', 'full_zip_url': f'http://127.0.0.1:{port}/result/{batch}.zip'}
            state['file_name'] = info['name_ext']
            self.parse_json({'code': 0, 'msg': 'ok', 'data': {'batch_id': batch, 'extract_result': [state]}})
            return
        if self.path.startswith('/result/') and self.path.endswith('.zip'):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as z:
                z.writestr('full.md', args.parse_text.replace('\\n', '\n'))
                z.writestr('layout.json', '{}')
            with lock:
                records.append({'kind': 'parse', 'api': 'zip'})
            self.send_body(buf.getvalue(), 'application/zip')
            return
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
        if self.path.endswith('/file-urls/batch'):
            data = json.loads(self.rfile.read(length))
            if args.parse_mode == 'auth':
                self.parse_json({'code': 'A0202', 'msg': 'token error'})
                return
            files = data.get('files', [])
            batch = 'mock-%d' % (len(batches) + 1)
            name = files[0].get('name', '') if files else ''
            with lock:
                batches[batch] = {'polls': 0, 'name_ext': name.rsplit('.', 1)[-1].lower() if '.' in name else ''}
                records.append({'kind': 'parse', 'api': 'apply', 'files': len(files), 'ext': batches[batch]['name_ext'],
                                'model_version': data.get('model_version'), 'has_auth': self.headers.get('Authorization', '').startswith('Bearer ')})
            port = self.server.server_address[1]
            self.parse_json({'code': 0, 'msg': 'ok', 'data': {'batch_id': batch, 'file_urls': [f'http://127.0.0.1:{port}/upload/{batch}?sig=mock']}})
            return
        if self.path.endswith('/tools/tts'):
            data = json.loads(self.rfile.read(length))
            text = data.get('text', '')
            self.voice('tts', 'deepseek', {'voice': data.get('voice_id'), 'format': data.get('format'),
                                           'filter_markdown': data.get('filter_markdown'), 'has_model': 'model' in data, 'chars': len(text)},
                       lambda empty: (b'' if empty else tone_wav(len(text)), 'audio/wav'))
            return
        if self.path.endswith('/tools/asr'):
            body = self.rfile.read(length)
            self.voice('asr', 'deepseek', {'multipart': b'name="file"' in body, 'has_model': b'name="model"' in body, 'audio_bytes': len(body)},
                       lambda empty: (json.dumps({'request_id': 'mock', 'text': '' if empty else args.asr_text, 'duration_ms': 3000},
                                                 ensure_ascii=False).encode(), 'application/json'))
            return
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
                'has_meta_note': last.startswith(META_HEADER),
                'meta_note': last[:last.index('）') + 1] if last.startswith(META_HEADER) and '）' in last else None,
                'system_has_date': bool(re.search(r'\d{4}年\d{1,2}月\d{1,2}日', system)),
                'has_file_text': '[文件：' in last,
                'file_refs': sum(text_of(m.get('content', '')).count('[文件：') for m in messages),
                'file_full': sum(text_of(m.get('content', '')).count(PARSE_MARK) for m in messages) if PARSE_MARK else 0,
                'has_image_text': '[图片：' in last,
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
