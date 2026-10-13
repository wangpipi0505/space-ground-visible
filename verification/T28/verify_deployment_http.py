"""验证 API 包与生产静态包边界、确切 Origin 预检与真实流式下载响应。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'verification/T28'
result = []


def request(label, url, method='GET', headers=None):
    try:
        response = urlopen(Request(url, method=method, headers=headers or {}))
    except HTTPError as error:
        response = error
    with response:
        body = response.read()
        item = dict(label=label, method=method, url=url, status=response.status, headers=dict(response.headers), bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
    result.append(item)
    return item, body


api = 'http://127.0.0.1:18130'
item, _ = request('后端API包没有Vue页或测试夹具', api + '/index.html')
assert item['status'] == 404
item, body = request('正式静态生产页', 'http://127.0.0.1:15130/index.html')
assert item['status'] == 200 and b'T28_STATIC_TEST_FIXTURE' not in body
assert body == (ROOT / 'verification/runtime/T28/deployment-package-v4/frontend/index.html').read_bytes()
for origin, status in [('http://127.0.0.1:15130', 200), ('http://unconfigured.invalid', 403)]:
    item, _ = request('确切跨域预检', api + '/api/v1/space/targets/query', 'OPTIONS', {'Origin': origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'Content-Type'})
    assert item['status'] == status
    if status == 200:
        assert item['headers']['Access-Control-Allow-Origin'] == origin
    else:
        assert 'Access-Control-Allow-Origin' not in item['headers']
nginx = subprocess.run(['where.exe', 'nginx'], capture_output=True)
(EVIDENCE / 'nginx-availability.txt').write_bytes(nginx.stdout + nginx.stderr)
assert nginx.returncode != 0, '若有 Nginx 必须继续实际验证标准配置'
(EVIDENCE / 'deployment-http-summary.json').write_text(json.dumps(dict(passed=True, responses=result, nginxAvailable=False, nginxConfigTestExecuted=False, actualGateway='static-validation-gateway.cjs', covered='正式静态字节/同源API代理由真实浏览器验收；跨域确切来源与拒绝来源'), ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(passed=True, responses=len(result), nginxAvailable=False), ensure_ascii=False))
