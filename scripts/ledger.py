"""按事项顺序更新中文台账，记录真实验证证据及可解析的完成提交标签。"""
import argparse
import datetime
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "待办事项台账.md"


def update(task: str, status: str, evidence: str, note: str) -> None:
    """禁止越过未完成前置事项，完成项必须引用已经存在的验证报告。"""
    text = LEDGER.read_text(encoding="utf-8")
    rows = re.findall(r"^\| (T\d{2}) \|.*$", text, re.MULTILINE)
    number = int(task[1:])
    if task not in rows:
        raise ValueError("事项编号不存在：" + task)
    for prior in range(1, number):
        row = re.search(r"^\| T%02d \|.*$" % prior, text, re.MULTILINE).group()
        if "| 已完成 |" not in row:
            raise ValueError("前置事项尚未完成：T%02d" % prior)
    if status == "已完成" and not (ROOT / evidence).is_file():
        raise ValueError("完成事项必须有实际验证报告")
    pattern = r"^(\| " + task + r" \|[^\n]*? \| )(未开始|进行中|待测试|待修复|已完成)( \|)"
    text, count = re.subn(pattern, lambda m: m[1] + status + m[3], text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise ValueError("事项状态列未匹配")
    completed = len(re.findall(r"^\| T\d{2} \|[^\n]*? \| 已完成 \|", text, re.MULTILINE))
    text = re.sub(r"实施进度：\d+ / 30", "实施进度：%d / 30" % completed, text)
    timestamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(timespec="seconds")
    record = "| %s | %s | %s | %s | %s | 见报告 | %s | %s |" % (
        task, status, timestamp, "见验证报告", note.replace("|", "／"), evidence or "执行中",
        "task/" + task if status == "已完成" else "尚未完成")
    anchor = "<!-- 执行记录追加位置 -->"
    text = text.replace(anchor, record + "\n" + anchor, 1)
    LEDGER.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="更新逐项实施台账")
    parser.add_argument("task")
    parser.add_argument("status", choices=["进行中", "待测试", "待修复", "已完成"])
    parser.add_argument("--evidence", default="")
    parser.add_argument("--note", default="")
    args = parser.parse_args()
    update(args.task, args.status, args.evidence, args.note)
