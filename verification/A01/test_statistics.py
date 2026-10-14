"""验收统计必要自检：nearest-rank、空输入拒绝、错误分母及矩阵唯一性；先运行失败再实现统计。"""
from performance import percentile_ms, summarize, validate_matrix
assert percentile_ms([10,20,30,40],.5)==20
assert percentile_ms([10,20,30,40],.95)==40
assert percentile_ms([40,10,30,20],.99)==40
try:percentile_ms([], .99)
except ValueError:pass
else:raise AssertionError('空样本不能伪造分位数')
rows=[dict(ms=10,ok=True),dict(ms=20,ok=False)]
s=summarize(rows,1);assert s['errorRate']==.5 and s['validSamples']==1 and s['rps']==2
validate_matrix([dict(id='A',status='PASS'),dict(id='B',status='BLOCKED')])
try:validate_matrix([dict(id='A',status='PASS'),dict(id='A',status='PASS')])
except AssertionError:pass
else:raise AssertionError('重复ID不能伪造覆盖数量')
print('PASS statistics and matrix selfcheck')
