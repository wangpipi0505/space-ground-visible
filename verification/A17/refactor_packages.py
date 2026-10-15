"""一次性机械迁移脚本；运行前必须已有 A17/before 源码快照。"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'backend/src/main/java'
BASE = 'com.runisys.orbit.spacevisibility.'
DOMAIN = BASE + 'domain.ballistic'
API = BASE + 'api.ballistic'
INFRA = BASE + 'infrastructure.files'


def mapped(text):
    text = text.replace('com.tx.alg.trajectory', DOMAIN).replace('com.tx.alg.api.VisibilityRequest', API + '.VisibilityRequest')
    text = text.replace('com.tx.alg.TrajectoryFileResolver', INFRA + '.TrajectoryFileResolver')
    text = text.replace('com.tx.alg.api.TargetTrajectoryFitController.ErrorResponse', API + '.BallisticErrorResponse')
    text = text.replace('com.tx.alg', DOMAIN)
    return text


assert (ROOT / 'verification/runtime/A17/before/backend/src/main/java/com/tx/alg/OrbitCalculator.java').exists()
# 先保留测试专用的直接算法对照适配器；它们不进入产品、Spring 扫描或交付包。
support = ROOT / 'backend/src/test/java/com/runisys/orbit/spacevisibility/testsupport'
support.mkdir(parents=True, exist_ok=True)
for old, new in [('OrbitVisibilityController', 'WindowEngineReference'), ('TargetTrajectoryFitController', 'TrajectoryEngineReference')]:
    source = (JAVA / ('com/tx/alg/api/' + old + '.java')).read_text(encoding='utf-8')
    source = source.replace('package com.tx.alg.api;', 'package ' + BASE + 'testsupport;')
    source = source.replace('import com.tx.alg.OrbitCalculator;', 'import com.tx.alg.OrbitCalculator;\nimport com.tx.alg.api.VisibilityRequest;')
    source = source.replace(old, new)
    source = re.sub(r'^@(?:RestController|RequestMapping)\(?.*$', '', source, flags=re.M)
    (support / (new + '.java')).write_text(source, encoding='utf-8')

for source in list((JAVA / 'com/tx/alg').rglob('*.java')):
    if source.stem in ('OrbitVisibilityController', 'TargetTrajectoryFitController'):
        source.unlink()
        continue
    package = API if source.stem == 'VisibilityRequest' else INFRA if source.stem == 'TrajectoryFileResolver' else DOMAIN
    destination = JAVA / package.replace('.', '/') / source.name
    destination.parent.mkdir(parents=True, exist_ok=True)
    text = re.sub(r'^package [^;]+;', 'package ' + package + ';', source.read_text(encoding='utf-8'), count=1)
    destination.write_text(text, encoding='utf-8')
    source.unlink()

for tree in ('main', 'test'):
    for path in list((ROOT / ('backend/src/' + tree + '/java')).rglob('*.java')):
        old = path.read_text(encoding='utf-8')
        # 冻结原版加载器中的旧 FQN 保持，它们指向只读原版 class。
        tokens = {}
        def protect(match):
            key = '__FROZEN_FQN_' + str(len(tokens)) + '__'
            tokens[key] = match.group()
            return key
        text = re.sub(r'"com\.tx\.[^"\n]*"', protect, old) if 'loader.loadClass(' in old else old
        text = mapped(text)
        text = text.replace(DOMAIN + '.api.OrbitVisibilityController', BASE + 'testsupport.WindowEngineReference')
        text = text.replace(DOMAIN + '.api.TargetTrajectoryFitController', BASE + 'testsupport.TrajectoryEngineReference')
        if tree == 'test':
            text = re.sub(r'\bOrbitVisibilityController\b', 'WindowEngineReference', text)
            text = re.sub(r'\bTargetTrajectoryFitController\b', 'TrajectoryEngineReference', text)
        # 领域输入改名只改变 Java 类型，不改变字段或校验函数名。
        for a, b in [('FitRequest','FitInput'), ('SimulationRequest','SimulationInput'), ('RetimeTrajectoryRequest','RetimeInput')]:
            text = re.sub(r'\b' + a + r'\b', b, text)
        for token, value in tokens.items():
            text = text.replace(token, value)
        if text != old:
            path.write_text(text, encoding='utf-8')
        if tree == 'test' and path.parts[-1].endswith('.java'):
            package = re.search(r'^package ([^;]+);', text)
            if package:
                destination = ROOT / 'backend/src/test/java' / package[1].replace('.', '/') / path.name
                if path != destination:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    path.rename(destination)
