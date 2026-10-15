import subprocess,json,hashlib,datetime,pathlib,sys
root=pathlib.Path(__file__).resolve().parents[4]
phase=sys.argv[1]
cwd=root/'verification/runtime/A07/fit-ui'
args=['C:/Program Files/nodejs/node.exe',str(root/'frontend/node_modules/vitest/vitest.mjs'),'run','--root',str(root/'frontend'),'--config',str(root/'frontend/vitest.config.ts'),'src/views/BallisticFitView.spec.ts','src/views/BallisticSimulationView.spec.ts','src/views/BallisticRetimeView.spec.ts']
start=datetime.datetime.now(datetime.timezone.utc).isoformat(); r=subprocess.run(args,cwd=cwd,capture_output=True)
e=root/'verification/A07/implementation/fit-ui'
(e/(phase+'.stdout.log')).write_bytes(r.stdout);(e/(phase+'.stderr.log')).write_bytes(r.stderr)
files={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for name in ['BallisticFitView','BallisticSimulationView','BallisticRetimeView'] for p in (root/'frontend/src/views').glob(name+'.*')}
p=root/'frontend/src/components/BallisticFitFileResult.vue';files[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
(e/(phase+'.json')).write_text(json.dumps(dict(startUtc=start,endUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),argv=args,cwd=str(cwd),exitCode=r.returncode,sha256=files,stdoutSha256=hashlib.sha256(r.stdout).hexdigest(),stderrSha256=hashlib.sha256(r.stderr).hexdigest()),ensure_ascii=False,indent=2),encoding='utf-8')
print('EXIT',r.returncode)
