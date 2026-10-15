"""A17 正常原子同步及既有引用保护核验，不强制推送或移动历史标签。"""
import json
import subprocess
import sys
from run_validation import ROOT, OUT, RUN, execute, save

sys.stdout.reconfigure(encoding='utf-8')
phase = sys.argv[1]
head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, check=True).stdout.decode().strip()
branch = 'refs/heads/codex/ballistic-workbench-ui'
tag = 'refs/tags/acceptance/2026-10-15/A17'


def refs(name):
    execute(name, ['git', 'ls-remote', 'origin'])
    return dict(line.split('\t')[::-1] for line in (OUT / (name + '-stdout.txt')).read_text(encoding='utf-8').splitlines())


before = refs('remote-' + phase + '-before')
args = ['git', 'push', '--atomic', 'origin', 'HEAD:refs/heads/main', 'HEAD:' + branch]
if phase == 'acceptance':
    assert tag not in before
    args.append(tag)
execute('remote-' + phase + '-push', args)
after = refs('remote-' + phase + '-after')
assert after['refs/heads/main'] == after[branch] == head
if phase == 'acceptance':
    assert after[tag + '^{}'] == head
allowed = {'HEAD', 'refs/heads/main', branch}
unchanged = {name: value for name, value in before.items() if name not in allowed}
assert all(after.get(name) == value for name, value in unchanged.items()), '历史引用变化'
save('remote-' + phase + '-sync', dict(passed=True, commit=head, branch=branch, tag=tag,
    preservedReferences=len(unchanged), actualReferences=len(after), normalAtomicPush=True,
    expectedBranchMatches=True, acceptanceTagCommit=after[tag + '^{}']))
print('远端同步与历史引用核对通过', head)
