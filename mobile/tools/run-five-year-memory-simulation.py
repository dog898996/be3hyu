#!/usr/bin/env python3
"""No Gradle or paid API. Compile frozen production source and run native SQLite Robolectric.
Requires the Android/JDK/Gradle dependency cache already provisioned by the build owner.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, urllib.parse
p=argparse.ArgumentParser();p.add_argument('--source-ref',default='f2e67c6fced3db82b1950c7c9382e57094fd2b6e');p.add_argument('--source-snapshot',help='Reuse a prior run\'s exact production source manifest, with the current fixture');p.add_argument('--fixture',help='Explicit fixture file; its exact bytes and origin are retained in the run manifest');p.add_argument('--output',required=True);p.add_argument('--notes-file');p.add_argument('--gateway',action='store_true');p.add_argument('--privacy-controls',action='store_true');p.add_argument('--physical-delete',action='store_true');p.add_argument('--compile-only',action='store_true');p.add_argument('--exclude-source',action='append',default=[]);p.add_argument('--tools-root',default='/workspace/.tools/android-build');a=p.parse_args()
repo=Path(__file__).resolve().parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
if (out/'report.json').exists():raise SystemExit('Output already contains a measured run; choose a fresh directory to retain source/result provenance.')
tools=Path(a.tools_root);jdk=tools/'jdk-17.0.16+8/bin';android=tools/'sdk/platforms/android-35/android.jar';cache=tools/'gradle-cache/caches'
jars=list((cache/'modules-2/files-2.1').rglob('*.jar'))+[q for q in (cache/'8.11.1/transforms').rglob('*.jar') if q.name.endswith('-api.jar') or q.name=='classes.jar']
if not android.is_file() or not jars:raise SystemExit('Provisioned Android SDK / dependency cache required; this script does not install or use paid services.')
source=out/'source';classes=out/'classes';source.mkdir(exist_ok=True);classes.mkdir(exist_ok=True)
packages=['mobile/app/src/main/java/com/hoyoung/assistant/store','mobile/app/src/main/java/com/hoyoung/assistant/memory']
snapshot=Path(a.source_snapshot).resolve() if a.source_snapshot else None
prior=json.loads((snapshot/'source-manifest.json').read_text()) if snapshot else None
if snapshot:
 paths=sorted(q for q in prior['sources'] if q.endswith('.java'))
 a.source_ref=prior['ref'];a.exclude_source=list(dict.fromkeys(prior.get('excludedProductionFiles',[])+a.exclude_source))
elif a.source_ref=='WORKTREE':
 paths=sorted(str(q.relative_to(repo)) for pkg in packages for q in (repo/pkg).rglob('*.java'))
else:
 paths=subprocess.check_output(['git','ls-tree','-r','--name-only',a.source_ref,*packages],cwd=repo,text=True).splitlines()
paths=[q for q in paths if Path(q).name not in set(a.exclude_source)]
def production_bytes(relative):
 if snapshot:
  raw=(snapshot/'source'/relative).read_bytes()
  if hashlib.sha256(raw).hexdigest()!=prior['sources'][relative]:raise SystemExit('Frozen production source SHA mismatch: '+relative)
  return raw
 return (repo/relative).read_bytes() if a.source_ref=='WORKTREE' else subprocess.check_output(['git','show',a.source_ref+':'+relative],cwd=repo)
gateway_path='mobile/app/src/main/java/com/hoyoung/assistant/store/MemoryGateway.java'
goal_path='mobile/app/src/main/java/com/hoyoung/assistant/runtime/GoalLedger.java'
if gateway_path in paths and b'import com.hoyoung.assistant.runtime.GoalLedger;' in production_bytes(gateway_path) and goal_path not in paths:
 paths.append(goal_path)
action_path='mobile/app/src/main/java/com/hoyoung/assistant/runtime/ActionReceiptJournal.java'
if goal_path in paths and b'new ActionReceiptJournal(' in production_bytes(goal_path) and action_path not in paths:
 paths.append(action_path)
manifest={}
for relative in paths:
 if not relative.endswith('.java'):continue
 raw=production_bytes(relative);target=source/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw);manifest[relative]=hashlib.sha256(raw).hexdigest()
fixtureOrigin=Path(a.fixture).resolve() if a.fixture else repo/'mobile/tools/longitudinal/FiveYearMemorySimulation.java';fixture=source/'benchmark/FiveYearMemorySimulation.java';fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_bytes(fixtureOrigin.read_bytes());manifest['benchmarkFixture']=hashlib.sha256(fixture.read_bytes()).hexdigest()
matrix=repo/'docs/benchmarks/five-year-memory-gold-matrix.json';matrixsha=hashlib.sha256(matrix.read_bytes()).hexdigest()
(out/'source-manifest.json').write_text(json.dumps({'ref':a.source_ref,'sources':manifest,'excludedProductionFiles':a.exclude_source,'reusedProductionSnapshot':str(snapshot) if snapshot else None,'fixtureOrigin':str(fixtureOrigin),'matrixSha256':matrixsha},indent=2)+'\n')
cpcompile=os.pathsep.join([str(android)]+[str(q) for q in jars]);args=['-source','17','-target','17','-cp',cpcompile,'-d',str(classes)]+[str(source/q) for q in paths if q.endswith('.java')]+[str(fixture)]
argfile=out/'javac.args';argfile.write_text('\n'.join('"'+x.replace('\\','\\\\').replace('"','\\"')+'"' for x in args))
subprocess.run([str(jdk/'javac'),'@'+str(argfile)],check=True,cwd=repo)
if a.compile_only:
 print('Frozen production and benchmark fixture compiled:',out/'source-manifest.json');raise SystemExit(0)
run=[str(jdk/'java'),'-Xmx1536m','-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2']
for scheme in ('http','https'):
 proxy=os.environ.get(scheme.upper()+'_PROXY') or os.environ.get(scheme+'_proxy')
 if proxy:
  u=urllib.parse.urlparse(proxy)
  if u.hostname:run += ['-D'+scheme+'.proxyHost='+u.hostname,'-D'+scheme+'.proxyPort='+str(u.port or 80)]
run+=['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts','-classpath',os.pathsep.join([str(classes)]+[str(q) for q in jars]+[str(android)]),'org.junit.runner.JUnitCore','com.hoyoung.assistant.memory.FiveYearMemorySimulation']
env=dict(os.environ,HOYOUNG_FIVE_YEAR_MATRIX=str(matrix),HOYOUNG_FIVE_YEAR_MATRIX_SHA=matrixsha,HOYOUNG_FIVE_YEAR_OUTPUT=str(out),HOYOUNG_FIVE_YEAR_SOURCE_REF=a.source_ref)
if a.gateway:env['HOYOUNG_FIVE_YEAR_GATEWAY']='1'
if a.privacy_controls or a.gateway or a.physical_delete:env['HOYOUNG_FIVE_YEAR_PRIVACY_CONTROLS']='1'
if a.physical_delete:env['HOYOUNG_FIVE_YEAR_PHYSICAL_DELETE']='1'
if a.notes_file:
 env['HOYOUNG_FIVE_YEAR_NOTES']=str(Path(a.notes_file).resolve())
with (out/'execution.log').open('w') as log:result=subprocess.run(run,env=env,stdout=log,stderr=subprocess.STDOUT,cwd=repo)
print((out/'execution.log').read_text()[-10000:]);raise SystemExit(result.returncode)
