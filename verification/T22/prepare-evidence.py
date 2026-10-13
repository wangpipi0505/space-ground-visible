"""提取CLI实际结果，核对原HTTP文本与浏览器下载字节；不从前端生成数值黄金。"""
from pathlib import Path
import json
import hashlib

root = Path(__file__).resolve().parents[2]
runtime = root / "verification/runtime/T22"
report = root / "verification/T22"
text = (runtime / "browser-results.txt").read_text(encoding="utf-8-sig")
result = json.loads(text.split("### Result\n", 1)[1].split("\n### Ran", 1)[0])
raw = result.pop("rawText").encode("utf-8")
(runtime / "http-response-original.json").write_bytes(raw)
download = (runtime / "download-response.json").read_bytes()
assert raw == download, "下载JSON字节与HTTP响应不相同"
source = (root / "test-data/reference/ballistic/trajectory-0.1s-standard.csv").read_bytes()
original = (runtime / "download-original.csv").read_bytes()
assert source == original, "原文件下载字节与上传来源不相同"
body = json.loads(raw)
result["downloads"] = {
    "responseBytes": len(raw), "responseSha256": hashlib.sha256(raw).hexdigest(),
    "responseByteEqual": raw == download,
    "originalBytes": len(original), "originalSha256": hashlib.sha256(original).hexdigest(),
    "originalByteEqual": source == original,
}
result["actualBest"] = {key: body["best"][key] for key in ["offsetSecond", "visibleArcCount", "visiblePointCount", "visibleDurationSec", "finalVisibleWindow"]}
(report / "browser-http-and-downloads.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result["downloads"], ensure_ascii=False))

# 其余真实Chrome请求单独保存完整原响应到ignored runtime，摘要含SHA/大小与结果层级，便于复核。
for filename, output in [("browser-edge.txt", "browser-edge-http.json"), ("browser-file-failures-third.txt", "browser-failure-and-remote-http.json")]:
    text = (runtime / filename).read_text(encoding="utf-8-sig")
    value = json.loads(text.split("### Result\n", 1)[1].split("\n### Ran", 1)[0])
    for index, item in enumerate(value["evidence"]):
        raw_text = item.pop("rawText")
        data = raw_text.encode("utf-8")
        path = runtime / f"{output.removesuffix('.json')}-{index}-original.json"
        path.write_bytes(data)
        item["originalResponse"] = {"runtimeFile": str(path.relative_to(root)).replace('\\', '/'), "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        body = json.loads(data)
        item["responseSummary"] = {key: body[key] for key in ["code", "error", "fileId", "originalName", "sizeBytes", "serverPath"] if key in body}
        if "allResults" in body:
            item["responseSummary"] = {"best": {key: body["best"][key] for key in ["offsetSecond", "visibleArcCount", "visiblePointCount", "visibleDurationSec"]} if body["best"] else None, "allResults": [{key: entry[key] for key in ["offsetSecond", "visibleArcCount", "visiblePointCount", "visibleDurationSec"]} for entry in body["allResults"]]}
    (report / output).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# 终端原输出留runtime；持久记录仅规整工具末尾空白，内容与实际命令结果不改。
for source, destination in [("frontend-tests-final.log", "frontend-tests-final.txt"), ("frontend-build-final.log", "frontend-build-final.txt"), ("browser-console-final.txt", "browser-console-final.txt"), ("browser-requests-final.txt", "browser-requests-final.txt")]:
    data = (runtime / source).read_text(encoding="utf-8-sig")
    (report / destination).write_text('\n'.join(line.rstrip() for line in data.splitlines()).rstrip() + '\n', encoding="utf-8")
for path in [report / 'upload-first-response.txt', report / 'upload-first-headers.txt', report / 'upload-chinese-response.txt', report / 'download-chinese-headers.txt']:
    data = path.read_text(encoding="utf-8-sig")
    path.write_text('\n'.join(line.rstrip() for line in data.splitlines()).rstrip() + '\n', encoding="utf-8")

files = [path for path in (root / 'frontend/src').rglob('*') if path.is_file() and (path.name.startswith('ballistic') or path.name.startswith('Ballistic') or path.name == 'style.css') and '.spec.' not in path.name]
manifest = {str(path.relative_to(root)).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
manifest['frontend/pnpm-lock.yaml'] = hashlib.sha256((root / 'frontend/pnpm-lock.yaml').read_bytes()).hexdigest()
(report / 'source-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
