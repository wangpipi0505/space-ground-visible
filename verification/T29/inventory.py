"""实际源码路由/DTO声明和中文注释审核文件清单，不把静态统计当充分性证明。

继承窗口健康路由按正式类显式补入；拟合健康方法在正式类声明。旧兼容Controller未扫描，不能把重复注解
都算活动端点。详细责任、边界与人工复核结论写在验证报告。
"""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/T29'
BASE = ROOT / 'backend/src/main/java'


def location(path, text, index):
    return dict(source=path.relative_to(ROOT).as_posix(), line=text[:index].count('\n') + 1)


def main():
    routes, fields, audit = [], [], []
    for path in sorted(BASE.rglob('*.java')):
        text = path.read_text(encoding='utf-8')
        if path.as_posix().startswith((BASE / 'com/runisys/orbit/spacevisibility/api').as_posix()) and re.search(r'@RestController\s*\n', text):
            prefix = re.search(r'@RequestMapping\("([^"]+)"\)', text).group(1)
            for match in re.finditer(r'@(Get|Post|Put|Delete)Mapping(?:\((.*?)\))?', text, re.S):
                suffix = re.search(r'"([^"]+)"', match.group(2) or '')
                signature = re.search(r'public\s+([^\{]+)\{', text[match.end():], re.S)
                routes.append(dict(method=match.group(1).upper(), path=prefix + (suffix.group(1) if suffix else ''),
                                   declaration=' '.join(signature.group(1).split()), **location(path, text, match.start())))
            if path.stem == 'BallisticVisibilityController':
                legacy = BASE / 'com/tx/alg/api/OrbitVisibilityController.java'
                old = legacy.read_text(encoding='utf-8')
                match = re.search(r'@GetMapping\("/health"\)', old)
                routes.append(dict(method='GET', path=prefix + '/health', declaration='继承原health方法',
                                   inheritedBy=path.relative_to(ROOT).as_posix(), **location(legacy, old, match.start())))
        if path.name in ('SpaceApiModels.java', 'VisibilityRequest.java', 'TargetTrajectoryFitter.java', 'BallisticFileModels.java'):
            owner = path.stem
            for line_no, line in enumerate(text.splitlines(), 1):
                definition = re.search(r'public\s+(?:static\s+)?(?:final\s+)?class\s+(\w+)', line)
                if definition:
                    owner = definition.group(1)
                if re.match(r'^\s+(?:private|public)\s+(?:(?:static|final)\s+)*[\w.<>,?\[\] ]+\s+\w+\s*(?:=.*)?;', line) and '(' not in line.split('=', 1)[0]:
                    fields.append(dict(owner=owner, source=path.relative_to(ROOT).as_posix(), line=line_no, declaration=line.strip()))
        comments = re.findall(r'/\*[\s\S]*?\*/|//[^\n]*', text)
        audit.append(dict(path=path.relative_to(ROOT).as_posix(), kind='Java主源码',
                          sha256=hashlib.sha256(path.read_bytes()).hexdigest(), chineseCommentBlocks=sum(bool(re.search(r'[\u4e00-\u9fff]', block)) for block in comments)))
    for path in sorted((ROOT / 'frontend/src').rglob('*')):
        if path.suffix not in ('.vue', '.ts', '.css'):
            continue
        text = path.read_text(encoding='utf-8')
        comments = re.findall(r'/\*[\s\S]*?\*/|//[^\n]*|<!--[\s\S]*?-->', text)
        audit.append(dict(path=path.relative_to(ROOT).as_posix(), kind='前端测试' if '.spec.' in path.name else '前端源码',
                          sha256=hashlib.sha256(path.read_bytes()).hexdigest(), chineseCommentBlocks=sum(bool(re.search(r'[\u4e00-\u9fff]', block)) for block in comments)))
    routes.sort(key=lambda item: (item['path'], item['method']))
    assert len(routes) == 35 and len({(r['method'], r['path']) for r in routes}) == 35, [(r['method'], r['path']) for r in routes]
    inventory = dict(routeCount=len(routes), routes=routes, dtoDeclarations=fields,
                     scan='扫描范围内正式RestController及窗口继承health；拟合health由正式类声明，旧兼容类不是第二套活动端点，字段声明含原默认表达式')
    (OUT / 'api-inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (OUT / 'comment-audit-files.json').write_text(json.dumps(dict(files=audit,
            scope='全部Java主源码及前端ts/vue/css，测试标明；统计仅导航，中文充分性与例外见验证报告'), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('routes', len(routes), 'field declarations', len(fields), 'audit files', len(audit))


if __name__ == '__main__':
    main()
