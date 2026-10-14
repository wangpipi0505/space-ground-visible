"""有限反例：只检验入口、无运行资产、只读及报告取值；不重新计算轨迹。"""
from pathlib import Path
from unittest.mock import patch
import copy
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'verification/A04'


def module(name):
    """仅导入无副作用的报告/交付代码，不加载原数值调度。"""
    spec = importlib.util.spec_from_file_location(name, OUT / (name + '.py'))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def fingerprint(root):
    """最小证据树前后逐文件绑定；禁止入口新增摘要或改写原证据。"""
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob('*') if p.is_file()}


class DeliveryTests(unittest.TestCase):
    def test_default_path(self):
        """默认仓库路径应能核验，不需要执行 Java/Maven。"""
        result = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(OUT / 'delivery_check.py')], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', errors='replace'))

    def test_no_runtime_and_readonly(self):
        """仅复制可交付清单所选文件，模拟其他机器没有 ignored 资产。"""
        check = module('delivery_check')
        with tempfile.TemporaryDirectory(prefix='a04-delivery-') as directory:
            root = Path(directory)
            manifest_path = OUT / 'candidate-delivery-manifest.json'
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            for item in manifest['files']:
                dest = root / item['path']
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / item['path'], dest)
            dest = root / 'verification/A04/candidate-delivery-manifest.json'
            shutil.copyfile(manifest_path, dest)
            self.assertFalse((root / 'verification/runtime').exists())
            before = fingerprint(root)
            result = check.verify(root)
            self.assertEqual(result['historicalCommands'], 47)
            self.assertEqual(result['junit']['tests'], 77)
            self.assertEqual(before, fingerprint(root))
            self.assertFalse((root / 'verification/runtime').exists())
            print('模拟证据树：', len(before), '文件；无 runtime；前后SHA完全一致')

    def test_missing_required_evidence(self):
        """缺少仓库证据应具名失败，与缺少 ignored 构建资产区分。"""
        check = module('delivery_check')
        with tempfile.TemporaryDirectory(prefix='a04-missing-') as directory:
            with self.assertRaisesRegex((AssertionError, FileNotFoundError), 'candidate-delivery-manifest'):
                check.verify(Path(directory))

    def test_path_boundary(self):
        """路径越界与ignored运行资产不能混入可交付清单。"""
        check = module('delivery_check')
        for name in ('../outside', 'verification/runtime/A04/current-source', 'verification/baseline-copies/ballistic/build/a.jar'):
            with self.assertRaises(AssertionError):
                check.inside(ROOT, name)

    def test_old_promise_removed(self):
        """正式报告及生成源不可继续承诺不存在的统计/报告执行阶段。"""
        for name in ('精度测试报告.md', 'finalize_precision.py'):
            text = (OUT / name).read_text(encoding='utf-8')
            self.assertNotIn('在空的新 A04 运行目录', text)
            self.assertNotIn('grid → 统计/报告 → protect', text)
            self.assertIn('delivery_check.py', text)

    def test_nonzero_observation(self):
        """只在内存改变一原点范数，报告必须输出非零，不能悄悄硬编码归零。"""
        report = module('finalize_precision')
        values = {name: json.loads((OUT / (name + '.json')).read_text(encoding='utf-8'))
                  for name in ('test-summary', 'new-build-bindings', 'complete-differences', 'scenario-windows',
                               'package102-residuals', 'coordinate-metrics', 'holdout-metrics', 'public-grid-metrics', 'protected-after')}
        mutated = copy.deepcopy(values)
        sample = mutated['package102-residuals']['targets'][0]['samples'][0]
        sample['positionNormM'], sample['velocityNormMps'] = '0.00000000123456789', '0.00000234567891'
        with tempfile.TemporaryDirectory(prefix='a04-report-') as directory:
            with patch.object(report, 'OUT', Path(directory)), patch.object(report, 'load', side_effect=lambda name: mutated[name]):
                report.write_report()
            text = (Path(directory) / '精度测试报告.md').read_text(encoding='utf-8')
            self.assertIn('1.23456789e-09 m / 2.34567891e-06 m/s', text)
            if (OUT / 'delivery_check.py').exists():
                check = module('delivery_check')
                with self.assertRaisesRegex(AssertionError, '原点报告与指标不匹配'):
                    check.check_observation_report(mutated['package102-residuals'], (OUT / '精度测试报告.md').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
