"""Offline publishing gate. No affiliate is approved in the shipped manifest.

This is NOT an API adapter to any affiliate network, and cannot prove that an
approval document is genuine: an independent human review must confirm each
provider's credentials and terms before production publication.
"""
from __future__ import annotations

from datetime import datetime, timezone
from urllib.parse import urlsplit
import json


def valid_url(value: str, allowed_hosts: list[str]) -> bool:
    if not isinstance(value, str):
        return False
    p = urlsplit(value)
    # Exact host allowlist (subdomains must be reviewed separately).
    return (
        p.scheme == "https"
        and bool(p.hostname)
        and p.hostname.lower() in allowed_hosts
        and not p.username and not p.password
        and p.port in (None, 443)
        and not any(c.isspace() for c in value)
    )


def _time(value: str) -> datetime:
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Timestamp requires timezone")
    return timestamp.astimezone(timezone.utc)


def eligible(program: dict, offer: dict, country: str, at: datetime) -> bool:
    # Program approval never follows from merely inserting an affiliate URL.
    if program.get("status") != "approved":
        return False
    if not program.get("approval_reference") or not program.get("approval_reviewed_by"):
        return False
    if not program.get("approved_at") or _time(program["approved_at"]) > at:
        return False
    if offer.get("status") != "approved" or offer.get("program_id") != program.get("id"):
        return False
    if country.upper() not in program.get("approved_countries", []):
        return False
    hosts = program.get("allowed_link_hosts", [])
    if not isinstance(hosts, list) or not hosts:
        return False
    if not valid_url(offer.get("affiliate_url", ""), hosts):
        return False
    if offer.get("expires_at") and _time(offer["expires_at"]) <= at:
        return False
    if offer.get("start_at") and _time(offer["start_at"]) > at:
        return False
    # Only deals explicitly reviewed for the correct traffic channel can be exported.
    if "web" not in program.get("approved_channels", []):
        return False
    return True


def export_offers(manifest: dict, country: str, at: datetime | None = None) -> list[dict]:
    now = at or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("Aware datetime required")
    programs = {p["id"]: p for p in manifest.get("programs", [])}
    output = []
    for o in manifest.get("offers", []):
        p = programs.get(o.get("program_id"))
        if p is None:
            continue
        try:
            if not eligible(p, o, country, now):
                continue
        except (ValueError, TypeError):
            continue
        output.append({
            "id": o["id"],
            "title": o["title"],
            "affiliate_url": o["affiliate_url"],
            "program_id": p["id"],
            "country": country.upper(),
            "disclosure": "Enlace de afiliado: EligeBien podría recibir comisión.",
            "rel": "sponsored noopener noreferrer",
        })
    return sorted(output, key=lambda item: item["id"])


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Export only independently approved affiliate offers")
    parser.add_argument("manifest")
    parser.add_argument("--country", default="CO")
    args = parser.parse_args()
    with open(args.manifest, encoding="utf-8") as f:
        data = json.load(f)
    print(json.dumps(export_offers(data, args.country), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
