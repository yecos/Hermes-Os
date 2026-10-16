import { neon } from "@neondatabase/serverless";
const types=new Set(["category_view","recommend","official_click"]);
const cats=new Set(["all","crear","organizar","aprender","idiomas","archivos"]);
export default async function handler(req,res){
  res.setHeader("Cache-Control","no-store");
  if(req.method!=="POST"){res.setHeader("Allow","POST");return res.status(405).json({ok:false});}
  if(Number(req.headers["content-length"]||0)>500)return res.status(413).json({ok:false});
  try{
    const origin=req.headers.origin;
    if(origin&&new URL(origin).host!==req.headers.host)return res.status(403).json({ok:false});
  }catch{return res.status(403).json({ok:false})}
  let value=req.body;
  if(typeof value==="string"){try{value=JSON.parse(value)}catch{return res.status(400).json({ok:false})}}
  if(!value||typeof value!=="object"||!types.has(value.event_type)||!cats.has(value.category)||!(value.offer_id===null||value.offer_id===undefined||typeof value.offer_id==="string"&&/^[a-z0-9-]{1,40}$/.test(value.offer_id)))return res.status(400).json({ok:false});
  if(!process.env.DATABASE_URL)return res.status(503).json({ok:false});
  try{
    const sql=neon(process.env.DATABASE_URL);
    await sql("INSERT INTO public.engagement_events(event_type, category, offer_id) SELECT $1, $2, $3 WHERE (SELECT COUNT(*) FROM public.engagement_events WHERE created_at > NOW() - INTERVAL '1 day') < 5000",[value.event_type,value.category,value.offer_id||null]);
    return res.status(202).json({ok:true});
  }catch(e){console.error("anonymous metrics write failed");return res.status(503).json({ok:false})}
}