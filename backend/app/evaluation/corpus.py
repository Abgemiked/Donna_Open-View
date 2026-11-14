"""Versioned synthetic Donna corpus. No network or production configuration."""
from __future__ import annotations
import json
from collections import Counter, defaultdict
from pathlib import Path

SPLITS = ('train', 'validation', 'holdout')
DEFAULT_CORPUS = Path(__file__).resolve().parents[2] / 'tests/fixtures/eval_corpus_v1.json'

def load_cases(path=None, split=None):
    if split is not None and split not in SPLITS:
        raise ValueError('unknown_split')
    try:
        data = json.loads(Path(path or DEFAULT_CORPUS).read_text(encoding='utf-8-sig'))
    except (OSError, ValueError) as exc:
        raise ValueError('invalid_corpus_file') from exc
    if not isinstance(data, dict) or type(data.get('version')) is not int or data['version'] < 1:
        raise ValueError('invalid_version')
    cases = data.get('cases')
    if not isinstance(cases, list) or not cases:
        raise ValueError('empty_corpus')
    seen, families = set(), defaultdict(set)
    for c in cases:
        if not isinstance(c, dict): raise ValueError('invalid_case')
        for key in ('id','family','category'):
            if not isinstance(c.get(key), str) or not c[key].strip(): raise ValueError('invalid_'+key)
        if c['id'] in seen: raise ValueError('duplicate_id')
        seen.add(c['id'])
        if c.get('split') not in SPLITS: raise ValueError('invalid_split')
        families[c['family']].add(c['split'])
        if c.get('synthetic') is not True: raise ValueError('synthetic_required')
        if type(c.get('pilot')) is not bool: raise ValueError('invalid_pilot')
        if not isinstance(c.get('memory'), list) or any(not isinstance(v,str) for v in c['memory']):
            raise ValueError('invalid_memory')
        turns = c.get('turns')
        if not isinstance(turns, list) or not turns: raise ValueError('empty_turns')
        for t in turns:
            if not isinstance(t,dict) or any(not isinstance(t.get(k),str) or not t[k].strip() for k in ('user','expectation')):
                raise ValueError('invalid_turn')
        checks = c.get('assertions')
        if not isinstance(checks,dict): raise ValueError('invalid_assertions')
        for k in ('required_any','forbidden','expected_tools','forbidden_tools','allowed_tools'):
            if not isinstance(checks.get(k),list) or any(not isinstance(v,str) or not v for v in checks[k]):
                raise ValueError('invalid_assertion_'+k)
    if any(len(v) != 1 for v in families.values()): raise ValueError('family_split_leakage')
    return [c for c in cases if split is None or c['split'] == split]

def split_counts(path=None):
    counts = Counter(c['split'] for c in load_cases(path))
    return {s:counts[s] for s in SPLITS}

def pilot_cases(max_cases=10, path=None):
    if type(max_cases) is not int or not 1 <= max_cases <= 10: raise ValueError('invalid_pilot_limit')
    # Pilot is baseline/train only: never expose the holdout to iterative coaching.
    candidates = [c for c in load_cases(path, split='train') if c['pilot']]
    return candidates[:max_cases]
