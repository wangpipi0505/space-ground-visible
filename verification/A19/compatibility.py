"""只转换 Vue 2 不自动归一化的自定义事件名；保持处理函数和事件参数。"""
from pathlib import Path
root = Path(__file__).resolve().parents[2] / 'frontend/src'
events = ['cancelTask', 'pageChange', 'selectPlanned', 'stationsChange', 'toggleLink', 'validateCustom']
import re
for file in root.rglob('*.vue'):
    text = file.read_text(encoding='utf-8')
    for event in events:
        kebab = re.sub(r'[A-Z]', lambda m: '-' + m[0].lower(), event)
        text = text.replace('@' + kebab + '=', '@' + event + '=')
    file.write_text(text, encoding='utf-8')
