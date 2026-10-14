"""A05有效反例：重复表头、单位、同刻完整对齐及微小状态/窗口变化。"""
import csv
import io
import math
import unittest
from datetime import timedelta
import run_stk_validation as qa


class StkValidationTest(unittest.TestCase):
    def source(self, times=('00.000', '00.100'), values=None):
        """同名两组列故意取不同值，DictReader覆盖会立刻破坏第一组三维量。"""
        header = ['Time (UTCG)'] + ['x (km)', 'y (km)', 'z (km)', 'x (km/sec)', 'y (km/sec)', 'z (km/sec)'] * 2
        stream = io.StringIO()
        writer = csv.writer(stream)
        writer.writerow(header)
        for time in times:
            writer.writerow(['1 Jul 2026 08:24:' + time] + (values or list(range(1, 13))))
        return stream.getvalue()

    def test_duplicate_headers_and_units(self):
        rows = qa.parse_stk_text(self.source(), 'missile')
        self.assertEqual(rows[0]['ecefP'], (1000., 2000., 3000.))
        self.assertEqual(rows[0]['ecefV'], (4000., 5000., 6000.))
        self.assertEqual(rows[0]['eciP'], (7000., 8000., 9000.))
        self.assertEqual(rows[0]['eciV'], (10000., 11000., 12000.))

    def test_invalid_order_duplicate_nonfinite_width(self):
        for text in (self.source(('00.100', '00.000')), self.source(('00.000', '00.000')),
                     self.source(values=[float('nan')] + list(range(2, 13))),
                     self.source(values=[float('inf')] + list(range(2, 13))), self.source(values=list(range(1, 12)))):
            with self.subTest(text=text), self.assertRaises(ValueError):
                qa.parse_stk_text(text, 'missile')

    def test_full_utc_alignment_rejects_offset_missing_reorder(self):
        rows = qa.parse_stk_text(self.source(), 'missile')
        for other in (rows[:-1], list(reversed(rows)),
                      [dict(r, utc=r['utc'] + timedelta(milliseconds=1)) for r in rows],
                      [dict(r, utc=r['utc'] + timedelta(seconds=1)) for r in rows]):
            with self.subTest(other=other), self.assertRaises(ValueError):
                qa.compare(rows, other, ('eciP',))

    def test_tiny_position_velocity_change_not_hidden(self):
        rows = qa.parse_stk_text(self.source(), 'missile')
        other = [dict(r, eciP=tuple(v + 0.00001 for v in r['eciP']),
                      eciV=tuple(v + 0.0000001 for v in r['eciV'])) for r in rows]
        result = qa.compare(rows, other, ('eciP', 'eciV'))
        self.assertAlmostEqual(result['eciP']['norm']['rmse'], math.sqrt(3) * 0.00001, places=10)
        self.assertGreater(result['eciV']['norm']['max'], 0)

    def test_one_ms_window_change(self):
        first = [{'satellite': 'SAT-A', 'start': 1., 'end': 2., 'duration': 1., 'points': 3}]
        second = [dict(first[0], start=1.001, duration=.999)]
        self.assertFalse(qa.compare_windows(first, second)['equal'])
        self.assertAlmostEqual(qa.compare_windows(first, second)['maxStartDeltaSec'], .001)

    def test_reject_software_access_truth(self):
        for name in ('access_diagnostic.csv', 'software_visibility_SAT-A.csv', 'target_offset.e'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                qa.require_stk_access(name, {'origin': 'software'})


if __name__ == '__main__':
    unittest.main(verbosity=2)
