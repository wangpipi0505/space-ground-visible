"""A03 恢复路径限定来源核验：比较实际控件条件/异常状态与手册，不运行浏览器。"""
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
checks = []


def check(name, passed, evidence):
    checks.append({'name': name, 'passed': bool(passed), 'evidence': evidence})


def function(text, name):
    # 当前组件顶层函数以独占行的右括号结束；限定检查不冒称通用 TS 解析器。
    return re.search(r'(?:async )?function ' + name + r'\([^\n]*\).*?\n}', text, re.S).group(0)


def catch(text):
    return re.search(r'catch \([^)]*\) \{(.*?)\}', text, re.S).group(1)


manual = (ROOT / 'docs/软件功能使用说明.md').read_text(encoding='utf-8')
section = manual.split('### 4.6 文件失败、过期与恢复\n', 1)[1].split('\n## 5.', 1)[0]
rows = {key.strip(): value.strip() for key, value in re.findall(r'^\| ([^|]+) \| ([^|]+) \|$', section, re.M)}
window_path = 'frontend/src/components/BallisticWindowControls.vue'
shared_path = 'frontend/src/components/BallisticFitFileResult.vue'
window = (ROOT / window_path).read_text(encoding='utf-8')
shared = (ROOT / shared_path).read_text(encoding='utf-8')
preview = function(window, 'loadPreview')
download = function(window, 'downloadOriginal')
choose = function(window, 'chooseFile')
select = function(window, 'selectGenerated')
clear = function(window, 'clearSource')
gated = re.search(r'<details v-if="target.preview".*?</details>', window, re.S)
pagination_requires_preview = bool(gated and '预览上一页' in gated.group(0) and '预览下一页' in gated.group(0))
check('窗口翻页控件只在已有真实预览时渲染', pagination_requires_preview,
      gated.group(0) if gated else '未找到实际条件')
preview_error = catch(preview)
download_error = catch(download)
keeps_reference = all('target.error =' in body and
                      not re.search(r'target\.(?:file|preview|generatedId)\s*=|clearSource\(|emit\(', body)
                      for body in [preview_error, download_error])
check('窗口预览和下载异常仅设错误且保留引用/成功页', keeps_reference,
      {'previewCatch': preview_error, 'downloadCatch': download_error})
clear_first = 'target.file = undefined' in clear and 'target.preview = undefined' in clear
native_retry = clear_first and choose.index('clearSource(target)') < choose.index('uploadBallisticFile(file)') and "input.value = ''" in choose
check('原生重选清旧来源后上传且允许再次选择同一文件', native_retry, choose)
explicit_retry = clear_first and select.index('clearSource(target)') < select.index('target.file = selected.reference') and 'await loadPreview(target, 1)' in select and '<option value="">请选择已成功登记' in window
check('生成结果可先选空项清旧再显式选引用并真实读取第一页', explicit_retry, select)
first = rows.get('窗口首次预览失败', '')
check('手册首次失败按真实可用的原生重选/清空再显式选择恢复',
      pagination_requires_preview and native_retry and explicit_retry and
      all(text in first for text in ['尚无成功预览', '没有翻页按钮', '原生选择', '先选空项', '再次显式选择', '等待真实预览成功']), first)
paging = rows.get('窗口已有预览后翻页失败', '')
check('手册已有预览的翻页失败保留旧页并只重试可用页按钮',
      pagination_requires_preview and keeps_reference and
      all(text in paging for text in ['此前成功页', '可用的', '预览上一页', '预览下一页', '等到实际新页成功']), paging)
window404 = rows.get('窗口预览/下载404', '')
check('手册窗口404明确保留引用/旧页并要求重新上传或重新生成选择',
      keeps_reference and native_retry and explicit_retry and
      all(text in window404 for text in ['只显示错误', '仍保留', '重新上传', '重新登记', '显式选择', '等待真实预览成功']), window404)
revoke = function(shared, 'revokeMissingReference')
shared_revokes = 'failure.status === 404' in revoke and "emit('reference', undefined)" in revoke and 'reference.value = undefined' not in revoke
check('共用生成结果辅助404撤销父级候选但不直接清本页引用', shared_revokes, revoke)
for name in ['loadPreview', 'loadTrajectory', 'download']:
    check('共用辅助异常调用404撤销:' + name,
          'revokeMissingReference(failure)' in catch(function(shared, name)), catch(function(shared, name)))
shared404 = rows.get('拟合/模拟/重定时辅助404、生成候选失效', '')
watch = window.split('watch(() => props.generatedResults', 1)[1].split('/** 原生文件', 1)[0]
check('手册区分共用辅助404撤销候选、窗口监听失效与本页原计算保留',
      shared_revokes and 'clearSource(target)' in watch and
      all(text in shared404 for text in ['撤销父级生成候选', '监听候选变化', '清旧引用与预览', '保留原计算成功', '重新登记结果文件', '新ID', '再次显式选择', '等待真实预览成功']), shared404)
check('不再泛称窗口总有预览页按钮或所有404自动撤销',
      '窗口可再点对应预览页按钮' not in section and '| 文件404、生成候选失效 | 旧引用被撤销。' not in section, section)
sources = []
for path in ['docs/软件功能使用说明.md', window_path, shared_path]:
    data = (ROOT / path).read_bytes()
    sources.append({'path': path, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
result = {'passed': all(item['passed'] for item in checks), 'scope': '仅静态文案与当前组件渲染条件/异常状态核验，不是浏览器执行证明',
          'checks': checks, 'sources': sources, 'errors': [item['name'] for item in checks if not item['passed']]}
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(0 if result['passed'] else 1)
