"""核验浏览器真实下载与原 CSV 字节，以及两页预览中的原单元格。"""
import csv
import hashlib
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def result(name):
    text = (ROOT / 'output/playwright/T13' / name).read_text(encoding='utf-8-sig')
    return json.loads(text.split('### Result', 1)[1].strip().splitlines()[0])


def main():
    records = []
    pairs = [('upload', 'test-data/reference/ballistic/trajectory-0.1s-standard.csv', '弹道样例-含中文.csv'),
             ('result', 'test-data/baseline/ballistic/simulate/trajectory-0.csv', 'T13-模拟结果.csv')]
    for kind, source, filename in pairs:
        expected = ROOT / source
        actual = ROOT / 'verification/runtime/T13/browser-download' / (kind + '.csv')
        capture = result(kind + '-download.txt')
        cells = list(csv.reader(io.StringIO(expected.read_text(encoding='utf-8-sig'))))
        assert sha(expected) == sha(actual), kind + ' 下载字节有差异'
        assert capture['suggestedFilename'] == filename
        assert capture['reference']['originalName'] == filename
        assert capture['reference']['sizeBytes'] == len(expected.read_bytes())
        assert capture['preview']['header'] == cells[0]
        assert capture['preview']['rows'] == cells[1:6]
        assert capture['preview']['totalRows'] == len(cells) - 1
        records.append({'kind': kind, 'originalName': filename, 'bytes': len(expected.read_bytes()),
                        'sha256': sha(actual), 'totalRows': len(cells) - 1, 'firstPageExact': True})
    # 第二页由真实“下一页”按钮加载，快照中确认0.500到0.900秒五行。
    page2 = (ROOT / 'output/playwright/T13/upload-page2.txt').read_text(encoding='utf-8-sig')
    assert '"page": 2' in page2.replace('\\"', '"')
    for stamp in ['00.500', '00.600', '00.700', '00.800', '00.900']:
        assert stamp in page2, '第二页缺少 ' + stamp
    assert 'Errors: 0, Warnings: 0' in (ROOT / 'output/playwright/T13/console.txt').read_text(encoding='utf-8-sig')
    output = {'passed': True, 'files': records, 'secondPageChecked': True,
              'backendJarSha256': sha(ROOT / 'verification/runtime/T13/space-target-visible-T13.jar'),
              'buildJarSha256': sha(ROOT / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar')}
    assert output['backendJarSha256'] == output['buildJarSha256'], '运行包不是最终构建包'
    (ROOT / 'verification/T13/browser-file-compare.json').write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(output, ensure_ascii=False))


if __name__ == '__main__':
    main()
