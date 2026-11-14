"""Local, isolated coaching workflow. This module never changes production Donna.

Confirmation and review methods require an application/UI to obtain actual human
approval. A nonce is replay protection, not proof of a human identity.
"""
from __future__ import annotations
import hashlib
import hmac
import json
import secrets
import sqlite3
from pathlib import Path

REGRESSION_TYPES=('positive','paraphrase','counterexample','contradiction')
EVAL_GATES=REGRESSION_TYPES+('safety','baseline_regression')

class CoachStore:
    def __init__(self,path):
        self.path=Path(path);self.path.parent.mkdir(parents=True,exist_ok=True)
        with self._db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS lessons(id TEXT PRIMARY KEY,content TEXT NOT NULL,kind TEXT NOT NULL,scope TEXT NOT NULL,status TEXT NOT NULL,nonce_hash TEXT,supersedes TEXT,drafts TEXT,evaluation TEXT,evidence_id TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,active_ids TEXT NOT NULL)')
    def _db(self):
        db=sqlite3.connect(self.path);db.row_factory=sqlite3.Row;return db
    def get(self,lesson_id):
        with self._db() as db:row=db.execute('SELECT * FROM lessons WHERE id=?',(lesson_id,)).fetchone()
        if row is None:raise ValueError('unknown_lesson')
        result=dict(row);result.pop('nonce_hash',None);return result
    def create_candidate(self,content,kind,scope,supersedes=None):
        if not isinstance(content,str) or not content.strip() or len(content)>2000:raise ValueError('invalid_content')
        if kind not in ('fact','tendency'):raise ValueError('invalid_kind')
        if not isinstance(scope,str) or not scope.strip() or len(scope)>100 or any(x in scope.casefold() for x in ('security','override','safety','global')):raise ValueError('invalid_scope')
        lid=secrets.token_hex(12);nonce=secrets.token_urlsafe(24)
        with self._db() as db:
            if supersedes:
                old=db.execute('SELECT * FROM lessons WHERE id=?',(supersedes,)).fetchone()
                if not old or old['status']!='active' or old['kind']!=kind or old['scope']!=scope:raise ValueError('invalid_supersedes')
            db.execute('INSERT INTO lessons(id,content,kind,scope,status,nonce_hash,supersedes) VALUES(?,?,?,?,?,?,?)',(lid,content,kind,scope,'candidate',hashlib.sha256(nonce.encode()).hexdigest(),supersedes))
        return {'id':lid,'nonce':nonce,'status':'candidate'}
    def confirm(self,lesson_id,nonce):
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE');r=db.execute('SELECT status,nonce_hash FROM lessons WHERE id=?',(lesson_id,)).fetchone()
            if not r or r['status']!='candidate' or not isinstance(nonce,str) or not hmac.compare_digest(r['nonce_hash'] or '',hashlib.sha256(nonce.encode()).hexdigest()):raise ValueError('invalid_confirmation')
            db.execute("UPDATE lessons SET status='pending_eval',nonce_hash=NULL WHERE id=?",(lesson_id,))
        return self.get(lesson_id)
    def regression_drafts(self,lesson_id):
        r=self.get(lesson_id)
        if r['status']!='pending_eval':raise ValueError('confirmation_required')
        # Never copy personal lesson content into exported regression drafts.
        drafts=[{'type':kind,'status':'unreviewed','synthetic':True,'context':'<SYNTHETIC_LESSON>','user':'<REVIEW_REQUIRED>','expectation':'<REVIEW_REQUIRED>'} for kind in REGRESSION_TYPES]
        with self._db() as db:db.execute('UPDATE lessons SET drafts=?,evaluation=NULL,evidence_id=NULL WHERE id=?',(json.dumps(drafts),lesson_id))
        return drafts
    def approve_drafts(self,lesson_id,reviewer,examples=None):
        r=self.get(lesson_id)
        if r['status']!='pending_eval' or not r['drafts'] or not isinstance(reviewer,str) or not reviewer.strip():raise ValueError('draft_review_required')
        if not isinstance(examples,list) or len(examples)!=4 or {x.get('type') for x in examples if isinstance(x,dict)}!=set(REGRESSION_TYPES):raise ValueError('complete_synthetic_examples_required')
        reviewed=[]
        for e in examples:
            if e.get('synthetic') is not True or any(not isinstance(e.get(k),str) or not e[k].strip() or '<' in e[k] for k in ('context','user','expectation')):raise ValueError('invalid_synthetic_example')
            reviewed.append({k:e[k] for k in ('type','synthetic','context','user','expectation')}|{'status':'reviewed','reviewer':reviewer})
        with self._db() as db:db.execute('UPDATE lessons SET drafts=?,evaluation=NULL,evidence_id=NULL WHERE id=?',(json.dumps(reviewed),lesson_id))
        return reviewed
    def record_evaluation(self,lesson_id,gates,evidence_id):
        r=self.get(lesson_id)
        if r['status']!='pending_eval' or not r['drafts'] or any(x['status']!='reviewed' for x in json.loads(r['drafts'])):raise ValueError('review_required')
        if not isinstance(gates,dict) or set(gates)!=set(EVAL_GATES) or any(type(v) is not bool for v in gates.values()):raise ValueError('invalid_gates')
        if not isinstance(evidence_id,str) or not evidence_id.strip():raise ValueError('evidence_required')
        with self._db() as db:db.execute('UPDATE lessons SET evaluation=?,evidence_id=? WHERE id=?',(json.dumps(gates),evidence_id,lesson_id))
    @staticmethod
    def _active(db):
        return [dict(r) for r in db.execute("SELECT id,content,kind,scope FROM lessons WHERE status='active' ORDER BY id")]
    @staticmethod
    def _commit(db):
        db.commit()
    def recover(self,memory):
        """Rebuild source+derived memory from the durable lesson database.

        Required on factory/startup and before further model use after failures.
        This is crash recovery, not a distributed transaction guarantee.
        """
        db=self._db()
        try:
            db.execute('BEGIN IMMEDIATE')
            lessons=self._active(db)
            memory.replace_revision(lessons)
            db.commit()
            return {'active_ids':[r['id'] for r in lessons]}
        finally:
            db.close()
    def activate(self,lesson_id,memory):
        self.recover(memory)
        db=self._db()
        try:
            db.execute('BEGIN IMMEDIATE');r=db.execute('SELECT * FROM lessons WHERE id=?',(lesson_id,)).fetchone()
            if not r or r['status']!='pending_eval' or not r['evaluation'] or not all(json.loads(r['evaluation']).values()):raise ValueError('evaluation_not_passed')
            if r['supersedes']:
                old=db.execute('SELECT status FROM lessons WHERE id=?',(r['supersedes'],)).fetchone()
                if not old or old['status']!='active':raise ValueError('stale_supersedes')
                db.execute("UPDATE lessons SET status='superseded' WHERE id=?",(r['supersedes'],))
            db.execute("UPDATE lessons SET status='active' WHERE id=?",(lesson_id,))
            memory.replace_revision(self._active(db))
            self._commit(db)
        except BaseException:
            db.rollback();db.close()
            # Even a commit failure after a successful memory rebuild restores
            # the last actually persisted revision. Recovery failure is fatal:
            # the caller must not continue to model invocation.
            self.recover(memory)
            raise
        finally:
            db.close()
        return self.get(lesson_id)
    def snapshot(self):
        sid=secrets.token_hex(12)
        with self._db() as db:db.execute('INSERT INTO snapshots VALUES(?,?)',(sid,json.dumps([x['id'] for x in self._active(db)])))
        return sid
    def rollback(self,snapshot,memory):
        self.recover(memory)
        db=self._db()
        try:
            db.execute('BEGIN IMMEDIATE');r=db.execute('SELECT active_ids FROM snapshots WHERE id=?',(snapshot,)).fetchone()
            if not r:raise ValueError('unknown_snapshot')
            ids=json.loads(r['active_ids'])
            db.execute("UPDATE lessons SET status='pending_eval',evaluation=NULL,evidence_id=NULL WHERE status='active'")
            for lid in ids:
                if db.execute("UPDATE lessons SET status='active' WHERE id=?",(lid,)).rowcount!=1:raise ValueError('invalid_snapshot')
            memory.replace_revision(self._active(db))
            self._commit(db)
        except BaseException:
            db.rollback();db.close();self.recover(memory);raise
        finally:
            db.close()
        return snapshot
