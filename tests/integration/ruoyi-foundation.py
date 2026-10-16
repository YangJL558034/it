"""Isolated Java migration tests. Fixed loopback ports; never targets production.
Reads development secrets without printing them. Requires running start-ruoyi-dev.ps1.
"""
import json
import secrets
import socket
import time
import urllib.request
import urllib.error
from pathlib import Path

root = Path(__file__).resolve().parents[2]
config = json.loads((root / '.local/ruoyi/development.json').read_text(encoding='utf-8-sig'))
base = 'http://127.0.0.1:5081'
checks = []

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
    # Captcha obtained from *isolated local cache*, not disabled in application.
    with socket.create_connection(('127.0.0.1', 56379), timeout=5) as client:
        stream = client.makefile('rb')
        def command(*parts):
            raw = [str(p).encode() for p in parts]
            client.sendall(b'*%d\r\n' % len(raw) + b''.join(b'$%d\r\n' % len(p) + p + b'\r\n' for p in raw))
            line = stream.readline()
            if line.startswith(b'+'): return line[1:-2].decode()
            if line.startswith(b'$'):
                size = int(line[1:-2])
                if size < 0: return None
                value = stream.read(size); stream.read(2)
                return value.decode()
            raise RuntimeError('Unexpected cache response')
        command('AUTH', config['redisPassword'])
        return command('GET', key)

def login(username, password):
    captcha = api('/captchaImage')
    answer = json.loads(redis_value('captcha_codes:' + captcha['uuid']))
    return api('/login', 'POST', {'username': username, 'password': password, 'uuid': captcha['uuid'], 'code': str(answer)})

def check(name, condition):
    if not condition: raise AssertionError(name)
    checks.append(name)
    print('PASS ' + name)

check('Java liveness', api('/api/health/live')['backend'] == 'ruoyi-java')
check('anonymous system access denied', api('/system/user/list').get('code') == 401)
token = login('admin', config['adminPassword'])['token']
check('official RuoYi getInfo', api('/getInfo', token=token)['user']['userName'] == 'admin')
check('PostgreSQL/cache/file readiness', api('/api/health/ready', token=token).get('code') == 200)
check('official dynamic menu', api('/getRouters', token=token).get('code') == 200)
for path in ['/system/user/list', '/system/role/list', '/system/menu/list', '/system/dept/list',
             '/system/config/list', '/system/dict/type/list', '/system/dict/data/list',
             '/system/post/list', '/system/notice/list', '/monitor/operlog/list',
             '/monitor/logininfor/list', '/monitor/job/list', '/monitor/jobLog/list']:
    check('PostgreSQL ' + path, api(path, token=token).get('code') == 200)
for path in ['/system/user/list', '/system/role/list', '/system/dict/type/list']:
    check('date range ' + path, api(path + '?params%5BbeginTime%5D=2020-01-01&params%5BendTime%5D=2099-01-01', token=token).get('code') == 200)
check('department descendant filter', api('/system/user/list?deptId=100', token=token).get('code') == 200)
stamp = str(int(time.time()))
username = 'qa' + stamp
password = 'Qa9' + secrets.token_hex(12)
created = api('/system/user', 'POST', {'userName': username, 'nickName': 'migration QA', 'password': password, 'deptId': 100, 'status': '0', 'roleIds': [], 'postIds': []}, token)
check('official user create', created.get('code') == 200)
try:
    restricted = login(username, password)['token']
    check('server permission enforcement', api('/system/user/list', token=restricted).get('code') == 403)
    check('health details restricted', api('/api/health/ready', token=restricted).get('code') == 403)
    check('no unauthorized dynamic menus', api('/getRouters', token=restricted).get('data') == [])
    check('logout succeeds', api('/logout', 'POST', {}, restricted).get('code') == 200)
    check('logout token revoked', api('/getInfo', token=restricted).get('code') == 401)
finally:
    rows = api('/system/user/list?userName=' + username, token=token)['rows']
    for row in rows:
        if row['userName'] == username:
            api('/system/user/' + str(row['userId']), 'DELETE', token=token)
departments, users, role_id = [], [], None
try:
    def department(name, parent):
        result = api('/system/dept', 'POST', {'deptName': name, 'parentId': parent, 'orderNum': 1, 'status': '0'}, token)
        check('department create ' + name, result.get('code') == 200)
        row = next(x for x in api('/system/dept/list', token=token)['data'] if x['deptName'] == name)
        departments.append(row['deptId'])
        return row['deptId']
    dept_a = department('QA-A-' + stamp, 100)
    dept_child = department('QA-child-' + stamp, dept_a)
    dept_other = department('QA-other-' + stamp, 100)
    menus = api('/system/menu/list', token=token)['data']
    menu_ids = [m['menuId'] for m in menus if m.get('perms') == 'system:user:list']
    check('user-list permission configured in database', bool(menu_ids))
    role = {'roleName': 'QA-' + stamp, 'roleKey': 'qa_' + stamp, 'roleSort': 1, 'status': '0',
            'dataScope': '4', 'menuCheckStrictly': True, 'deptCheckStrictly': True, 'menuIds': menu_ids}
    check('role boolean fields / permission assignment', api('/system/role', 'POST', role, token).get('code') == 200)
    role_id = next(r['roleId'] for r in api('/system/role/list', token=token)['rows'] if r['roleKey'] == role['roleKey'])
    def user(name, department_id, roles):
        result = api('/system/user', 'POST', {'userName': name, 'nickName': name, 'password': password, 'deptId': department_id,
                                             'status': '0', 'roleIds': roles, 'postIds': []}, token)
        check('scoped user created ' + name, result.get('code') == 200)
        row = next(u for u in api('/system/user/list?userName=' + name, token=token)['rows'] if u['userName'] == name)
        users.append(row['userId'])
    user('scopeA' + stamp, dept_a, [role_id])
    user('scopeB' + stamp, dept_child, [])
    user('scopeC' + stamp, dept_other, [])
    scope_token = login('scopeA' + stamp, password)['token']
    visible = api('/system/user/list', token=scope_token)
    check('department data-scope query', visible.get('code') == 200)
    names = {u['userName'] for u in visible['rows']}
    check('own department and descendant visible', {'scopeA' + stamp, 'scopeB' + stamp} <= names)
    check('other departments and administrator excluded', 'scopeC' + stamp not in names and 'admin' not in names)
    check('explicit out-of-scope filter cannot bypass', api('/system/user/list?deptId=' + str(dept_other), token=scope_token).get('total') == 0)
    check('button write permission enforced', api('/system/dept', 'POST', {'deptName': 'denied', 'parentId': dept_a, 'orderNum': 1, 'status': '0'}, scope_token).get('code') == 403)
    check('data-scope update', api('/system/role/dataScope', 'PUT', {'roleId': role_id, 'dataScope': '3', 'deptCheckStrictly': True, 'deptIds': []}, token).get('code') == 200)
    after_scope_change = api('/system/user/list', token=scope_token)
    check('existing token uses new data scope', after_scope_change.get('code') == 200 and {u['userName'] for u in after_scope_change['rows']} == {'scopeA' + stamp})
    check('disable user', api('/system/user/changeStatus', 'PUT', {'userId': users[0], 'status': '1'}, token).get('code') == 200)
    check('disabled user existing token revoked', api('/getInfo', token=scope_token).get('code') == 401)
finally:
    for user_id in users:
        api('/system/user/' + str(user_id), 'DELETE', token=token)
    if role_id:
        api('/system/role/' + str(role_id), 'DELETE', token=token)
    for dept_id in reversed(departments):
        api('/system/dept/' + str(dept_id), 'DELETE', token=token)
result = {'backend': 'ruoyi-java', 'passed': len(checks), 'checks': checks,
          'scope': 'framework foundation only; not MES production-flow or installer acceptance'}
(root / '.local/migration/java-foundation-tests.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
