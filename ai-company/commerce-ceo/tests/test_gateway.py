import importlib.util,json,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    return mod
ctrl=load("telegram_ctrl","telegram_control.py")
metr=load("snapshot_metrics","metrics_snapshot.py")
SECRET=b"S"*48
NOW=1792170000
def payload(command="status",sender="123",chat="123",id="1",at=NOW):
    return {"update_id":id,"chat_id":chat,"user_id":sender,"issued_at":at,"command":command}
class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.bridge=ctrl.GatewayControl(Path(self.tmp.name)/"db.sqlite3",Path(self.tmp.name)/"reports",
                          owner_user_id="123",owner_chat_id="123",secret=SECRET,clock=lambda:NOW)
    def tearDown(self):self.bridge.close();self.tmp.cleanup()
    def do(self,p):return self.bridge.handle(p,ctrl.sign_trusted_gateway_envelope(p,SECRET))
    def test_paused_default(self):self.assertTrue(self.do(payload())["paused"])
    def test_bad_signature(self):
        with self.assertRaises(ctrl.AuthenticationError):self.bridge.handle(payload(),"0"*64)
    def test_other_user(self):
        with self.assertRaises(ctrl.AuthenticationError):self.do(payload(sender="999"))
    def test_other_chat(self):
        with self.assertRaises(ctrl.AuthenticationError):self.do(payload(chat="-100"))
    def test_replay(self):
        p=payload()
        self.assertTrue(self.do(p)["ok"])
        self.assertEqual(self.do(p)["reason"],"duplicate_update")
    def test_stale(self):
        with self.assertRaises(ctrl.AuthenticationError):self.do(payload(at=NOW-301))
    def test_future(self):
        with self.assertRaises(ctrl.AuthenticationError):self.do(payload(at=NOW+301))
    def test_no_run_if_paused(self):self.assertEqual(self.do(payload("run"))["reason"],"paused")
    def test_queued_is_not_execution(self):
        self.do(payload("resume",id="2"))
        r=self.do(payload("run",id="3"))
        self.assertEqual(r["queued_cycle_requests"],1)
        self.assertEqual(r["agent_execution"],"not_connected")
    def test_pause_resume(self):
        self.do(payload("resume",id="2"))
        self.assertFalse(self.do(payload("status",id="3"))["paused"])
        self.assertTrue(self.do(payload("pause",id="4"))["paused"])
    def test_unknown_command(self):
        with self.assertRaises(ctrl.AuthenticationError):self.do(payload("delete"))
    def test_unknown_fields(self):
        p=payload();p["override"]=True
        with self.assertRaises(ctrl.AuthenticationError):self.do(p)
    def test_report_empty(self):self.assertIsNone(self.do(payload("report"))["last_cycle"])
    def test_report_limited(self):
        folder=Path(self.tmp.name)/"reports";folder.mkdir()
        (folder/"ceo-2026-10-16.json").write_text(json.dumps({
          "run_id":"2026-10-16","status":"completed_draft_cycle",
          "approved_commissions_minor":None,"currency":None,
          "tasks":[{"state":"reviewed_draft","draft":"secret draft"}]}))
        r=self.do(payload("report"))
        self.assertEqual(r["last_cycle"]["reviewed_drafts"],1)
        self.assertNotIn("secret draft",json.dumps(r))
    def test_weak_secret(self):
        with self.assertRaises(ctrl.AuthenticationError):
            ctrl.GatewayControl(Path(self.tmp.name)/"test.db",Path(self.tmp.name),
                                owner_user_id="123",owner_chat_id="123",secret=b"bad")
    def test_snapshot_unknown_visits(self):
        s=metr.build_snapshot([("category_view",5),("official_click",2)],
                              [{"brand":"impact.com","status":"research"}])
        self.assertIsNone(s["analytics"]["visits"])
        self.assertEqual(s["analytics"]["outbound_clicks"],2)
        self.assertIsNone(s["verified"]["approved_commissions_minor"])
    def test_negative_metrics(self):
        with self.assertRaises(ValueError):metr.build_snapshot([("official_click",-1)],[])
    def test_unknown_partner(self):
        with self.assertRaises(ValueError):metr.build_snapshot([],[{"brand":"A","status":"fake"}])
if __name__=="__main__":unittest.main()
