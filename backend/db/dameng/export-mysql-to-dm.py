#!/usr/bin/env python3
"""将 MySQL 中的业务数据导出为达梦（DM8）兼容的 SQL 脚本。

仅依赖 Python 标准库与本机 mysql 命令行客户端（通过 MYSQL_PWD 环境变量传口令，
避免命令行明文）。生成的脚本配合 db/dameng/schema.sql 使用：先在达梦执行
schema.sql 建表，再执行生成的 data.sql 导入数据；也可以用 DM 数据迁移工具
（DTS）或 disql 直接执行。

用法示例（Git Bash / Linux）：
    MYSQL_PWD=123456 python db/dameng/export-mysql-to-dm.py \
        --host 127.0.0.1 --user root --database space_target_visible \
        --mysql-client "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
        --output db/dameng/data.sql
"""
import argparse
import datetime
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# 需要导出的业务表（按外键无关顺序排列；任务结果表数据量可能较大，放最后）
TABLES = [
    "ZG_STATION",
    "ZG_XZ_MANAGE",
    "ZG_XZ_MANAGE_TARGET",
    "ZG_VISIBILITY_TASK_RESULT",
]

# 含 IDENTITY 主键的表，导入时需显式开启 IDENTITY_INSERT 以保留原主键值
IDENTITY_TABLES = {"ZG_XZ_MANAGE", "ZG_XZ_MANAGE_TARGET", "ZG_VISIBILITY_TASK_RESULT"}

HEADER = """-- =====================================================================
-- 空间目标可见窗口服务 —— 达梦数据库（DM8）数据导入脚本
-- 由 db/dameng/export-mysql-to-dm.py 从 MySQL 库 {database} 导出于 {exported_at}
-- 使用方式：先在达梦目标库执行 db/dameng/schema.sql 建表，再执行本脚本
-- （DM 管理工具 / disql 均可直接执行；IDENTITY 列通过 SET IDENTITY_INSERT 保留原值）。
-- =====================================================================
"""


def run_mysql_xml(args, table):
    """以 XML 输出读取整表数据，NULL 值以 xsi:nil 标记区分于字符串。"""
    command = [
        args.mysql_client,
        f"--host={args.host}",
        f"--port={args.port}",
        f"--user={args.user}",
        "--xml",
        "--default-character-set=utf8mb4",
        args.database,
        "-e", f"SELECT * FROM {table}",
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"读取表 {table} 失败：{result.stderr.strip()}")
    return ET.fromstring(result.stdout)


NUMBER_LITERAL = re.compile(r"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$")


def format_literal(field):
    """把 XML 字段值转换为 DM8 SQL 字面量；数值不加引号，日期时间与字符串按文本处理。"""
    if field.get("{http://www.w3.org/2001/XMLSchema-instance}nil") == "true":
        return "NULL"
    text = field.text or ""
    if text and NUMBER_LITERAL.match(text):
        return text
    escaped = text.replace("'", "''")
    return f"'{escaped}'"


def export_table(args, table):
    root = run_mysql_xml(args, table)
    rows = root.findall("row")
    lines = [f"-- 表 {table}（{len(rows)} 行）"]
    if not rows:
        lines.append(f"-- （无数据）\n")
        return lines
    columns = [field.get("name") for field in rows[0].findall("field")]
    column_list = ", ".join(f'"{name}"' for name in columns)
    if table in IDENTITY_TABLES:
        lines.append(f'SET IDENTITY_INSERT "{table}" ON;')
    for row in rows:
        values = ", ".join(format_literal(field) for field in row.findall("field"))
        lines.append(f'INSERT INTO "{table}" ({column_list}) VALUES ({values});')
    if table in IDENTITY_TABLES:
        lines.append(f'SET IDENTITY_INSERT "{table}" OFF;')
    lines.append("")
    return lines


def main():
    parser = argparse.ArgumentParser(description="导出 MySQL 业务数据为达梦 DM8 兼容 SQL")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default="3306")
    parser.add_argument("--user", default="root")
    parser.add_argument("--database", default="space_target_visible")
    parser.add_argument("--mysql-client", default="mysql")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    lines = [HEADER.format(database=args.database,
                           exported_at=datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))]
    for table in TABLES:
        lines.extend(export_table(args, table))
    lines.append("COMMIT;\n")
    with open(args.output, "w", encoding="utf-8") as output:
        output.write("\n".join(lines))
    print(f"已导出 {len(TABLES)} 张表到 {args.output}")


if __name__ == "__main__":
    sys.exit(main())
