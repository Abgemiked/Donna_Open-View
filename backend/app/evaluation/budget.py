"""One atomic SQLite ledger across all pilot processes and judges."""
from decimal import Decimal
import json
from pathlib import Path
import sqlite3
import uuid


class BudgetStop(RuntimeError):
    pass


class BudgetLedger:
    def __init__(self, path, run_id, *, max_calls=50, max_tokens=100000, max_usd="10"):
        self.path = Path(path).resolve(); self.path.parent.mkdir(parents=True, exist_ok=True)
        self.run_id = run_id
        self.limits = (max_calls, max_tokens, str(Decimal(max_usd)))
        if type(max_calls) is not int or type(max_tokens) is not int or min(max_calls,max_tokens)<=0 or Decimal(max_usd)<=0:
            raise ValueError("invalid_limits")
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY,max_calls INTEGER,max_tokens INTEGER,max_usd TEXT,stopped INTEGER DEFAULT 0)")
            db.execute("CREATE TABLE IF NOT EXISTS calls(id TEXT PRIMARY KEY,run TEXT,tokens INTEGER,usd TEXT,state TEXT)")
            db.execute("INSERT OR IGNORE INTO runs(id,max_calls,max_tokens,max_usd) VALUES(?,?,?,?)",(run_id,*self.limits))
            row=db.execute("SELECT max_calls,max_tokens,max_usd FROM runs WHERE id=?",(run_id,)).fetchone()
            if row!=self.limits: raise BudgetStop("ledger_limits_mismatch")

    def connect(self):
        db=sqlite3.connect(self.path,timeout=15,isolation_level=None)
        db.execute("PRAGMA journal_mode=WAL")
        return db

    def reserve(self, input_bound, output_bound, *, input_price, output_price):
        try:
            prices=[Decimal(str(input_price)),Decimal(str(output_price))]
            if not all(p.is_finite() and p>0 for p in prices): raise ValueError()
        except Exception: raise BudgetStop("price_missing_or_invalid") from None
        if any(type(n) is not int or n<0 for n in (input_bound,output_bound)): raise BudgetStop("token_bound_invalid")
        tokens=input_bound+output_bound
        usd=(Decimal(input_bound)*prices[0]+Decimal(output_bound)*prices[1])/Decimal(1000000)
        cid=uuid.uuid4().hex
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT stopped,max_calls,max_tokens,max_usd FROM runs WHERE id=?",(self.run_id,)).fetchone()
            calls=db.execute("SELECT tokens,usd FROM calls WHERE run=?",(self.run_id,)).fetchall()
            if row[0] or len(calls)+1>row[1] or sum(r[0] for r in calls)+tokens>row[2] or sum((Decimal(r[1]) for r in calls),Decimal(0))+usd>Decimal(row[3]):
                db.rollback();raise BudgetStop("budget_exhausted_or_stopped")
            db.execute("INSERT INTO calls VALUES(?,?,?,?,?)",(cid,self.run_id,tokens,str(usd),"reserved"));db.commit()
        return cid

    def settle(self, cid, *, input_tokens, output_tokens, input_price, output_price):
        if any(type(n) is not int or n<0 for n in (input_tokens,output_tokens)):
            self.stop();raise BudgetStop("unknown_usage")
        tokens=input_tokens+output_tokens
        usd=(Decimal(input_tokens)*Decimal(str(input_price))+Decimal(output_tokens)*Decimal(str(output_price)))/Decimal(1000000)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT tokens,usd,state FROM calls WHERE id=? AND run=?",(cid,self.run_id)).fetchone()
            if not row or row[2]!="reserved":db.rollback();raise BudgetStop("reservation_missing")
            db.execute("UPDATE calls SET tokens=?,usd=?,state='settled' WHERE id=?",(tokens,str(usd),cid))
            exceeded=tokens>row[0] or usd>Decimal(row[1])
            if exceeded:db.execute("UPDATE runs SET stopped=1 WHERE id=?",(self.run_id,))
            db.commit()
        if exceeded:raise BudgetStop("actual_exceeded_reservation")

    def stop(self):
        with self.connect() as db:db.execute("UPDATE runs SET stopped=1 WHERE id=?",(self.run_id,))

    def summary(self):
        with self.connect() as db:
            rows=db.execute("SELECT tokens,usd,state FROM calls WHERE run=?",(self.run_id,)).fetchall()
            stopped=db.execute("SELECT stopped FROM runs WHERE id=?",(self.run_id,)).fetchone()[0]
        return {"calls":len(rows),"accounted_tokens":sum(r[0] for r in rows),"accounted_usd":str(sum((Decimal(r[1]) for r in rows),Decimal(0))),"unsettled":sum(r[2]=="reserved" for r in rows),"stopped":bool(stopped)}
