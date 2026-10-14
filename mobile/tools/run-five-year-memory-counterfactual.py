#!/usr/bin/env python3
"""Tag disposable source interventions and run frozen native production code. No gold access."""
from pathlib import Path
import argparse, hashlib, json, os, shlex, shutil, subprocess, urllib.parse
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--request',required=True);p.add_argument('--output',required=True);p.add_argument('--tools-root',default='/workspace/.tools/android-build');a=p.parse_args()
run=Path(a.run).resolve();request=Path(a.request).resolve();out=Path(a.output).resolve()
if out.exists():raise SystemExit('Choose a new tagged output directory; existing interventions are retained')
for name in ['canonical-corpus.sqlite','native-reader-control-snapshot.json','final-reader-inputs.jsonl','source-manifest.json','javac.args']:
 if not (run/name).is_file():raise SystemExit('Frozen native replay state required: '+name)
config=json.loads(request.read_text());tags=[v['tag'] for v in config['variants']]
if len(tags)!=len(set(tags)) or any(not t.replace('-','').replace('_','').isalnum() or len(t)>40 for t in tags):raise SystemExit('Unique bounded alphanumeric tags required')
out.mkdir(parents=True);shutil.copyfile(request,out/'scorer-only-request.json')
for tag in tags:
 variant=out/tag;variant.mkdir()
 for name in ['source-manifest.json','javac.args']:shutil.copyfile(run/name,variant/name)
 (variant/'classes').symlink_to(run/'classes',target_is_directory=True)
repo=Path(__file__).resolve().parents[2];jdk=Path(a.tools_root)/'jdk-17.0.16+8/bin';javac=shlex.split((run/'javac.args').read_text());cp=os.pathsep.join([str(run/'classes'),javac[javac.index('-cp')+1]])
helper=repo/'mobile/tools/longitudinal/FiveYearNativeCounterfactual.java';classes=out/'helper.classes';classes.mkdir()
subprocess.run([str(jdk/'javac'),'-source','17','-target','17','-cp',cp,'-d',str(classes),str(helper)],check=True,cwd=repo)
command=[str(jdk/'java'),'-Xmx1536m','-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
for scheme in ('http','https'):
 proxy=os.environ.get(scheme.upper()+'_PROXY') or os.environ.get(scheme+'_proxy')
 if proxy:
  u=urllib.parse.urlparse(proxy)
  if u.hostname:command+=['-D'+scheme+'.proxyHost='+u.hostname,'-D'+scheme+'.proxyPort='+str(u.port or 80)]
command+=['-cp',os.pathsep.join([str(classes),cp]),'org.junit.runner.JUnitCore','com.hoyoung.assistant.memory.FiveYearNativeCounterfactual']
env=dict(os.environ,HOYOUNG_COUNTERFACTUAL_RUN=str(run),HOYOUNG_COUNTERFACTUAL_REQUEST=str(request),HOYOUNG_COUNTERFACTUAL_OUTPUT=str(out))
with (out/'execution.log').open('w') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,cwd=repo)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
receipt={'run':str(run),'originalCanonicalSnapshotSha256':sha(run/'canonical-corpus.sqlite'),'productionSourceManifestSha256':sha(run/'source-manifest.json'),'helperSha256':sha(helper),'requestSha256':sha(request),'readerPacketsSha256':sha(out/'reader-inputs.jsonl') if (out/'reader-inputs.jsonl').is_file() else None,'exitCode':r.returncode,'scope':'Actual native source/context interventions only; no gold, generated expected answer or semantic model-answer score','groundedAnswerQuality':'not measured','paidApiCalls':0}
(out/'execution-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print((out/'execution.log').read_text()[-4000:]);raise SystemExit(r.returncode)
