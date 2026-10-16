from datetime import datetime, timezone
from pathlib import Path
import json, sys, unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pipeline import export_offers, valid_url

NOW = datetime(2026, 10, 16, tzinfo=timezone.utc)


def manifest():
    return {
        "programs": [{
            "id": "sample", "status": "approved",
            "approved_at": "2026-10-01T00:00:00Z",
            "approval_reference": "manual-record-01",
            "approval_reviewed_by": "human-reviewer",
            "approved_countries": ["CO"],
            "approved_channels": ["web"],
            "allowed_link_hosts": ["partner.example.test"],
        }],
        "offers": [{
            "id": "offer-1", "program_id": "sample", "status": "approved",
            "title": "Ejemplo interno", "affiliate_url": "https://partner.example.test/a/ref",
            "expires_at": "2026-12-01T00:00:00Z",
        }]
    }


class PublishGate(unittest.TestCase):
    def test_valid_approved_url(self):
        self.assertEqual(len(export_offers(manifest(), "CO", NOW)), 1)
    def test_requires_program_approval(self):
        x = manifest()
        x["programs"][0]["status"] = "research"
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_requires_offer_approval(self):
        x = manifest()
        x["offers"][0]["status"] = "pending"
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_country(self):
        self.assertEqual(export_offers(manifest(), "AR", NOW), [])
    def test_expired(self):
        x = manifest()
        x["offers"][0]["expires_at"] = "2026-10-01T00:00:00Z"
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_future_offer(self):
        x = manifest()
        x["offers"][0]["start_at"] = "2026-10-20T00:00:00Z"
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_approval_reference_is_required(self):
        x = manifest()
        x["programs"][0]["approval_reference"] = ""
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_requires_web_channel(self):
        x = manifest()
        x["programs"][0]["approved_channels"] = ["email"]
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_host_exact(self):
        x = manifest()
        x["offers"][0]["affiliate_url"] = "https://partner.example.test.attacker.example/r"
        self.assertEqual(export_offers(x, "CO", NOW), [])
    def test_reject_userinfo(self):
        self.assertFalse(valid_url("https://someone@partner.example.test/path", ["partner.example.test"]))
    def test_reject_http(self):
        self.assertFalse(valid_url("http://partner.example.test/", ["partner.example.test"]))
    def test_sponsored_disclosure(self):
        o = export_offers(manifest(), "CO", NOW)[0]
        self.assertIn("sponsored", o["rel"])
        self.assertIn("afiliado", o["disclosure"])
    def test_current_manifest_has_no_approved_offers(self):
        path = Path(__file__).resolve().parents[1] / "partner-candidates.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(export_offers(data, "CO", NOW), [])

if __name__ == "__main__":
    unittest.main()
