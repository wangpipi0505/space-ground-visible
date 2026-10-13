"""T03 契约静态清单：只读冻结源码，核对路由、DTO声明和公开入口覆盖。

本脚本使用标准库和明确的源码匹配范围，不解释 Java，也不声称证明数值精度。
--freeze 仅用于本事项首次冻结；普通执行必须与冻结清单一致。
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "verification/T03/contract-inventory.json"
SOURCES = {"space": ROOT / "verification/baseline-copies/space/backend/src/main/java",
           "ballistic": ROOT / "verification/baseline-copies/ballistic/source/src/main/java"}


def scan():
    """逐文件保留出处与行号；声明原文同时保留泛型、默认表达式及单位注释。"""
    routes, declarations, entries, hashes = [], [], [], {}
    for system, base in SOURCES.items():
        for path in sorted(base.rglob("*.java")):
            source = path.read_text(encoding="utf-8-sig")
            rel = path.relative_to(ROOT).as_posix()
            is_controller = "@RestController\n" in source or "@RestController\r\n" in source
            is_model = path.name in ("SpaceApiModels.java", "VisibilityRequest.java", "TargetTrajectoryFitter.java") or "/entity/" in rel or path.name == "VisibilityObservationResult.java"
            is_entry = is_controller or path.name in ("OrbitCalculator.java", "TargetTrajectoryFitter.java", "TrajectoryFileResolver.java", "PlanningFacadeService.java")
            if not (is_controller or is_model or is_entry):
                continue
            hashes[rel] = hashlib.sha256(path.read_bytes()).hexdigest()
            if is_controller:
                prefix = re.search(r'@RequestMapping\("([^"]*)"\)', source).group(1)
                pattern = r'@(Get|Post|Put|Delete|Patch)Mapping(?:\((.*?)\))?\s+public\s+([^\{]+)\{'
                for match in re.finditer(pattern, source, re.S):
                    suffix = re.search(r'"([^"]*)"', match.group(2) or "")
                    routes.append(dict(system=system, method=match.group(1).upper(), path=prefix + (suffix.group(1) if suffix else ""), source=rel, line=source[:match.start()].count("\n")+1, signature=" ".join(match.group(3).split()), annotation=match.group(2)))
            owner = path.stem
            for line_no, line in enumerate(source.splitlines(), 1):
                cls = re.search(r'public\s+(?:static\s+)?(?:final\s+)?class\s+(\w+)', line)
                if cls:
                    owner = cls.group(1)
                # 字段采用完整声明行冻结，不把方法体赋值误判为字段。
                if is_model and re.match(r'^\s+(?:private|public)\s+(?:(?:static|final)\s+)*[\w.<>,?\[\] ]+\s+\w+\s*(?:=.*)?;', line) and "(" not in line.split("=", 1)[0]:
                    declarations.append(dict(system=system, owner=owner, source=rel, line=line_no, declaration=line.strip()))
                if is_entry and re.match(r'^\s*public\s+', line) and "(" in line and not re.search(r'\bclass\b', line) and not "=" in line.split("(", 1)[0]:
                    tail = "\n".join(source.splitlines()[line_no-1:])
                    signature = tail.split("{", 1)[0].strip()
                    entries.append(dict(system=system, source=rel, line=line_no, declaration=line.strip(), signature=" ".join(signature.split())))
    return dict(schemaVersion=1, scope="T03 静态契约冻结；不含数值精度证明", routes=routes, fields=declarations, javaPublicEntries=entries, sourceSha256=hashes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true")
    args = parser.parse_args()
    actual = scan()
    if args.freeze:
        OUT.write_text(json.dumps(actual, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    frozen = json.loads(OUT.read_text(encoding="utf-8"))
    for section in ("routes", "fields", "javaPublicEntries", "sourceSha256"):
        assert actual[section] == frozen[section], "冻结清单与源码不一致: " + section
    assert len(actual["routes"]) == 30, "实际路由数量发生变化"
    doc = (ROOT / "docs/接口与数值口径基线.md").read_text(encoding="utf-8")
    for route in actual["routes"]:
        assert route["method"]+" "+route["path"] in doc, "文档遗漏路由: " + route["path"]
    for field in actual["fields"]:
        assert field["declaration"].replace("|", "\\|") in doc, "文档遗漏字段: " + field["declaration"]
    print(json.dumps(dict(result="通过", routes=len(actual["routes"]), fields=len(actual["fields"]), javaPublicEntries=len(actual["javaPublicEntries"]), sourceFiles=len(actual["sourceSha256"]), checks=["源码摘要", "全部Controller路由", "DTO及实体字段原声明", "公开入口原声明", "文档路由与字段覆盖"]), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
