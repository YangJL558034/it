"""Only the fixed, isolated RuoYi development environment. Never production."""
import json
import os
import socket
import urllib.request
import urllib.error
from pathlib import Path

root = Path(__file__).resolve().parents[2]
operations = os.environ.get('MES_TEST_PROFILE') == 'operations'
config = json.loads((root / ('.local/ruoyi-ops-test/development.json' if operations else '.local/ruoyi/development.json')).read_text(encoding='utf-8-sig'))
base = 'http://127.0.0.1:5082' if operations else 'http://127.0.0.1:5081'

def api(path, method='GET', data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, None if data is None else json.dumps(data).encode(), headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    return json.loads(response.read())

def redis_value(key):
    with socket.create_connection(('127.0.0.1', 56380 if operations else 56379), timeout=5) as client:
        stream = client.makefile('rb')
        def command(*parts):
            raw = [str(p).encode() for p in parts]
            client.sendall(b'*%d\r\n' % len(raw) + b''.join(b'$%d\r\n' % len(p) + p + b'\r\n' for p in raw))
            line = stream.readline()
            if line.startswith(b'+'):
                return line[1:-2].decode()
            if line.startswith(b'$'):
                size = int(line[1:-2])
                if size < 0:
                    return None
                value = stream.read(size)
                stream.read(2)
                return value.decode()
            raise RuntimeError('Unexpected cache response')
        command('AUTH', config['redisPassword'])
        return command('GET', key)

def login(username='admin', password=None):
    captcha = api('/captchaImage')
    # Captcha stays enabled. Only test fixtures read the isolated development cache.
    answer = json.loads(redis_value('captcha_codes:' + captcha['uuid']))
    result = api('/login', 'POST', {'username': username, 'password': password or config['adminPassword'],
                                  'uuid': captcha['uuid'], 'code': str(answer)})
    if result.get('code') != 200:
        raise AssertionError('Development login failed: ' + str(result.get('msg')))
    return result['token']
