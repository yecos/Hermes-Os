"""Read only aggregate event counts from Neon for an EligeBien CEO snapshot."""
import argparse,json,os
from datetime import datetime,timedelta,timezone
from pathlib import Path

SITE="https://hermes-os-eligebien.vercel.app"
def build_snapshot(rows,partners,*,site=SITE):
    counts={}
    for name,count in rows:
        if name not in {"category_view","recommend","official_click"}:continue
        if type(count) is not int or count<0:raise ValueError("Invalid aggregated count")
        counts[name]=count
    clean=[]
    for item in partners:
        if not isinstance(item,dict):raise ValueError("Invalid partner entry")
        status=item.get("status","research")
        if status not in {"research","applied","approved","rejected","paused"}:raise ValueError("Invalid partner state")
        clean.append({"name":str(item.get("brand",item.get("name","")))[:100],"status":status})
    return {"site":site,"analytics":{"visits":None,
            "category_events":counts.get("category_view",0),
            "outbound_clicks":counts.get("official_click",0)},
            "partnerships":clean,
            "verified":{"approved_commissions_minor":None,"currency":None}}
def read_neon_counts(url):
    if not url.startswith(("postgresql://","postgres://")):raise ValueError("Invalid database URL")
    try:import psycopg
    except ImportError as e:raise RuntimeError("Install psycopg[binary] on authorized host") from e
    start=datetime.now(timezone.utc)-timedelta(days=7)
    with psycopg.connect(url,connect_timeout=10) as db:
        with db.cursor() as cursor:
            cursor.execute("SELECT event_type, COUNT(*) FROM public.engagement_events WHERE created_at >= %s GROUP BY event_type",(start,))
            return [(kind,int(n)) for kind,n in cursor.fetchall()]
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--partners",required=True);p.add_argument("--out",required=True)
    args=p.parse_args()
    partners=json.loads(Path(args.partners).read_text(encoding="utf-8")).get("programs",[])
    out=build_snapshot(read_neon_counts(os.getenv("ELIGEBIEN_METRICS_DATABASE_URL","")),partners)
    dest=Path(args.out);dest.parent.mkdir(parents=True,exist_ok=True)
    temp=dest.with_suffix(".tmp")
    temp.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    os.replace(temp,dest)
    print(json.dumps({"ok":True,"visits_measured":False,"data":"aggregate_only"}))
if __name__=="__main__":main()
