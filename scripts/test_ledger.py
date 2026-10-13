"""验证台账禁止跳项和无证据完成，并确保更新只发生在独立测试副本。"""
import importlib.util
import pathlib
import re
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ledger", pathlib.Path(__file__).with_name("ledger.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LedgerWorkflowTest(unittest.TestCase):
    """以真实台账结构验证状态流转和前置条件，不修改工作台账。"""

    def setUp(self):
        self.source = MODULE.LEDGER
        self.original_root = MODULE.ROOT
        self.temp = tempfile.TemporaryDirectory()
        MODULE.ROOT = pathlib.Path(self.temp.name)
        MODULE.LEDGER = MODULE.ROOT / "ledger.md"
        # 测试副本从全部未开始状态建立，避免真实实施进度影响前置条件用例。
        text = self.source.read_text(encoding="utf-8")
        text = re.sub(r"^(\| T\d{2} \|[^\n]*? \| )(进行中|待测试|待修复|已完成)( \|)",
                      lambda m: m[1] + "未开始" + m[3], text, flags=re.MULTILINE)
        MODULE.LEDGER.write_text(text, encoding="utf-8")

    def tearDown(self):
        MODULE.ROOT = self.original_root
        MODULE.LEDGER = self.source
        self.temp.cleanup()

    def test_cannot_skip_unfinished_task(self):
        with self.assertRaisesRegex(ValueError, "前置事项尚未完成"):
            MODULE.update("T02", "进行中", "", "")

    def test_completion_requires_evidence_and_unlocks_next_task(self):
        with self.assertRaisesRegex(ValueError, "实际验证报告"):
            MODULE.update("T01", "已完成", "result.md", "")
        (MODULE.ROOT / "result.md").write_text("核验通过", encoding="utf-8")
        MODULE.update("T01", "已完成", "result.md", "真实核验通过")
        MODULE.update("T02", "进行中", "", "下一条开始")
        result = MODULE.LEDGER.read_text(encoding="utf-8")
        self.assertIn("实施进度：1 / 30", result)
        self.assertIn("task/T01", result)
        self.assertIn("| T02 | 准备 | 冻结两套原版及运行条件 | 进行中 |", result)


if __name__ == "__main__":
    unittest.main()
