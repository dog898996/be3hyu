#!/usr/bin/env python3
"""Compare actual blinded final packets exactly; never read expected answers or normalize evidence."""
from pathlib import Path
import argparse, hashlib, json

p=argparse.ArgumentParser();p.add_argument('--before',required=True);p.add_argument('--after',required=True);p.add_argument('--output',required=True);a=p.parse_args()
before=Path(a.before).resolve();after=Path(a.after).resolve();out=Path(a.output).resolve()
if out.exists():raise SystemExit('Choose a fresh comparison directory; prior receipts are retained')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def text_sha(value):return hashlib.sha256(value.encode()).hexdigest()
def load(run):
 file=run/'final-reader-inputs.jsonl'
 if not file.is_file():file=run/'reader-inputs.jsonl'
 packets=[json.loads(line) for line in file.read_text().splitlines() if line.strip()]
 packets=[row for row in packets if row['date']=='2026-12-31']
 if len(packets)!=144 or len({row['id'] for row in packets})!=144:raise SystemExit('Exactly 144 distinct actual final packets are required: '+str(run))
 return {row['id']:row for row in packets},file
def paths_changed(left,right,path=''):
 if isinstance(left,dict) and isinstance(right,dict):
  return sum((paths_changed(left.get(k),right.get(k),path+'.'+k) for k in sorted(set(left)|set(right))),[])
 return [] if left==right else [{'path':path,'before':left,'after':right}]
def footer_diff(left,right):
 la=left.rsplit('MEMORY_LIMITS ',1);ra=right.rsplit('MEMORY_LIMITS ',1)
 if len(la)!=2 or len(ra)!=2:return None
 try:
  old=json.loads(la[1].replace('\\n','\n'));new=json.loads(ra[1].replace('\\n','\n'))
 except json.JSONDecodeError:return None
 return {'allBytesBeforeFooterExactlyEqual':la[0]==ra[0],'footerChangedPaths':paths_changed(old,new,'MEMORY_LIMITS')}
old,old_file=load(before);new,new_file=load(after)
if set(old)!=set(new):raise SystemExit('Different opaque question sets must not be called a paired comparison')
changed=[];other_changed=[]
for key,row in old.items():
 if {k:v for k,v in row.items() if k!='evidence'}!={k:v for k,v in new[key].items() if k!='evidence'}:other_changed.append(key)
 if row['evidence']!=new[key]['evidence']:
  diff={'id':key,'beforeContextSha256':text_sha(row['evidence']),'afterContextSha256':text_sha(new[key]['evidence']),'beforeContextChars':len(row['evidence']),'afterContextChars':len(new[key]['evidence'])}
  footer=footer_diff(row['evidence'],new[key]['evidence'])
  if footer is not None:diff.update(footer)
  changed.append(diff)
maps_match=None
if (before/'source-map.json').is_file() and (after/'source-map.json').is_file():maps_match=json.loads((before/'source-map.json').read_text())==json.loads((after/'source-map.json').read_text())
receipt={'format':'exact-reader-packet-equivalence-v2','beforeRun':str(before),'afterRun':str(after),'beforePacketsSha256':sha(old_file),'afterPacketsSha256':sha(new_file),'beforeProductionManifestSha256':sha(before/'source-manifest.json'),'afterProductionManifestSha256':sha(after/'source-manifest.json'),'packetsCompared':144,'exactUnchangedEvidenceContexts':144-len(changed),'changedEvidenceContexts':changed,'changedNonEvidencePacketIds':other_changed,'allCanonicalSourceMappingsAndHashesExactlyEqual':maps_match,'normalizationsApplied':[],'semanticAnswerScoreAssigned':False,'followupResponseEquivalence':'not measured by packet comparator','answerCarryForwardRule':'Changed evidence requires explicit independent review or a new source-grounded answer; unchanged primary bytes do not automatically establish follow-up tool-response equivalence.'}
out.mkdir(parents=True);(out/'comparison.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
changed_ids={c['id'] for c in changed}|set(other_changed)
(out/'changed-reader-inputs.jsonl').write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in new.values() if row['id'] in changed_ids))
print(json.dumps({'compared':144,'exactEqual':144-len(changed),'changedCount':len(changed_ids),'changedIdsFirstTen':sorted(changed_ids)[:10],'comparison':str(out/'comparison.json')}))
