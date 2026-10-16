"""Gateway-to-CEO authenticated control boundary. NOT a Telegram transport client."""
import argparse, hashlib, hmac, json, os, re, sqlite3, sys, time
from pathlib import Path

COMMANDS={"status","report","pause","resume","run"}
FIELDS={"update_id","chat_id","user_id","issued_at","command"}
class AuthenticationError(ValueError): pass

def canonical(envelope):
    if not isinstance(envelope,dict) or set(envelope)!=FIELDS:raise AuthenticationError("Invalid schema")
    if any(not isinstance(envelope[k],str) for k in ("update_id","chat_id","user_id","command")) or type(envelope["issued_at"]) is not int:
        raise AuthenticationError("Invalid transport fields")
    if not re.fullmatch(r"[0-9]{1,22}",envelope["update_id"]) or not re.fullmatch(r"-?[0-9]{1,22}",envelope["chat_id"]) or not re.fullmatch(r"[0-9]{1,22}",envelope["user_id"]):
        raise AuthenticationError("Invalid IDs")
    if envelope["command"] not in COMMANDS:raise AuthenticationError("Invalid command")
    return json.dumps(envelope,sort_keys=True,separators=(",",":"),ensure_ascii=True).encode()

def sign_trusted_gateway_envelope(envelope,secret):
    """Only trusted gateway or tests may call; never expose signer to any model."""
    if not isinstance(secret,bytes) or len(secret)<32:raise AuthenticationError("Weak secret")
    return hmac.new(secret,canonical(envelope),hashlib.sha256).hexdigest()

def latest_report(folder):
    folder=Path(folder)
    if not folder.is_dir():return None
    for path in sorted(folder.glob("ceo-????-??-??.json"),reverse=True)[:5]:
        if path.is_symlink() or not path.is_file() or path.stat().st_size>1048576:continue
        try:
            data=json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data,dict) or data.get("status")!="completed_draft_cycle":continue
            states=[v.get("state") for v in data.get("tasks",[]) if isinstance(v,dict)]
            return {"run_id":data.get("run_id"),"status":"draft_only",
                    "reviewed_drafts":states.count("reviewed_draft"),
                    "needs_revision":states.count("revision_required"),
                    "recorded_revenue_minor":data.get("approved_commissions_minor"),
                    "currency":data.get("currency")}
        except (OSError,ValueError,TypeError):pass
    return None

class GatewayControl:
    def __init__(self,db_path,report_dir,*,owner_user_id,owner_chat_id,secret,clock=None):
        if not re.fullmatch(r"[0-9]{1,22}",str(owner_user_id)) or not re.fullmatch(r"-?[0-9]{1,22}",str(owner_chat_id)):
            raise AuthenticationError("Invalid owner IDs")
        if not isinstance(secret,bytes) or len(secret)<32:raise AuthenticationError("Weak signing key")
        self.owner_user_id,self.owner_chat_id=str(owner_user_id),str(owner_chat_id)
        self.secret,self.clock,self.report_dir=secret,clock or time.time,Path(report_dir)
        path=Path(db_path);path.parent.mkdir(parents=True,exist_ok=True)
        self.db=sqlite3.connect(str(path),timeout=15,isolation_level=None)
        self.db.execute("PRAGMA busy_timeout=15000")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS control_state (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS received_updates (update_id TEXT PRIMARY KEY,command TEXT NOT NULL,processed_at INTEGER NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS requested_cycles (update_id TEXT PRIMARY KEY REFERENCES received_updates(update_id),requested_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('queued','claimed','done','failed')))")
        self.db.execute("INSERT OR IGNORE INTO control_state VALUES ('paused','1')")
    def close(self):self.db.close()
    def _status(self):
        paused=self.db.execute("SELECT value FROM control_state WHERE key='paused'").fetchone()[0]=="1"
        n=self.db.execute("SELECT COUNT(*) FROM requested_cycles WHERE state='queued'").fetchone()[0]
        return {"paused":paused,"queued_cycle_requests":n,"agent_execution":"not_connected","commercial_execution":"disabled"}
    def handle(self,envelope,signature):
        data=canonical(envelope)
        if not isinstance(signature,str) or not re.fullmatch(r"[a-fA-F0-9]{64}",signature):raise AuthenticationError("Invalid signature")
        expected=hmac.new(self.secret,data,hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature.lower(),expected):raise AuthenticationError("Signature mismatch")
        if envelope["user_id"]!=self.owner_user_id or envelope["chat_id"]!=self.owner_chat_id:
            raise AuthenticationError("Unauthorized transport user/chat")
        now=int(self.clock())
        if abs(now-envelope["issued_at"])>300:raise AuthenticationError("Old or future-dated update")
        command,update_id=envelope["command"],envelope["update_id"]
        try:
            self.db.execute("BEGIN IMMEDIATE")
            if self.db.execute("SELECT 1 FROM received_updates WHERE update_id=?",(update_id,)).fetchone():
                self.db.execute("ROLLBACK")
                return {"ok":False,"reason":"duplicate_update",**self._status()}
            self.db.execute("INSERT INTO received_updates VALUES (?,?,?)",(update_id,command,now))
            if command in {"pause","resume"}:
                self.db.execute("UPDATE control_state SET value=? WHERE key='paused'",("1" if command=="pause" else "0",))
            elif command=="run":
                if self._status()["paused"]:
                    self.db.execute("COMMIT")
                    return {"ok":False,"reason":"paused",**self._status()}
                self.db.execute("INSERT INTO requested_cycles VALUES (?,?,'queued')",(update_id,now))
            self.db.execute("COMMIT")
        except Exception:
            if self.db.in_transaction:self.db.execute("ROLLBACK")
            raise
        result={"ok":True,"command":command,**self._status()}
        if command=="report":result["last_cycle"]=latest_report(self.report_dir)
        return result

def main():
    p=argparse.ArgumentParser(description="Trusted Hermes gateway to CEO control bridge")
    for flag in ("envelope","signature","db","reports"):p.add_argument("--"+flag,required=True)
    args=p.parse_args()
    try:
        envelope=json.loads(Path(args.envelope).read_text(encoding="utf-8"))
        signature=Path(args.signature).read_text(encoding="ascii").strip()
        bridge=GatewayControl(args.db,args.reports,owner_user_id=os.getenv("HERMES_OWNER_USER_ID",""),
            owner_chat_id=os.getenv("HERMES_OWNER_CHAT_ID",""),secret=os.getenv("HERMES_GATEWAY_HMAC_KEY","").encode())
        try:out=bridge.handle(envelope,signature)
        finally:bridge.close()
        print(json.dumps(out,ensure_ascii=False))
    except (ValueError,OSError,sqlite3.Error):
        print(json.dumps({"ok":False,"reason":"unauthorized_or_unavailable"}));sys.exit(1)

if __name__=="__main__":main()
