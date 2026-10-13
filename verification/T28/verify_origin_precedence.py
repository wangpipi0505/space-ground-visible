"""针对正式启动脚本的参数优先级定向验收；期望状态由本轮明确传入，原反例保留。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
label, expected = sys.argv[1], int(sys.argv[2])
origin = 'http://127.0.0.1:15132'
try:
    response = urlopen(Request('http://127.0.0.1:18132/api/v1/space/targets/query', method='OPTIONS', headers={'Origin':origin, 'Access-Control-Request-Method':'POST', 'Access-Control-Request-Headers':'Content-Type'}))
except HTTPError as error:
    response = error
with response:
    item = dict(label=label, origin=origin, status=response.status, headers=dict(response.headers), body=response.read().decode('utf-8'), expectedStatus=expected)
assert item['status'] == expected, item
if expected == 200:
    assert item['headers']['Access-Control-Allow-Origin'] == origin
else:
    assert 'Access-Control-Allow-Origin' not in item['headers']
(ROOT / 'verification/T28' / (label+'-origin.json')).write_text(json.dumps(item,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(item,ensure_ascii=False))
