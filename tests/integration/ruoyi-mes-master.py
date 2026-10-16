"""Real PostgreSQL/Java HTTP checks for MES master data, concurrency and RuoYi authorization."""
import json
import secrets
import time
from concurrent.futures import ThreadPoolExecutor
from ruoyi_helpers import api, login, root

token = login()
stamp = str(time.time_ns())[-12:]
checks = []
created = {kind: [] for kind in ('lines', 'products', 'stations', 'orders', 'batches')}
departments = []
role_id = user_id = None

def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
    print('PASS ' + name)

def create(kind, body, record=True):
    result = api('/mes/master/' + kind, 'POST', body, token)
    if result.get('code') != 200:
        raise AssertionError(str(result))
    row = result['data']
    if record:
        created[kind].append(row['id'])
    return row

def body(row, **updates):
    fields = {'lines': ['code', 'name', 'workshop', 'deptId'], 'products': ['code', 'name', 'deptId'],
              'stations': ['code', 'name', 'lineId', 'processStepId'],
              'orders': ['code', 'lineId', 'productId', 'plan', 'status'], 'batches': ['code', 'workOrderId', 'plan']}
    kind = next(k for k, ids in created.items() if row['id'] in ids)
    return {**{k: row[k] for k in fields[kind]}, 'version': row['version'], **updates}

try:
    check('anonymous MES module access denied', api('/mes/modules').get('code') == 401)
    modules = api('/mes/modules', token=token)
    check('complete MES module inventory visible', len(modules['data']) == 17)
    check('unmigrated modules clearly marked', any(m['key'] == 'sop' and m['status'] == 'migrating' for m in modules['data']))
    snapshot = json.loads((root / '.local/migration/masterdata/legacy-masterdata-snapshot.json').read_text(encoding='utf-8'))
    fields = {'lines': {'Id': 'id', 'Code': 'code', 'Name': 'name', 'Workshop': 'workshop'},
              'products': {'Id': 'id', 'Code': 'code', 'Name': 'name'},
              'stations': {'Id': 'id', 'Code': 'code', 'Name': 'name', 'LineId': 'lineId', 'ProcessStepId': 'processStepId'},
              'orders': {'Id': 'id', 'Code': 'code', 'LineId': 'lineId', 'ProductId': 'productId', 'Plan': 'plan', 'Status': 'status'},
              'batches': {'Id': 'id', 'Code': 'code', 'WorkOrderId': 'workOrderId', 'Plan': 'plan'}}
    for kind, mapping in fields.items():
        current, page = {}, 1
        while True:
            result = api('/mes/master/' + kind + '?pageSize=200&pageNum=' + str(page), token=token)
            current.update({r['id']: r for r in result['rows']})
            if len(current) >= result['total']:
                break
            page += 1
        check('snapshot counts match Java ' + kind, len(current) == len(snapshot[kind]))
        check('preserve source UUIDs and relationships for ' + kind, all(all(current[r['Id']][new] == r[old] for old, new in mapping.items()) for r in snapshot[kind]))
    routers = json.dumps(api('/getRouters', token=token), ensure_ascii=False)
    check('RuoYi dynamic menus include MES business', all(name in routers for name in ['生产运营', '生产工单', 'SOP 作业指导书', '备份与恢复']))
    processes = api('/mes/master/processes/list', token=token)['data']
    check('five actual legacy process IDs retained', [p['sequence'] for p in processes] == [1, 2, 3, 4, 5])
    for suffix in ('A', 'B'):
        name = 'MES-QA-' + suffix + stamp
        check('create scope department ' + suffix, api('/system/dept', 'POST', {'deptName': name, 'parentId': 100, 'orderNum': 0, 'status': '0'}, token)['code'] == 200)
        departments.append(next(d['deptId'] for d in api('/system/dept/list', token=token)['data'] if d['deptName'] == name))
    child_name = 'MES-QA-child-' + stamp
    check('create descendant department', api('/system/dept', 'POST', {'deptName': child_name, 'parentId': departments[0], 'orderNum': 0, 'status': '0'}, token)['code'] == 200)
    departments.append(next(d['deptId'] for d in api('/system/dept/list', token=token)['data'] if d['deptName'] == child_name))
    line = create('lines', {'code': 'L' + stamp, 'name': 'Java 产线测试', 'workshop': '验证车间', 'deptId': departments[0]})
    other = create('lines', {'code': 'LB' + stamp, 'name': '另一数据范围', 'workshop': '验证车间', 'deptId': departments[1]})
    child = create('lines', {'code': 'LC' + stamp, 'name': '下级部门产线', 'workshop': '验证车间', 'deptId': departments[2]})
    product = create('products', {'code': 'P' + stamp, 'name': 'Java 产品测试', 'deptId': departments[0]})
    check('duplicate master code rejected', api('/mes/master/lines', 'POST', {'code': line['code'], 'name': '重复', 'workshop': '验证', 'deptId': departments[0]}, token)['code'] == 409)
    update = api('/mes/master/lines/' + line['id'], 'PUT', body(line, name='更新后的产线'), token)
    check('optimistic update increments version', update['code'] == 200 and update['data']['version'] == 2)
    check('stale update rejected', api('/mes/master/lines/' + line['id'], 'PUT', body(line, name='过期修改'), token)['code'] == 409)
    line = update['data']
    station = create('stations', {'code': 'S' + stamp, 'name': '主码工位', 'lineId': line['id'], 'processStepId': processes[0]['id']})
    check('station department inherited from line', station['deptId'] == departments[0])
    order = create('orders', {'code': 'MO' + stamp, 'lineId': line['id'], 'productId': product['id'], 'plan': 60, 'status': 'Open'})
    check('cross-department product/line rejected', api('/mes/master/orders', 'POST', {'code': 'BAD' + stamp, 'lineId': other['id'], 'productId': product['id'], 'plan': 10, 'status': 'Open'}, token)['code'] == 422)
    def allocate(suffix):
        return api('/mes/master/batches', 'POST', {'code': 'B' + suffix + stamp, 'workOrderId': order['id'], 'plan': 50}, token)
    with ThreadPoolExecutor(max_workers=2) as pool:
        attempts = list(pool.map(allocate, ['1', '2']))
    for result in attempts:
        if result.get('code') == 200:
            created['batches'].append(result['data']['id'])
    check('concurrent batch allocation cannot exceed order plan', sorted(r['code'] for r in attempts) == [200, 422])
    check('referenced order deletion denied', api('/mes/master/orders/' + order['id'] + '?version=1', 'DELETE', token=token)['code'] == 409)
    check('referenced product deletion denied', api('/mes/master/products/' + product['id'] + '?version=1', 'DELETE', token=token)['code'] == 409)
    check('referenced order critical key change denied', api('/mes/master/orders/' + order['id'], 'PUT', body(order, code='NEW' + stamp), token)['code'] == 409)
    check('order plan cannot fall below allocated batch plan', api('/mes/master/orders/' + order['id'], 'PUT', body(order, plan=40), token)['code'] == 422)
    menus = api('/system/menu/list', token=token)['data']
    permissions = ['mes:lines:list', 'mes:lines:add', 'mes:lines:edit', 'mes:lines:remove', 'mes:orders:list']
    menu_ids = [m['menuId'] for m in menus if m.get('perms') in permissions]
    role_key = 'mesqa' + stamp
    role = {'roleName': role_key, 'roleKey': role_key, 'roleSort': 1, 'status': '0', 'dataScope': '3', 'menuCheckStrictly': True, 'deptCheckStrictly': True, 'menuIds': menu_ids}
    check('assign MES button permissions through RuoYi role', api('/system/role', 'POST', role, token)['code'] == 200)
    role_id = next(r['roleId'] for r in api('/system/role/list', token=token)['rows'] if r['roleKey'] == role_key)
    username, password = 'mesqa' + stamp, 'Mes9-' + secrets.token_hex(10)
    check('create RuoYi MES user', api('/system/user', 'POST', {'userName': username, 'nickName': username, 'password': password, 'deptId': departments[0], 'status': '0', 'roleIds': [role_id], 'postIds': []}, token)['code'] == 200)
    user_id = next(u['userId'] for u in api('/system/user/list?userName=' + username, token=token)['rows'] if u['userName'] == username)
    scoped = login(username, password)
    rows = api('/mes/master/lines?pageSize=200', token=scoped)['rows']
    check('MES list obeys RuoYi department data scope', line['id'] in {r['id'] for r in rows} and other['id'] not in {r['id'] for r in rows})
    check('out-of-scope delete by known UUID denied', api('/mes/master/lines/' + other['id'] + '?version=1', 'DELETE', token=scoped)['code'] == 403)
    check('cannot create in another department', api('/mes/master/lines', 'POST', {'code': 'HACK' + stamp, 'name': '越权', 'workshop': '验证', 'deptId': departments[1]}, scoped)['code'] == 403)
    check('cannot move resource outside allowed scope', api('/mes/master/lines/' + line['id'], 'PUT', body(line, deptId=departments[1]), scoped)['code'] in (403, 409))
    check('missing write button permission denied on server', api('/mes/master/orders', 'POST', {'code': 'HACK' + stamp}, scoped)['code'] == 403)
    visible = api('/mes/modules', token=scoped)['data']
    check('module inventory respects assigned permissions', {m['key'] for m in visible} == {'lines', 'orders'})
    check('relationship choices exclude other department', other['id'] not in {r['id'] for r in api('/mes/master/orders/options', token=scoped)['data']['lines']})
    check('line lookup cannot disclose other business modules', set(api('/mes/master/lines/options', token=scoped)['data']) == {'departments'})
    check('order lookup returns only minimum related identifiers', all(set(r) <= {'id', 'code', 'name'} for r in api('/mes/master/orders/options', token=scoped)['data']['products']))
    for data_scope, custom, expected in [('4', [], {line['id'], child['id']}), ('2', [departments[1]], {other['id']}), ('1', [], {line['id'], other['id'], child['id']})]:
        check('change MES data scope to ' + data_scope, api('/system/role/dataScope', 'PUT', {'roleId': role_id, 'dataScope': data_scope, 'deptCheckStrictly': True, 'deptIds': custom}, token)['code'] == 200)
        visible_ids = {r['id'] for r in api('/mes/master/lines?pageSize=200', token=scoped)['rows']}
        check('existing MES token enforces data scope ' + data_scope, visible_ids.intersection({line['id'], other['id'], child['id']}) == expected)
    for guard in snapshot['guards']:
        kind = guard['resource']
        check('legacy history prevents deleting migrated ' + kind, api('/mes/master/' + kind + '/' + guard['id'] + '?version=1', 'DELETE', token=token)['code'] == 409)
        if kind == 'batches':
            break
finally:
    for kind in ('batches', 'orders', 'stations', 'products', 'lines'):
        data = api('/mes/master/' + kind + '?keyword=' + stamp + '&pageSize=200', token=token)
        for row in data.get('rows', []):
            if row['id'] in created[kind]:
                result = api('/mes/master/' + kind + '/' + row['id'] + '?version=' + str(row['version']), 'DELETE', token=token)
                if result.get('code') != 200:
                    raise AssertionError('Fixture cleanup failed: ' + str(result))
    if user_id:
        api('/system/user/' + str(user_id), 'DELETE', token=token)
    if role_id:
        api('/system/role/' + str(role_id), 'DELETE', token=token)
    for department in reversed(departments):
        api('/system/dept/' + str(department), 'DELETE', token=token)
    api('/logout', 'POST', {}, token)

(root / '.local/migration/java-mes-master-tests.json').write_text(json.dumps({'passed': len(checks), 'checks': checks}, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASSED', len(checks))
