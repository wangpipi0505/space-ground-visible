"""A01初候选兼容入口：共享执行实现；候选实例路径独立，不受A01_FINAL切换。"""
import sys
from acceptance_shared import Acceptance, ROOT, JAVA, sha

driver = Acceptance(final=False)
OUT, RUN, CLEAN, PACKAGE = driver.OUT, driver.RUN, driver.CLEAN, driver.PACKAGE
save, execute = driver.save, driver.execute
prepare, build, numeric, package, original = driver.prepare, driver.build, driver.numeric, driver.package, driver.original

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) != 2 or sys.argv[1] not in ('prepare', 'build', 'numeric', 'package', 'original'):
        raise SystemExit('阶段必须为prepare/build/numeric/package/original')
    getattr(driver, sys.argv[1])()
