"""Chrome CLI本轮证据驱动；命令stdout原字节保存，结果对象单独从同一真实页面读取。"""
from pathlib import Path
import json,os,subprocess,sys
if os.environ.get('A01_FINAL'):
    from acceptance_final import ROOT,OUT,RUN,execute,save
else:
    from acceptance import ROOT,OUT,RUN,execute,save
SESSION='a01-final' if os.environ.get('A01_FINAL') else 'a01-acceptance'
PREFIX=['npx.cmd','--yes','--package','@playwright/cli','playwright-cli','-s='+SESSION]

def cli(name,args):
    execute('chrome-'+name,PREFIX+args)

if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='open':cli('open',['open','http://127.0.0.1:15140','--browser','chrome'])
    elif mode=='reset':cli('reset-'+sys.argv[2],['goto','http://127.0.0.1:15140'])
    elif mode=='snapshot':cli('snapshot-'+sys.argv[2],['snapshot'])
    elif mode=='run':
        name=sys.argv[2];cli(name,['run-code','--filename='+str(OUT/(name+'-browser-flow.js'))])
    elif mode=='upload':cli('upload-'+sys.argv[2],['upload',sys.argv[3]])
    elif mode=='result':
        name,field=sys.argv[2:];cli(name+'-result',['run-code','async (page) => page.'+field])
    elif mode=='close':cli('close',['close'])
