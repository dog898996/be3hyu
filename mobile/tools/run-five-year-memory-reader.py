#!/usr/bin/env python3
"""Run model-requested native source tools against an exact simulation snapshot. No gold access."""
from pathlib import Path
import argparse, fcntl, hashlib, json, os, shlex, subprocess, urllib.parse
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--requests',required=True);p.add_argument('--output',required=True);p.add_argument('--tools-root',default='/workspace/.tools/android-build');a=p.parse_args()
run=Path(a.run).resolve();output=Path(a.output).resolve();requests=Path(a.requests).resolve()
if output.exists():raise SystemExit('Refusing to overwrite previous reader tool responses')
if not (run/'native-reader-control-snapshot.json').is_file():raise SystemExit('Run lacks exact native reader controls; do not reconstruct privacy from a gold matrix. Rerun frozen production snapshot with state export.')
repo=Path(__file__).resolve().parents[2];jdk=Path(a.tools_root)/'jdk-17.0.16+8/bin'
javac=shlex.split((run/'javac.args').read_text());classpath=javac[javac.index('-cp')+1]
helper=repo/'mobile/tools/longitudinal/FiveYearNativeReader.java';helperclasses=output.parent/(output.name+'.classes');helperclasses.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join([str(run/'classes'),classpath]);subprocess.run([str(jdk/'javac'),'-source','17','-target','17','-cp',cp,'-d',str(helperclasses),str(helper)],check=True,cwd=repo)
budget=run/'native-reader-budget.json';env=dict(os.environ,HOYOUNG_READER_RUN=str(run),HOYOUNG_READER_REQUESTS=str(requests),HOYOUNG_READER_OUTPUT=str(output),HOYOUNG_READER_BUDGET=str(budget))
command=[str(jdk/'java'),'-Xmx1536m','-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
for scheme in ('http','https'):
 proxy=os.environ.get(scheme.upper()+'_PROXY') or os.environ.get(scheme+'_proxy')
 if proxy:
  parsed=urllib.parse.urlparse(proxy)
  if parsed.hostname:command+=['-D'+scheme+'.proxyHost='+parsed.hostname,'-D'+scheme+'.proxyPort='+str(parsed.port or 80)]
command+=['-cp',os.pathsep.join([str(helperclasses),cp]),'org.junit.runner.JUnitCore','com.hoyoung.assistant.memory.FiveYearNativeReader']
log=output.with_name(output.name+'.execution.log')
with (run/'native-reader-budget.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 before=hashlib.sha256(budget.read_bytes()).hexdigest() if budget.exists() else None
 with log.open('w') as handle:result=subprocess.run(command,env=env,stdout=handle,stderr=subprocess.STDOUT,cwd=repo)
 after=hashlib.sha256(budget.read_bytes()).hexdigest() if budget.exists() else None
receipt={'run':str(run),'productionSourceManifestSha256':hashlib.sha256((run/'source-manifest.json').read_bytes()).hexdigest(),'helperSha256':hashlib.sha256(helper.read_bytes()).hexdigest(),'requestSha256':hashlib.sha256(requests.read_bytes()).hexdigest(),'responseSha256':hashlib.sha256(output.read_bytes()).hexdigest() if output.is_file() else None,'budgetBeforeSha256':before,'budgetAfterSha256':after,'exitCode':result.returncode,'scope':'Actual native source tools only; no gold or answer rubric read by helper. Search/row budgets persist across invocations for each packet.'}
output.with_name(output.name+'.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(log.read_text()[-3000:]);raise SystemExit(result.returncode)
