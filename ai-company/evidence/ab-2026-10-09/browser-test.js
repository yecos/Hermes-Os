// Browser-level A/B quality checks. Real Chrome, real interactions, no network.
const fs=require('fs');
const path=require('path');
const puppeteer=require('C:/Users/yecos/AppData/Roaming/npm/node_modules/@wonderwhy-er/desktop-commander/node_modules/puppeteer');
const variant=process.argv[2];
if(!['single','multi'].includes(variant)){throw Error('Choose single|multi')}
const base='C:/Dev/HermesAgentBenchmark/'+variant;
const Chrome='C:/Program Files/Google/Chrome/Application/chrome.exe';
const url='file:///'+base+'/index.html';
(async ()=>{
const browser=await puppeteer.launch({headless:true,executablePath:Chrome,args:['--no-sandbox','--disable-gpu','--disable-extensions']});
const page=await browser.newPage();const results=[];const start=Date.now();
async function check(label,fn){
try {const val=await fn(); if(val!==true) throw Error(String(val));results.push({test:label,pass:true});}
catch(e){results.push({test:label,pass:false,error:e.message.substring(0,270)});}
}
try{
await page.setViewport({width:1280,height:850});
await page.goto(url,{waitUntil:'load'});
await page.evaluate(()=>{localStorage.clear();window.__xss=0});await page.reload({waitUntil:'load'});
await check('HTML title',async()=> (await page.title())==='Control de gastos');
await check('Labels form and selectors',async()=>await page.evaluate(()=> ['date','category','amount'].every(id=>{const x=document.getElementById(id);return x && !!document.querySelector('label[for="'+id+'"]')}) && document.querySelector('#add[type=submit]')!==null));
await check('Initially empty and total=0',async()=>await page.evaluate(()=>document.querySelector('#total')?.getAttribute('data-total')==='0' && document.querySelectorAll('#expense-list li').length===0));
async function add(date,category,amount){
await page.$eval('#date',(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))},date);
await page.$eval('#category',(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))},category);
await page.$eval('#amount',(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))},amount);
await page.click('#add');
}
await add('2026-10-08','Comida','100');
await add('2026-10-09','Transporte','50');
await check('Add two expenses',async()=>await page.evaluate(()=>document.querySelectorAll('#expense-list li').length===2));
await check('Global total 150',async()=>await page.$eval('#total',e=>e.getAttribute('data-total')==='150'));
await check('Filter category and keep global total',async()=>{
let opts=await page.$$eval('#filter option',x=>x.map(o=>o.value));
if(!opts.includes('Comida')||!opts.includes('all'))return 'Missing filter options';
await page.select('#filter','Comida');
return await page.evaluate(()=>document.querySelectorAll('#expense-list li').length===1 && document.querySelector('#total')?.getAttribute('data-total')==='150');
});
await page.reload({waitUntil:'load'});
await check('Persistence after refresh',async()=>await page.evaluate(()=>document.querySelector('#total')?.getAttribute('data-total')==='150' && JSON.parse(localStorage.getItem('hermes_expenses_v1')||'[]').length===2));
await page.select('#filter','all').catch(()=>{});
await check('Delete and recalc',async()=>{
const del=await page.$('#expense-list button[data-delete]');if(!del)return 'delete button absent';await del.click();
return await page.evaluate(()=>document.querySelectorAll('#expense-list li').length===1 && (document.querySelector('#total')?.getAttribute('data-total')==='50'||document.querySelector('#total')?.getAttribute('data-total')==='100'));
});
await check('Reject nonpositive expense',async()=>{
const b=await page.$eval('#total',el=>el.getAttribute('data-total'));
await add('2026-10-09','Invalido','-3');
return await page.$eval('#total',(el,previous)=>el.getAttribute('data-total')===previous,b);
});
await check('User data rendered as text, no XSS',async()=>{
await page.evaluate(()=>window.__xss=0);
await add('2026-10-09','<img src=x onerror=window.__xss=1>','25');
return await page.evaluate(()=> window.__xss!==1 && !!document.querySelector('#expense-list')?.textContent.includes('<img src=x onerror=window.__xss=1>'));
});
await page.setViewport({width:390,height:844});
await check('Mobile no horizontal overflow',async()=>await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth+3));
await page.screenshot({path:path.join(base,'screenshot-mobile.png'),fullPage:true});
await page.setViewport({width:1280,height:850});
await page.screenshot({path:path.join(base,'screenshot-desktop.png'),fullPage:true});
}catch(e){results.push({test:'runner_exception',pass:false,error:e.message.substring(0,450)})}
await browser.close();
const payload={variant,passed:results.filter(x=>x.pass).length,total:results.length,seconds:Math.round((Date.now()-start)/1000*100)/100,results};
fs.writeFileSync(path.join(base,'browser-result.json'),JSON.stringify(payload,null,2),'utf8');
console.log(JSON.stringify(payload,null,2));
})().catch(e=>{console.error(e.stack);process.exit(2)});
