"""Like-for-like regression comparisons. No paid calls or model learning."""
from __future__ import annotations
import json
import math
from pathlib import Path
from statistics import mean

ARTIFACT_REVISIONS={'prompt_revision','lesson_revision','code_revision'}

def _validate(run):
    if not isinstance(run,dict) or run.get('complete') is not True:raise ValueError('incomplete_run')
    manifest=run.get('manifest')
    if not isinstance(manifest,dict) or not all(manifest.get(k) for k in ('corpus','model','rubric','split','case_ids')):raise ValueError('missing_manifest')
    if manifest['split'] not in ('train','validation','holdout'):raise ValueError('invalid_split')
    ids=manifest['case_ids']
    if not isinstance(ids,list) or any(not isinstance(i,str) or not i for i in ids) or len(set(ids))!=len(ids):raise ValueError('invalid_expected_ids')
    cases=run.get('cases')
    if not isinstance(cases,list) or not cases:raise ValueError('missing_cases')
    seen=set();dims=None
    for c in cases:
        if not isinstance(c,dict) or c.get('id') in seen or c.get('id') not in ids:raise ValueError('duplicate_or_unknown_case')
        seen.add(c['id'])
        if c.get('status') not in ('PASS','FAIL'):raise ValueError('unresolved_case')
        if not isinstance(c.get('category','general'),str) or not c.get('category','general'):raise ValueError('invalid_category')
        scores=c.get('scores')
        if not isinstance(scores,dict) or not scores or any(type(v) not in (int,float) or not math.isfinite(v) or not 1<=v<=5 for v in scores.values()):raise ValueError('invalid_scores')
        if dims is None:dims=set(scores)
        elif set(scores)!=dims:raise ValueError('score_dimension_mismatch')
        if not isinstance(c.get('critical_failures'),list) or any(not isinstance(x,str) for x in c['critical_failures']):raise ValueError('invalid_critical_failures')
    if seen!=set(ids):raise ValueError('missing_cases')
    return manifest,cases,dims

def compare_runs(baseline,candidate,tolerances,purpose='release'):
    if purpose not in ('release','tuning'):raise ValueError('invalid_purpose')
    try:b,bc,bd=_validate(baseline);c,cc,cd=_validate(candidate)
    except ValueError as exc:return {'status':'INVALID','codes':[str(exc)]}
    bconditions={k:v for k,v in b.items() if k not in ARTIFACT_REVISIONS}
    cconditions={k:v for k,v in c.items() if k not in ARTIFACT_REVISIONS}
    if bconditions!=cconditions or bd!=cd:return {'status':'INVALID','codes':['incomparable_conditions']}
    if purpose=='tuning' and c['split']=='holdout':return {'status':'INVALID','codes':['holdout_not_for_tuning']}
    if not isinstance(tolerances,dict) or set(tolerances)!=bd or any(type(v) not in (int,float) or not math.isfinite(v) or v<0 for v in tolerances.values()):raise ValueError('explicit_tolerances_required')
    deltas={k:mean(x['scores'][k] for x in cc)-mean(x['scores'][k] for x in bc) for k in sorted(bd)}
    codes=[]
    category_deltas={}
    baseline_by_id={x['id']:x for x in bc}
    if any(x.get('category','general')!=baseline_by_id[x['id']].get('category','general') for x in cc):return {'status':'INVALID','codes':['category_mismatch']}
    for category in sorted({x.get('category','general') for x in cc}):
        cb=[x for x in bc if x.get('category','general')==category]
        ca=[x for x in cc if x.get('category','general')==category]
        category_deltas[category]={k:mean(x['scores'][k] for x in ca)-mean(x['scores'][k] for x in cb) for k in sorted(bd)}
    if any(delta < -tolerances[k] for ds in category_deltas.values() for k,delta in ds.items()):codes.append('category_score_regression')
    if purpose=='release' and any(x['status']!='PASS' for x in cc):codes.append('candidate_case_failed')
    if any(x['critical_failures'] for x in cc):codes.append('critical_gate_failed')
    # Improvements elsewhere cannot average away a newly broken case.
    bstatus={x['id']:x['status'] for x in bc}
    if any(x['status']=='FAIL' and bstatus[x['id']]=='PASS' for x in cc):codes.append('case_regression')
    if any(delta < -tolerances[k] for k,delta in deltas.items()):codes.append('score_regression')
    return {'status':'FAIL' if codes else 'PASS','codes':codes,'score_deltas':deltas,'category_deltas':category_deltas,'case_count':len(cc),'manual_review_required':True,'limits':'Small synthetic samples do not prove general improvement.'}

def record_trend(path,comparison,run_id):
    if not isinstance(run_id,str) or not run_id or comparison.get('status') not in ('PASS','FAIL','INVALID'):raise ValueError('invalid_trend')
    # Persist only measured summaries, never response text or judge rationale.
    record={'run_id':run_id,'status':comparison['status'],'codes':comparison.get('codes',[]),'score_deltas':comparison.get('score_deltas',{}),'category_deltas':comparison.get('category_deltas',{}),'case_count':comparison.get('case_count',0)}
    p=Path(path);p.parent.mkdir(parents=True,exist_ok=True)
    with p.open('a',encoding='utf-8') as f:f.write(json.dumps(record,sort_keys=True)+'\n')
    return record
