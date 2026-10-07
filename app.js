import { readShot } from './shot.js';
import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect, signInWithCredential, getRedirectResult, onAuthStateChanged, signOut } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js';
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager, collection, doc, onSnapshot, setDoc, updateDoc, deleteDoc, addDoc, writeBatch } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js';

// Configuración pública del proyecto Firebase (no es secreta: el acceso a los datos
// lo controlan las reglas de seguridad de Firestore, ver firestore.rules).
const firebaseConfig = {
  apiKey: 'AIzaSyAZ-WU0HhLVo83ntxkkV7ClHwnfQA_p46k',
  authDomain: 'registro-xauusd-26ac6.firebaseapp.com',
  projectId: 'registro-xauusd-26ac6',
  storageBucket: 'registro-xauusd-26ac6.firebasestorage.app',
  messagingSenderId: '897912027374',
  appId: '1:897912027374:web:b8fcbd0a6fbe7cfb729bff'
};
const fbApp = initializeApp(firebaseConfig);
const auth = getAuth(fbApp);
const provider = new GoogleAuthProvider();
provider.setCustomParameters({ prompt: 'select_account' });
let fs;
try { fs = initializeFirestore(fbApp, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) }); }
catch (e) { fs = initializeFirestore(fbApp, {}); }

const clean = o => JSON.parse(JSON.stringify(o)); // Firestore no admite "undefined"

// Adaptador con la misma forma que usaba la versión de Claude: todo vive en users/{uid}/...
function makeDb(uid){
  const docApi = ref => ({
    set: d => setDoc(ref, clean(d)),
    update: d => updateDoc(ref, clean(d)),
    delete: () => deleteDoc(ref),
    onSnapshot: (next, err) => onSnapshot(ref, s => next({ exists: s.exists(), data: () => s.data() }), err)
  });
  return {
    collection(name){
      const c = collection(fs, 'users', uid, name);
      const api = {
        limit(){ return api; },
        onSnapshot: (next, err) => onSnapshot(c, s => next({ docs: s.docs.map(d => ({ id: d.id, data: () => d.data() })) }), err),
        doc: id => docApi(doc(c, id)),
        add: d => addDoc(c, clean(d))
      };
      return api;
    },
    doc(path){ const [a, b] = path.split('/'); return docApi(doc(fs, 'users', uid, a, b)); },
    uid
  };
}
async function restoreBackup(data){
  const uid = auth.currentUser && auth.currentUser.uid; if (!uid) throw new Error('Inicia sesión primero.');
  const ops = [];
  (data.days||[]).forEach(d => { if (d && d.acc && d.date) ops.push([doc(fs,'users',uid,'days',d.acc+'_'+d.date), clean(d)]); });
  (data.entries||[]).forEach(e => { const { id, _id, ...r } = e; ops.push([id||_id ? doc(fs,'users',uid,'entries',id||_id) : doc(collection(fs,'users',uid,'entries')), clean(r)]); });
  if (data.config) ops.push([doc(fs,'users',uid,'config','main'), clean(data.config)]);
  for (let i=0; i<ops.length; i+=400){ const b = writeBatch(fs); ops.slice(i,i+400).forEach(([r,d]) => b.set(r,d)); await b.commit(); }
  return { days:(data.days||[]).length, entries:(data.entries||[]).length, config:!!data.config };
}

(() => {
'use strict';
/* ---------- state ---------- */
const DEFAULT_CFG = {
  accounts: { '24894318': { name: 'Principal' }, '24552198': { name: 'Secundaria' } },
  copyAcc: '24894318', copyStart: '2026-10-01', pct: 30, commInitial: 2222, commAcc: '24552198',
  copiers: [ { id: 'deyvid', name: 'Deyvid', capital: 968, mult: 2 }, { id: 'franco', name: 'Franco', capital: 3326, mult: 1.5 } ]
};
let days = [];          // docs from db "days"
let entries = [];       // docs from db "entries"
let cfg = structuredClone(DEFAULT_CFG);
let db = null, canWrite = false;
let sel = localGet('acc') || '24894318';
let view = 'cal';
let monthKey = null;    // 'YYYY-MM'
let chartMode = 'bal';
let copSel = 'comm', copMonth = null;
let xPer = 'all', xMonth = null, xCustom = null;

/* ---------- utils ---------- */
function localGet(k){ try { return localStorage.getItem('xau.'+k); } catch(e){ return null; } }
function localSet(k,v){ try { localStorage.setItem('xau.'+k,v); } catch(e){} }
const $ = id => document.getElementById(id);
const nf2 = new Intl.NumberFormat('es-ES',{minimumFractionDigits:2,maximumFractionDigits:2,useGrouping:'always'});
const nf0 = new Intl.NumberFormat('es-ES',{maximumFractionDigits:0,useGrouping:'always'});
const money = v => (v<0?'−':'') + nf2.format(Math.abs(v)) + ' $';
const money0 = v => (v<0?'−':'') + nf0.format(Math.abs(v)) + ' $';
const smoney0 = v => (v>0?'+':v<0?'−':'') + nf0.format(Math.abs(v)) + ' $';
const smoney = v => (v>0?'+':v<0?'−':'') + nf2.format(Math.abs(v)) + ' $';
const spct = (v,d=2) => (v>0?'+':v<0?'−':'') + Math.abs(v*100).toFixed(d).replace('.',',') + ' %';
const compact = v => { const a=Math.abs(v), s=v>0?'+':v<0?'−':''; if(a<0.05) return '0'; if(a>=1000) return s+(a/1000).toFixed(a>=10000?0:1).replace('.',',')+'k'; if(a<10) return s+a.toFixed(1).replace('.',','); return s+Math.round(a); };
const cls = v => v>0?'pos':v<0?'neg':'mut';
const esc = s => String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const MES = ['enero','febrero','marzo','abril','mayo','junio','julio','agosto','septiembre','octubre','noviembre','diciembre'];
const DOW = ['domingo','lunes','martes','miércoles','jueves','viernes','sábado'];
const pd = s => { const [y,m,d]=s.split('-').map(Number); return new Date(Date.UTC(y,m-1,d)); };
const fd = dt => dt.toISOString().slice(0,10);
const todayStr = () => { const n=new Date(); return n.getFullYear()+'-'+String(n.getMonth()+1).padStart(2,'0')+'-'+String(n.getDate()).padStart(2,'0'); };
const cap1 = t => t.charAt(0).toUpperCase()+t.slice(1);
const longDate = s => { const d=pd(s); return DOW[d.getUTCDay()]+' '+d.getUTCDate()+' de '+MES[d.getUTCMonth()]+' '+d.getUTCFullYear(); };
const shortDate = s => { const d=pd(s); return String(d.getUTCDate()).padStart(2,'0')+'/'+String(d.getUTCMonth()+1).padStart(2,'0')+'/'+String(d.getUTCFullYear()).slice(2); };
const accName = a => (cfg.accounts && cfg.accounts[a] && cfg.accounts[a].name) || ('Cuenta '+a);
const showMsg = (el, text, kind) => { el.textContent=text; el.className='msg'+(kind?' '+kind:''); el.hidden=!text; };

/* ---------- derived data ---------- */
function accountIds(){
  const s = new Set(Object.keys(cfg.accounts||{}));
  days.forEach(d=>s.add(d.acc));
  return [...s].sort((a,b)=> (a===cfg.copyAcc?-1:b===cfg.copyAcc?1:a<b?-1:1));
}
function computeAcc(a){
  const arr = days.filter(d=>d.acc===a).sort((x,y)=>x.date<y.date?-1:1);
  let prev = null;
  return arr.map(d=>{
    const pnl=+d.pnl||0, flow=+d.flow||0;
    let end;
    if (d.src!=='manual' && typeof d.end==='number') end=d.end;
    else end = ((typeof d.start==='number') ? d.start : (prev??0)) + pnl + flow;
    const start = end - pnl - flow;
    prev = end;
    return {...d, pnl, flow, start, end, accs:[a]};
  });
}
function series(selAcc){
  const accs = selAcc==='all' ? accountIds() : [selAcc];
  const per = accs.map(a=>({a, rows:computeAcc(a)}));
  if (per.length===1) return per[0].rows.map(r=>({...r, pct: r.start>0? r.pnl/r.start : 0, parts:[r]}));
  const maps = per.map(p=>({a:p.a, m:new Map(p.rows.map(r=>[r.date,r]))}));
  const dates = [...new Set(per.flatMap(p=>p.rows.map(r=>r.date)))].sort();
  const last = {};
  return dates.map(date=>{
    let pnl=0,flow=0,start=0,end=0,n=0,wins=0,losses=0; const parts=[];
    maps.forEach(({a,m})=>{
      const r=m.get(date);
      if (r){ pnl+=r.pnl; flow+=r.flow; start+=r.start; end+=r.end; n+=r.n||0; wins+=r.wins||0; losses+=r.losses||0; last[a]=r.end; parts.push(r); }
      else if (last[a]!=null){ start+=last[a]; end+=last[a]; }
    });
    return {date,pnl,flow,start,end,n,wins,losses,parts,pct:start>0?pnl/start:0};
  });
}
function stats(S){
  let idx=1, peak=1, mdd=0, best=null, worst=null, g=0, r=0, sum=0, traded=0, gp=0, gl=0, wins=0, losses=0;
  const curve=[];
  S.forEach(x=>{
    if (x.start>0) idx*=1+x.pnl/x.start;
    peak=Math.max(peak,idx); const dd=idx/peak-1; mdd=Math.min(mdd,dd);
    curve.push({date:x.date, bal:x.end, cum:idx-1, dd});
    const active = (x.n||0)>0 || x.pnl!==0;
    if (active){ traded++; sum+=x.pnl; if(x.pnl>0)g++; else if(x.pnl<0)r++;
      if(best===null||x.pct>best.pct)best=x; if(worst===null||x.pct<worst.pct)worst=x; }
    wins+=x.wins||0; losses+=x.losses||0;
    (x.parts||[]).forEach(p=>(p.trades||[]).forEach(t=>{ if(t.p>0)gp+=t.p; else gl-=t.p; }));
  });
  const lastRow=S[S.length-1];
  return {cum:idx-1, mdd, curDd: curve.length?curve[curve.length-1].dd:0, best, worst, g, r, traded, total:S.reduce((a,x)=>a+x.pnl,0), flows:S.reduce((a,x)=>a+x.flow,0), avg: traded? sum/traded:0, pf: gl>0? gp/gl : null, wins, losses, bal:lastRow?lastRow.end:0, curve};
}
function months(S){
  const m=new Map();
  S.forEach(x=>{ const k=x.date.slice(0,7); if(!m.has(k)) m.set(k,{k,pnl:0,idx:1,g:0,r:0,n:0}); const o=m.get(k);
    o.pnl+=x.pnl; if(x.start>0)o.idx*=1+x.pnl/x.start; if(x.pnl>0)o.g++; else if(x.pnl<0)o.r++; if((x.n||0)>0||x.pnl!==0)o.n++; });
  return [...m.values()].map(o=>({...o,pct:o.idx-1}));
}
function flowLabel(c){ const m=String(c||'').match(/MTS-CTS\s*PM\s*(\d\d\/\d\d)\/\d\d-(\d\d\/\d\d)/i); if(m) return 'Comisión de referidos ('+m[1]+'–'+m[2]+')'; if(/MTS-CTS/i.test(c)) return 'Comisión de referidos';
  let t=String(c||''); t=t.replace(/^Transfer In from (\d+)/i,(x,a)=>'Transferencia desde '+accLabel(a)).replace(/^Transfer Out to (\d+)/i,(x,a)=>'Transferencia a '+accLabel(a)); return t; }
function accLabel(a){ return (cfg.accounts&&cfg.accounts[a])? accName(a)+' ('+a+')' : 'cuenta '+a; }
const isCommFlow = f => false; // las comisiones de copiers aún no tienen un comentario conocido en MT5
function isLinked(){ return !!(cfg.commAcc && cfg.commAcc!=='none'); }
function copyEngine(){
  const pct=(+cfg.pct||0)/100, start=cfg.copyStart||'0000-00-00';
  const base = new Map(computeAcc(cfg.copyAcc).filter(r=>r.date>=start).map(r=>[r.date,r]));
  const ents = entries.filter(e=>e.date>=start);
  const dates=[...new Set([...base.keys(), ...ents.map(e=>e.date)])].sort();
  const cps=(cfg.copiers||[]).map(c=>({...c, cap:+c.capital||0, gain:0, com:0, mov:0}));
  let comm=+cfg.commInitial||0, commTot=0, own=0, commMov=0; const rows=[]; const commByMonth=new Map();
  dates.forEach(date=>{
    const b=base.get(date); const row={date, base:b?b.start:null, pnl:b?b.pnl:0, cp:[], com:0};
    cps.forEach(c=>{
      let g=0, cm=0;
      if (b && b.start>0){ g=b.pnl*(c.cap/b.start)*(+c.mult||1); cm=g>0? g*pct : 0; }
      const mv=ents.filter(e=>e.date===date && e.target===c.id).reduce((a,e)=>a+(+e.amount||0),0);
      const st=c.cap; c.cap+=g-cm+mv; c.gain+=g; c.com+=cm; c.mov+=mv; row.cp.push({g,cm,mv,start:st,cap:c.cap}); row.com+=cm;
    });
    const ce=ents.filter(e=>e.date===date && e.target==='comm');
    const ownD=ce.filter(e=>e.kind==='propia').reduce((a,e)=>a+(+e.amount||0),0);
    const movD=ce.filter(e=>e.kind!=='propia').reduce((a,e)=>a+(+e.amount||0),0);
    comm+=row.com+ownD+movD; commTot+=row.com; own+=ownD; commMov+=movD; row.comm=comm;
    const mk=date.slice(0,7); commByMonth.set(mk,(commByMonth.get(mk)||0)+row.com);
    rows.push(row);
  });
  return {rows, cps, comm, commTot, own, commMov, commByMonth, byDate:new Map(rows.map(r=>[r.date,r]))};
}

/* ---------- rendering ---------- */
function renderAccs(){
  const ids=accountIds();
  if (sel!=='all' && !ids.includes(sel)) sel=ids[0]||'all';
  const btns=ids.map(a=>`<button class="acc" data-a="${esc(a)}" aria-pressed="${sel===a}">${esc(accName(a))} <span style="opacity:.7">·${esc(a.slice(-4))}</span></button>`);
  if (ids.length>1) btns.push(`<button class="acc" data-a="all" aria-pressed="${sel==='all'}">Ambas</button>`);
  $('accs').innerHTML=btns.join('');
}
function renderHeader(S){
  const st=stats(S);
  $('hdrLbl').textContent = sel==='all' ? 'Balance total' : 'Balance';
  $('hdrBal').textContent = S.length? money(st.bal) : '—';
}
function gridHtml(mk, vals, opt){
  // vals: Map date -> number. opt.gold: colour every value gold (commissions)
  const [y,m]=mk.split('-').map(Number);
  const inM=[...vals.entries()].filter(([d])=>d.startsWith(mk)).map(([,v])=>Math.abs(v));
  const maxAbs=Math.max(1e-9,...inM);
  const tone=v=> opt.gold? (v>0?'var(--gold)':'var(--muted)') : (v>0?'var(--pos)':v<0?'var(--neg)':'var(--muted)');
  const tcls=v=> opt.gold? (v>0?'gt':'mut') : cls(v);
  const first=new Date(Date.UTC(y,m-1,1)); const dow=(first.getUTCDay()+6)%7; const cur=new Date(first); cur.setUTCDate(1-dow);
  let html=['L','M','X','J','V'].map(d=>`<div class="hd">${d}</div>`).join('')+'<div class="hd wk">Sem</div>';
  const today=todayStr();
  for(let w=0; w<6; w++){
    if (w>0 && cur.getUTCMonth()!==m-1 && cur>first) break;
    let wsum=0, wany=false, wkend=[];
    for(let i=0;i<7;i++){
      const ds=fd(cur); const has=vals.has(ds); const v=vals.get(ds)||0; const inMo=cur.getUTCMonth()===m-1;
      if (has && inMo){ wsum+=v; wany=true; }
      if (i<5){
        let style='', inner=`<span class="d">${cur.getUTCDate()}</span>`, c='cell'+(inMo?'':' out')+(ds===today?' today':'');
        if (has && inMo){
          const t=Math.round(10+40*Math.min(1,Math.abs(v)/maxAbs));
          style=`background:color-mix(in oklab, ${tone(v)} ${v===0?10:t}%, var(--surface))`;
          inner+=`<span class="p ${tcls(v)}">${v===0?'0':(opt.gold?compact(v).replace('+',''):compact(v))}</span>`; c+=' has';
        }
        html+= has&&inMo ? `<button class="${c}" style="${style}" data-d="${ds}" aria-label="${longDate(ds)}: ${opt.gold?money(v):smoney(v)}">${inner}</button>` : `<div class="${c}">${inner}</div>`;
      } else if (has && inMo) wkend.push(ds);
      cur.setUTCDate(cur.getUTCDate()+1);
    }
    html+=`<div class="wkcell" ${wkend.length?`title="Incluye ${wkend.map(shortDate).join(', ')}"`:''}>${wkend.length?'<span class="wkend" style="position:static;display:block;margin-bottom:auto"></span>':''}<span class="p ${wany?tcls(wsum):'mut'}">${wany?(opt.gold?compact(wsum).replace('+',''):compact(wsum)):'·'}</span></div>`;
  }
  return html;
}
function renderCopCal(E){
  const opts=[['comm','Comisiones'],...(cfg.copiers||[]).map(c=>[c.id,c.name])];
  if (!opts.some(o=>o[0]===copSel)) copSel='comm';
  $('copSeg').innerHTML=opts.map(([id,n])=>`<button data-c="${esc(id)}" aria-pressed="${copSel===id}">${esc(n)}</button>`).join('');
  if (!copMonth) copMonth = E.rows.length? E.rows[E.rows.length-1].date.slice(0,7) : todayStr().slice(0,7);
  const [y,m]=copMonth.split('-').map(Number); $('cTitle').textContent=MES[m-1]+' '+y;
  const rows=E.rows.filter(r=>r.base!=null);
  const ci=(cfg.copiers||[]).findIndex(c=>c.id===copSel);
  const vals=new Map(rows.map(r=>[r.date, copSel==='comm'? r.com : r.cp[ci].g-r.cp[ci].cm]));
  $('copCal').innerHTML=gridHtml(copMonth, vals, {gold:copSel==='comm'});
  const mr=rows.filter(r=>r.date.startsWith(copMonth));
  const set=(i,l,v)=>{ $('csl'+i).textContent=l; $('cs'+i).innerHTML=v; };
  if (!mr.length){ set(1,copSel==='comm'?'Comisión del mes':'Resultado neto','<span class="mut">—</span>'); set(2,copSel==='comm'?'Días cobrados':'% del mes','<span class="mut">—</span>'); set(3,copSel==='comm'?'Media por día':'Comisión pagada','<span class="mut">—</span>'); }
  else if (copSel==='comm'){
    const tot=mr.reduce((a,r)=>a+r.com,0), d=mr.filter(r=>r.com>0).length;
    set(1,'Comisión del mes',`<span class="gt">${money(tot)}</span>`); set(2,'Días cobrados',`${d} <span class="mut">/ ${mr.length}</span>`); set(3,'Media por día',money(mr.length?tot/mr.length:0));
  } else {
    const g=mr.reduce((a,r)=>a+r.cp[ci].g-r.cp[ci].cm,0), cm=mr.reduce((a,r)=>a+r.cp[ci].cm,0);
    const pct=mr.reduce((a,r)=>a*(1+(r.cp[ci].start>0? (r.cp[ci].g-r.cp[ci].cm)/r.cp[ci].start:0)),1)-1;
    set(1,'Resultado neto',`<span class="${cls(g)}">${smoney(g)}</span>`); set(2,'% del mes',`<span class="${cls(pct)}">${spct(pct)}</span>`); set(3,'Comisión pagada',money(cm));
  }
  $('copCalEmpty').hidden = rows.length>0;
}
function openCopDay(date){
  const E=copyEngine(); const r=E.byDate.get(date); if(!r||r.base==null) return;
  const cs=cfg.copiers||[]; const ci=cs.findIndex(c=>c.id===copSel);
  let body;
  if (ci<0){
    body=`<div><div class="big gt">${money(r.com)}</div><div class="mut">Comisión total del día (${cfg.pct} %)</div></div>
    <dl class="kv card">${cs.map((c,i)=>`<dt>${esc(c.name)}</dt><dd>${r.cp[i].cm?money(r.cp[i].cm):'<span class="mut">0 (día en negativo)</span>'}</dd>`).join('')}<dt>Tu resultado ese día</dt><dd class="${cls(r.pnl)}">${smoney(r.pnl)}</dd></dl>`;
  } else {
    const p=r.cp[ci], c=cs[ci], net=p.g-p.cm, pct=p.start>0?net/p.start:0;
    body=`<div><div class="big ${cls(net)}">${smoney(net)}</div><div class="${cls(pct)} num">${spct(pct)} del día · neto</div></div>
    <dl class="kv card"><dt>Capital inicial</dt><dd>${money(p.start)}</dd><dt>Ganancia bruta</dt><dd class="${cls(p.g)}">${smoney(p.g)}</dd><dt>Tu comisión (${cfg.pct} %)</dt><dd>${p.cm?'−'+money(p.cm):'0,00 $'}</dd><dt>Ganancia neta</dt><dd class="${cls(net)}">${smoney(net)}</dd>${p.mv?`<dt>Depósito / retiro</dt><dd class="${cls(p.mv)}">${smoney(p.mv)}</dd>`:''}<dt>Capital final</dt><dd>${money(p.cap)}</dd></dl>
    <p class="hint" style="margin:0">Ganancia bruta: tu ganancia ${smoney(r.pnl)} × (${money(p.start)} ÷ ${money(r.base)}) × ${String(c.mult).replace('.',',')}.</p>`;
  }
  const host=$('sheetHost');
  host.innerHTML=`<div class="scrim" id="scrim"><div class="sheet" role="dialog" aria-modal="true"><div class="grab"></div>
    <div class="row"><div><div class="eyebrow">${ci<0?'Comisiones':esc(cs[ci].name)}</div><h2>${cap1(longDate(date))}</h2></div><button class="btn ghost" id="shClose">Cerrar</button></div>${body}</div></div>`;
  const close=()=>{ host.innerHTML=''; document.removeEventListener('keydown',onKey); }; const onKey=e=>{ if(e.key==='Escape') close(); };
  document.addEventListener('keydown',onKey); $('scrim').addEventListener('click',e=>{ if(e.target.id==='scrim') close(); }); $('shClose').addEventListener('click',close); $('shClose').focus();
}
function avgRate(S, days){
  const last=S.length? S[S.length-1].date : todayStr();
  let cut='0000-00-00'; if (days){ const d=pd(last); d.setUTCDate(d.getUTCDate()-days); cut=fd(d); }
  const act=S.filter(x=>x.date>cut && x.start>0 && ((x.n||0)>0 || x.pnl!==0));
  if (!act.length) return {geo:0, arith:0, usd:0, n:0};
  const prod=act.reduce((a,x)=>a*(1+x.pnl/x.start),1);
  return {geo:Math.pow(prod,1/act.length)-1, arith:act.reduce((a,x)=>a+x.pnl/x.start,0)/act.length, usd:act.reduce((a,x)=>a+x.pnl,0)/act.length, n:act.length};
}
function isWeekday(ds){ const g=pd(ds).getUTCDay(); return g>=1 && g<=5; }
function projector(S){
  const st=S.length? S[S.length-1] : null;
  const per = xPer==='all'?0 : xPer==='custom'?0 : +xPer;
  const A=avgRate(S, per);
  const r = xPer==='custom' && xCustom!=null ? xCustom/100 : A.geo;
  const baseDate = st? st.date : todayStr(), baseBal = st? st.end : 0;
  const cache=new Map();
  const at = ds => { // projected balance at end of day ds (ds > baseDate)
    if (cache.has(ds)) return cache.get(ds);
    let n=0; const d=pd(baseDate); d.setUTCDate(d.getUTCDate()+1);
    const end=pd(ds); while(d<=end){ if(d.getUTCDay()>=1&&d.getUTCDay()<=5) n++; d.setUTCDate(d.getUTCDate()+1); }
    const v={bal: baseBal*Math.pow(1+r,n), n}; cache.set(ds,v); return v; };
  return {A, r, baseDate, baseBal, at};
}
function renderExp(S){
  const P=projector(S);
  const perName={all:'todo el historial','90':'los últimos 3 meses','30':'el último mes',custom:'tu %'}[xPer];
  $('xRate').innerHTML = `<span>${spct(P.r)}</span>`;
  $('xRateSub').textContent = xPer==='custom' ? `Usando tu % · media real de todo el historial ${spct(avgRate(S,0).geo)}` : `Media de ${perName}: ${P.A.n} días operados · ${smoney(P.A.usd)} por día`;
  document.querySelectorAll('#xPer button').forEach(b=>b.setAttribute('aria-pressed', b.dataset.p===xPer));
  $('xCustomWrap').hidden = xPer!=='custom';
  if (!xMonth) xMonth = todayStr().slice(0,7);
  const [y,m]=xMonth.split('-').map(Number); $('xTitle').textContent=MES[m-1]+' '+y;
  const byDate=new Map(S.map(x=>[x.date,x]));
  const today=todayStr();
  const first=new Date(Date.UTC(y,m-1,1)); const dow=(first.getUTCDay()+6)%7; const cur=new Date(first); cur.setUTCDate(1-dow);
  let html=['L','M','X','J','V'].map(d=>`<div class="hd">${d}</div>`).join('')+'<div class="hd wk">Sem</div>';
  const balOn = ds => { if (ds<=P.baseDate){ let b=null; for(const x of S){ if(x.date<=ds) b=x.end; else break; } return b==null?null:{bal:b,real:true}; } return {...P.at(ds), real:false}; };
  for(let w=0; w<6; w++){
    if (w>0 && cur.getUTCMonth()!==m-1 && cur>first) break;
    const wkStart=new Date(cur); wkStart.setUTCDate(wkStart.getUTCDate()-1);
    const bStart=balOn(fd(wkStart)); let bEnd=null;
    for(let i=0;i<7;i++){
      const ds=fd(cur), inMo=cur.getUTCMonth()===m-1;
      if (i<5){
        const b=balOn(ds); let c='cell'+(inMo?'':' out')+(ds===today?' today':''), inner=`<span class="d">${cur.getUTCDate()}</span>`;
        if (b && inMo){ c+= b.real?' real':' proj has'; inner+=`<span class="b ${b.real?'':'gt'}">${nf0.format(b.bal)}</span>`; bEnd=b; }
        html+= (b && inMo && !b.real) ? `<button class="${c}" data-d="${ds}" aria-label="${longDate(ds)}: ${money(b.bal)} esperado">${inner}</button>` : `<div class="${c}">${inner}</div>`;
      }
      cur.setUTCDate(cur.getUTCDate()+1);
    }
    const wg = (bStart && bEnd) ? bEnd.bal-bStart.bal : null;
    html+=`<div class="wkcell"><span class="p ${wg==null?'mut':cls(wg)}">${wg==null?'·':compact(wg)}</span></div>`;
  }
  $('xCal').innerHTML=html;
  const mEnd=fd(new Date(Date.UTC(y,m,0))); const mStartPrev=fd(new Date(Date.UTC(y,m-1,0)));
  const be=balOn(mEnd), bs=balOn(mStartPrev);
  $('xs1').textContent = money0(P.baseBal);
  $('xs2').innerHTML = be? `<span class="${be.real?'':'gt'}">${money0(be.bal)}</span>` : '—';
  $('xs3').innerHTML = (be&&bs)? `<span class="${cls(be.bal-bs.bal)}">${smoney0(be.bal-bs.bal)}</span>` : '—';
  const miles=[['Dentro de 1 semana',7],['Dentro de 1 mes',30],['Dentro de 3 meses',91],['Dentro de 6 meses',182],['Dentro de 1 año',365]].map(([l,d])=>{ const t=pd(P.baseDate); t.setUTCDate(t.getUTCDate()+d); const ds=fd(t); const v=P.at(ds); return `<div class="li" style="cursor:default"><div class="l"><strong>${l}</strong><small>${shortDate(ds)} · ${v.n} días de trading</small></div><div class="r"><span class="gt">${money(v.bal)}</span><small class="${cls(v.bal-P.baseBal)}">${smoney(v.bal-P.baseBal)}</small></div></div>`; }).join('');
  $('xMiles').innerHTML = S.length? miles : '<div class="empty">Sin datos todavía.</div>';
}
function openExpDay(ds){
  const S=series(sel), P=projector(S), v=P.at(ds);
  const host=$('sheetHost');
  host.innerHTML=`<div class="scrim" id="scrim"><div class="sheet" role="dialog" aria-modal="true"><div class="grab"></div>
   <div class="row"><div><div class="eyebrow">Expectativa · ${esc(sel==='all'?'Ambas cuentas':accName(sel))}</div><h2>${cap1(longDate(ds))}</h2></div><button class="btn ghost" id="shClose">Cerrar</button></div>
   <div><div class="big gt">${money(v.bal)}</div><div class="mut num">balance esperado al cierre</div></div>
   <dl class="kv card"><dt>Balance actual (${shortDate(P.baseDate)})</dt><dd>${money(P.baseBal)}</dd><dt>Días de trading hasta entonces</dt><dd>${v.n}</dd><dt>Media diaria usada</dt><dd>${spct(P.r)}</dd><dt>Ganancia esperada</dt><dd class="${cls(v.bal-P.baseBal)}">${smoney(v.bal-P.baseBal)}</dd><dt>Rentabilidad</dt><dd class="${cls(v.bal-P.baseBal)}">${spct(P.baseBal>0?v.bal/P.baseBal-1:0)}</dd></dl></div></div>`;
  const close=()=>{ host.innerHTML=''; }; $('scrim').addEventListener('click',e=>{ if(e.target.id==='scrim') close(); }); $('shClose').addEventListener('click',close); $('shClose').focus();
}
function renderCal(S){
  const byDate=new Map(S.map(x=>[x.date,x]));
  if (!monthKey){ monthKey = S.length? S[S.length-1].date.slice(0,7) : todayStr().slice(0,7); }
  const [y,m]=monthKey.split('-').map(Number);
  $('mTitle').textContent = MES[m-1]+' '+y;
  const inMonth=S.filter(x=>x.date.startsWith(monthKey));
  const mo=months(inMonth)[0];
  $('msPnl').innerHTML = mo? `<span class="${cls(mo.pnl)}">${smoney(mo.pnl)}</span>` : '<span class="mut">—</span>';
  $('msPct').innerHTML = mo? `<span class="${cls(mo.pct)}">${spct(mo.pct)}</span>` : '<span class="mut">—</span>';
  $('msDays').innerHTML = mo? `<span class="pos">${mo.g}</span> <span class="mut">/</span> <span class="neg">${mo.r}</span>` : '<span class="mut">—</span>';
  const html=gridHtml(monthKey, new Map(S.map(x=>[x.date,x.pnl])), {});
  $('cal').innerHTML=html;
  $('calEmpty').hidden = days.length>0;
}
function renderReg(S){
  const ids=accountIds();
  const opt=ids.map(a=>`<option value="${esc(a)}">${esc(accName(a))} (${esc(a)})</option>`).join('');
  const mAcc=$('mAcc'); const keep=mAcc.value; mAcc.innerHTML=opt; mAcc.value= keep && ids.includes(keep)? keep : (sel!=='all'?sel:ids[0]);
  updateStartField();
  ['tFrom','tTo'].forEach((id,i)=>{ const el=$(id); const k=el.value; el.innerHTML=opt; el.value = k && ids.includes(k) ? k : (ids[i]||ids[0]); });
  const rows=[...S].reverse();
  $('regCount').textContent = rows.length? rows.length+' días' : '';
  $('regList').innerHTML = rows.length? rows.map(x=>`<div class="li" data-d="${x.date}" role="button" tabindex="0">
     <div class="l"><strong>${shortDate(x.date)}</strong> <span class="mut" style="font-size:13px">${DOW[pd(x.date).getUTCDay()].slice(0,3)}</span>
       <small>${x.n?x.n+' op. · ':''}${x.flow?'Mov. '+smoney(x.flow)+' · ':''}${(x.parts||[]).some(p=>p.via==='captura')?'captura':(x.parts||[]).some(p=>p.src==='manual')?'a mano':(x.parts||[]).some(p=>p.src==='myfxbook')?'Myfxbook':'MT5'}</small></div>
     <div class="r"><span class="${cls(x.pnl)}">${smoney(x.pnl)}</span><small>${spct(x.pct)} · ${money(x.end)}</small></div></div>`).join('')
     : '<div class="empty">Todavía no hay días. Importa tu informe arriba.</div>';
}
function updateStartField(){
  const a=$('mAcc').value; const has=days.some(d=>d.acc===a);
  $('mStartWrap').hidden = has;
}
function renderCop(){
  const E=copyEngine();
  const mk=todayStr().slice(0,7);
  const linked = isLinked();
  if (linked){
    const rows=computeAcc(cfg.commAcc), last=rows[rows.length-1];
    $('cmLbl').textContent='Cuenta de comisiones · '+accName(cfg.commAcc);
    $('cmBal').textContent= last? money(last.end) : 'Sin datos aún';
    const rec=rows.flatMap(r=>(r.flows||[]).filter(isCommFlow).map(f=>({d:r.date,a:+f.a||0})));
    const recTot=rec.reduce((a,x)=>a+x.a,0), recMonth=rec.filter(x=>x.d.startsWith(mk)).reduce((a,x)=>a+x.a,0);
    $('cmSub').textContent=(last?`Balance al ${shortDate(last.date)} · `:`Importa el informe de la cuenta ${cfg.commAcc} · `)+`Comisiones calculadas desde el ${shortDate(cfg.copyStart)}: ${money(E.commTot)}`+(E.commByMonth.get(mk)?` (este mes ${money(E.commByMonth.get(mk))})`:'')+(recTot?` · recibidas ${money(recTot)}`:'');
  } else {
    $('cmLbl').textContent='Cuenta de comisiones';
    $('cmBal').textContent=money(E.comm);
    $('cmSub').textContent=`Comisiones cobradas ${money(E.commTot)} · este mes ${money(E.commByMonth.get(mk)||0)}${E.own?` · ganancia propia ${smoney(E.own)}`:''}`;
  }
  $('cps').innerHTML=E.cps.map(c=>{ const net=c.gain-c.com; const r=c.capital>0? (c.cap-c.mov)/c.capital-1 : 0;
    return `<div class="cp"><div class="row"><span class="name">${esc(c.name)}</span><span class="tag gold">×${String(c.mult).replace('.',',')}</span></div>
      <div class="v num">${money(c.cap)}</div>
      <dl class="kv"><dt>Inicial</dt><dd>${money(+c.capital)}</dd><dt>Ganancia neta</dt><dd class="${cls(net)}">${smoney(net)}</dd><dt>Comisión pagada</dt><dd>${money(c.com)}</dd><dt>Rentab.</dt><dd class="${cls(r)}">${spct(r)}</dd></dl></div>`; }).join('');
  renderCopCal(E);
  $('copNote').textContent=`Copian la cuenta ${accName(cfg.copyAcc)} desde el ${shortDate(cfg.copyStart)}. La comisión del ${cfg.pct}% solo se cobra en días positivos.`+(linked?` Las comisiones te llegan como depósito a la ${accName(cfg.commAcc)}: allí cuentan como movimiento, no como ganancia de trading.`:'');
  const cs=cfg.copiers||[];
  const head=`<thead><tr><th>Fecha</th><th>Tu resultado</th>${cs.map(c=>`<th>${esc(c.name)} (neto)</th>`).join('')}<th>Comisión</th>${linked?'':'<th>Cta. com.</th>'}</tr></thead>`;
  const body=[...E.rows].reverse().map(r=>`<tr><td>${shortDate(r.date)}</td><td class="${cls(r.pnl)}">${r.base==null?'—':smoney(r.pnl)}</td>${r.cp.map(p=>`<td class="${cls(p.g-p.cm)}">${r.base==null?'—':smoney(p.g-p.cm)}</td>`).join('')}<td>${r.com?money(r.com):'—'}</td>${linked?'':`<td>${money(r.comm)}</td>`}</tr>`).join('');
  $('copTbl').innerHTML = head+'<tbody>'+(body||`<tr><td colspan="${(linked?3:4)+cs.length}" style="text-align:center;font-family:var(--f-display)" class="mut">Sin días desde ${shortDate(cfg.copyStart)}</td></tr>`)+'</tbody>';
  // movement form
  const tg=$('eTarget'), keep=tg.value;
  tg.innerHTML=(linked?'':`<option value="comm">Cuenta de comisiones</option>`)+cs.map(c=>`<option value="${esc(c.id)}">${esc(c.name)}</option>`).join('');
  if ([...tg.options].some(o=>o.value===keep)) tg.value=keep;
  updateKinds();
  $('movSum').textContent = (linked? 'Depósitos y retiros de copiers' : 'Movimientos')+(entries.length?` (${entries.length})`:'');
  const ents=[...entries].sort((a,b)=>a.date<b.date?1:-1);
  $('eList').innerHTML=ents.map(e=>{ const who=e.target==='comm'?'Cta. comisiones':(cs.find(c=>c.id===e.target)||{name:e.target}).name;
    const what=e.kind==='propia'?'Ganancia propia':(+e.amount>=0?'Depósito':'Retiro');
    return `<div class="li" style="cursor:default"><div class="l"><strong>${esc(who)}</strong> · ${what}<small>${shortDate(e.date)}${e.note?' · '+esc(e.note):''}</small></div><div class="r"><span class="${cls(+e.amount)}">${smoney(+e.amount)}</span>${canWrite?`<button class="x" data-del-e="${esc(e._id)}" aria-label="Borrar movimiento">Borrar</button>`:''}</div></div>`; }).join('');
  // settings
  const sAcc=$('sAcc'); sAcc.innerHTML=accountIds().map(a=>`<option value="${esc(a)}">${esc(accName(a))} (${esc(a)})</option>`).join(''); sAcc.value=cfg.copyAcc;
  const sCA=$('sCommAcc'); sCA.innerHTML='<option value="none">Llevarla dentro de la app</option>'+accountIds().map(a=>`<option value="${esc(a)}">${esc(accName(a))} (${esc(a)})</option>`).join(''); sCA.value=linked?cfg.commAcc:'none'; $('sCommWrap').hidden=linked;
  if (document.activeElement?.closest?.('#setCard')==null){
    $('sStart').value=cfg.copyStart; $('sPct').value=cfg.pct; $('sComm').value=cfg.commInitial;
    $('sCopiers').innerHTML=cs.map((c,i)=>`<div class="grid2" data-ci="${i}" style="grid-template-columns:1.2fr 1fr .8fr"><label class="f">Nombre<input type="text" id="sc-n-${i}" value="${esc(c.name)}"></label><label class="f">Capital inicial<input type="number" step="0.01" id="sc-c-${i}" value="${c.capital}"></label><label class="f">Multipl.<input type="number" step="0.1" id="sc-m-${i}" value="${c.mult}"></label></div>`).join('');
  }
}
function updateKinds(){
  const t=$('eTarget').value, k=$('eKind'), keep=k.value;
  k.innerHTML = (t==='comm'?'<option value="propia">Ganancia propia</option>':'')+'<option value="mov">Depósito / retiro</option>';
  if ([...k.options].some(o=>o.value===keep)) k.value=keep;
}
function renderRes(S){
  const st=stats(S);
  const k=(lbl,v,s,c='')=>`<div class="kpi"><div class="eyebrow">${lbl}</div><div class="v num ${c}">${v}</div>${s?`<div class="s">${s}</div>`:''}</div>`;
  $('kpis').innerHTML = !S.length? '<div class="empty card" style="grid-column:1/-1"><strong>Sin datos todavía</strong>Importa tu informe en «Registro».</div>' :
    `<div class="kpi hero"><div class="eyebrow">${sel==='all'?'Balance total':'Balance actual'}</div><div class="v num">${money(st.bal)}</div><div class="s">Desde ${shortDate(S[0].date)} · ${st.traded} días operados</div></div>`+
    k('Resultado total', smoney(st.total), 'Mov. netos '+smoney(st.flows), cls(st.total))+
    k('% acumulado', spct(st.cum), 'Compuesto', cls(st.cum))+
    k('Drawdown máx.', spct(st.mdd), 'Actual '+spct(st.curDd), st.mdd<0?'neg':'')+
    k('Días en verde', st.traded? Math.round(st.g/st.traded*100)+' %':'—', `${st.g} verdes · ${st.r} rojos`)+
    k('Mejor día', st.best? spct(st.best.pct):'—', st.best? shortDate(st.best.date)+' · '+smoney(st.best.pnl):'', 'pos')+
    k('Peor día', st.worst? spct(st.worst.pct):'—', st.worst? shortDate(st.worst.date)+' · '+smoney(st.worst.pnl):'', 'neg')+
    (()=>{ const A=avgRate(S,0); return k('Media diaria', spct(A.geo), smoney(st.avg)+' por día operado', cls(A.geo)); })()+
    k('Operaciones', nf0.format(st.wins+st.losses), (st.wins+st.losses? Math.round(st.wins/(st.wins+st.losses)*100)+' % ganadoras':''));
  drawLine(st.curve);
  const M=months(S); drawBars(M);
  $('monTbl').innerHTML = `<thead><tr><th>Mes</th><th>Resultado</th><th>%</th><th>Días +/−</th></tr></thead><tbody>`+
    ([...M].reverse().map(o=>{const [yy,mm]=o.k.split('-'); return `<tr><td style="font-family:var(--f-display);text-transform:capitalize">${MES[+mm-1]} ${yy}</td><td class="${cls(o.pnl)}">${smoney(o.pnl)}</td><td class="${cls(o.pct)}">${spct(o.pct)}</td><td><span class="pos">${o.g}</span> / <span class="neg">${o.r}</span></td></tr>`}).join('')||'<tr><td colspan="4" class="mut">—</td></tr>')+'</tbody>';
}
function niceTicks(min,max,n=4){
  if (min===max){ min-=1; max+=1; }
  const span=max-min, step0=span/n, mag=Math.pow(10,Math.floor(Math.log10(step0))), r=step0/mag;
  const step=(r<1.5?1:r<3?2:r<7?5:10)*mag; const lo=Math.floor(min/step)*step, hi=Math.ceil(max/step)*step;
  const t=[]; for(let v=lo; v<=hi+step/2; v+=step) t.push(+v.toFixed(10)); return t;
}
function drawLine(curve){
  const host=$('lineChart');
  if (curve.length<2){ host.innerHTML='<div class="empty">Hace falta más de un día para dibujar la evolución.</div>'; return; }
  const isPct=chartMode==='pct';
  $('chTitle').textContent = isPct? 'Rentabilidad acumulada' : 'Evolución del balance';
  const vals=curve.map(c=>isPct?c.cum*100:c.bal);
  const W=520,H=210,L=52,R=10,T=12,B=26;
  const ticks=niceTicks(Math.min(...vals),Math.max(...vals));
  const y0=ticks[0], y1=ticks[ticks.length-1];
  const X=i=>L+(W-L-R)*i/(curve.length-1), Y=v=>T+(H-T-B)*(1-(v-y0)/(y1-y0));
  const pts=vals.map((v,i)=>[X(i),Y(v)]);
  const path='M'+pts.map(p=>p[0].toFixed(1)+','+p[1].toFixed(1)).join('L');
  const area=path+`L${X(curve.length-1).toFixed(1)},${(H-B)}L${L},${H-B}Z`;
  const fmtY=v=> isPct? (v>0?'+':'')+v.toFixed(Math.abs(y1-y0)<5?1:0).replace('.',',')+'%' : nf0.format(v);
  const xIdx=[0,Math.floor((curve.length-1)/2),curve.length-1];
  const last=pts[pts.length-1];
  host.innerHTML=`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${isPct?'Rentabilidad acumulada':'Balance'} por día">
    <defs><linearGradient id="ga" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--gold)" stop-opacity=".28"/><stop offset="1" stop-color="var(--gold)" stop-opacity="0"/></linearGradient></defs>
    ${ticks.map(t=>`<line x1="${L}" x2="${W-R}" y1="${Y(t)}" y2="${Y(t)}" stroke="var(--line)" stroke-width="1" ${isPct&&t===0?'':'stroke-dasharray="2 3"'}/><text class="ax" x="${L-6}" y="${Y(t)+3}" text-anchor="end">${fmtY(t)}</text>`).join('')}
    ${xIdx.map(i=>`<text class="ax" x="${X(i)}" y="${H-8}" text-anchor="${i===0?'start':i===curve.length-1?'end':'middle'}">${shortDate(curve[i].date).slice(0,5)}</text>`).join('')}
    <path d="${area}" fill="url(#ga)"/>
    <path d="${path}" fill="none" stroke="var(--gold)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
    <circle cx="${last[0]}" cy="${last[1]}" r="4" fill="var(--gold)" stroke="var(--surface)" stroke-width="2"/>
    <g id="hov" style="display:none"><line id="hvl" y1="${T}" y2="${H-B}" stroke="var(--ink2)" stroke-width="1" stroke-dasharray="3 3"/><circle id="hvc" r="4.5" fill="var(--gold)" stroke="var(--surface)" stroke-width="2"/></g>
    <rect x="${L}" y="${T}" width="${W-L-R}" height="${H-T-B}" fill="transparent" id="hit"/>
  </svg><div class="tip" id="ltip" hidden></div>`;
  const svg=host.querySelector('svg'), hit=host.querySelector('#hit'), tip=host.querySelector('#ltip');
  const move=ev=>{ const r=svg.getBoundingClientRect(); const px=(ev.clientX-r.left)*W/r.width;
    const i=Math.max(0,Math.min(curve.length-1,Math.round((px-L)/(W-L-R)*(curve.length-1)))); const [x,y]=pts[i];
    host.querySelector('#hov').style.display=''; host.querySelector('#hvl').setAttribute('x1',x); host.querySelector('#hvl').setAttribute('x2',x);
    host.querySelector('#hvc').setAttribute('cx',x); host.querySelector('#hvc').setAttribute('cy',y);
    tip.hidden=false; tip.style.left=(x*r.width/W)+'px'; tip.style.top=(y*r.height/H)+'px';
    tip.textContent=shortDate(curve[i].date)+' · '+(isPct? spct(curve[i].cum) : money(curve[i].bal)); };
  hit.addEventListener('pointermove',move); hit.addEventListener('pointerdown',move);
  hit.addEventListener('pointerleave',()=>{host.querySelector('#hov').style.display='none'; tip.hidden=true;});
}
function drawBars(M){
  const host=$('barChart');
  if (!M.length){ host.innerHTML=''; return; }
  const W=520,H=180,L=52,R=10,T=12,B=26;
  const vals=M.map(o=>o.pnl); const ticks=niceTicks(Math.min(0,...vals),Math.max(0,...vals));
  const y0=ticks[0], y1=ticks[ticks.length-1]; const Y=v=>T+(H-T-B)*(1-(v-y0)/(y1-y0));
  const n=M.length, slot=(W-L-R)/n, bw=Math.min(48, slot*0.62);
  host.innerHTML=`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Resultado por mes">
    ${ticks.map(t=>`<line x1="${L}" x2="${W-R}" y1="${Y(t)}" y2="${Y(t)}" stroke="var(--line)" ${t===0?'stroke-width="1.5"':'stroke-dasharray="2 3"'}/><text class="ax" x="${L-6}" y="${Y(t)+3}" text-anchor="end">${nf0.format(t)}</text>`).join('')}
    ${M.map((o,i)=>{ const x=L+slot*i+(slot-bw)/2, ya=Y(Math.max(0,o.pnl)), yb=Y(Math.min(0,o.pnl)); const h=Math.max(1,yb-ya); const [yy,mm]=o.k.split('-');
      const rad=Math.min(4,h/2); const up=o.pnl>=0;
      const d= up? `M${x},${yb}V${ya+rad}Q${x},${ya} ${x+rad},${ya}H${x+bw-rad}Q${x+bw},${ya} ${x+bw},${ya+rad}V${yb}Z` : `M${x},${ya}V${yb-rad}Q${x},${yb} ${x+rad},${yb}H${x+bw-rad}Q${x+bw},${yb} ${x+bw},${yb-rad}V${ya}Z`;
      return `<g class="bar" data-i="${i}"><rect x="${L+slot*i}" y="${T}" width="${slot}" height="${H-T-B}" fill="transparent"/><path d="${d}" fill="${up?'var(--pos)':'var(--neg)'}"/><text class="ax" x="${x+bw/2}" y="${H-8}" text-anchor="middle">${MES[+mm-1].slice(0,3)} ${yy.slice(2)}</text></g>`; }).join('')}
  </svg><div class="tip" id="btip" hidden></div>`;
  const svg=host.querySelector('svg'), tip=host.querySelector('#btip');
  host.querySelectorAll('.bar').forEach(g=>{ const show=()=>{ const i=+g.dataset.i, o=M[i]; const r=svg.getBoundingClientRect();
    const cx=L+slot*i+slot/2; tip.hidden=false; tip.style.left=(cx*r.width/W)+'px'; tip.style.top=(Y(Math.max(0,o.pnl))*r.height/H)+'px';
    tip.textContent=`${MES[+o.k.slice(5)-1]}: ${smoney(o.pnl)} (${spct(o.pct)})`; };
    g.addEventListener('pointerenter',show); g.addEventListener('pointerdown',show); g.addEventListener('pointerleave',()=>tip.hidden=true); });
}
function render(){
  renderAccs();
  const S=series(sel);
  renderHeader(S);
  if (view==='cal') renderCal(S);
  if (view==='reg') renderReg(S);
  if (view==='cop') renderCop();
  if (view==='res') renderRes(S);
  if (view==='exp') renderExp(S);
  document.querySelectorAll('#importCard,#manualCard,#transferCard,#movCard,#sSave').forEach(el=>{ if(!canWrite && db) el.hidden=true; });
  $('roNote').hidden = !(db && !canWrite);
}

/* ---------- day sheet ---------- */
function openDay(date){
  const S=series(sel); const x=S.find(r=>r.date===date); if(!x) return;
  const E = (sel===cfg.copyAcc || sel==='all') ? copyEngine().byDate.get(date) : null;
  const trades=(x.parts||[]).flatMap(p=>(p.trades||[]).map(t=>({...t,acc:p.acc}))).sort((a,b)=>a.t<b.t?-1:1);
  const flows=(x.parts||[]).flatMap(p=>(p.flows||[]).map(f=>({...f,acc:p.acc})));
  const single=(x.parts||[]).length===1? x.parts[0] : null;
  const host=$('sheetHost');
  host.innerHTML=`<div class="scrim" id="scrim"><div class="sheet" role="dialog" aria-modal="true" aria-label="${longDate(date)}">
    <div class="grab"></div>
    <div class="row"><div><div class="eyebrow">${esc(sel==='all'?'Ambas cuentas':accName(sel))}</div><h2>${cap1(longDate(date))}</h2></div><button class="btn ghost" id="shClose">Cerrar</button></div>
    <div><div class="big ${cls(x.pnl)}">${smoney(x.pnl)}</div><div class="${cls(x.pct)} num">${spct(x.pct)} del día</div></div>
    <dl class="kv card"><dt>Balance inicial</dt><dd>${money(x.start)}</dd>${x.flow?`<dt>Depósitos / retiros</dt><dd class="${cls(x.flow)}">${smoney(x.flow)}</dd>`:''}<dt>Balance final</dt><dd>${money(x.end)}</dd><dt>Operaciones cerradas</dt><dd>${x.n||0}${x.n?` <span class="mut">(${x.wins||0} + / ${x.losses||0} −)</span>`:''}</dd></dl>
    ${flows.length?`<div class="stack"><h3>Movimientos</h3><div class="list card" style="padding:4px 12px">${flows.map(f=>`<div class="li" style="cursor:default"><div class="l">${esc(flowLabel(f.c))}<small>${f.t}${sel==='all'?' · '+esc(accName(f.acc)):''}</small></div><div class="r ${cls(f.a)}">${smoney(f.a)}</div></div>`).join('')}</div></div>`:''}
    ${E && E.base!=null?`<div class="stack"><h3>Copiers ese día</h3><dl class="kv card">${(cfg.copiers||[]).map((c,i)=>`<dt>${esc(c.name)}</dt><dd class="${cls(E.cp[i].g)}">${smoney(E.cp[i].g)}${E.cp[i].cm?` <span class="mut">· com. ${money(E.cp[i].cm)}</span>`:''}</dd>`).join('')}<dt>Comisión total</dt><dd>${money(E.com)}</dd></dl></div>`:''}
    ${!trades.length && x.n?`<p class="hint" style="margin:0">${x.n} operaciones cerradas. El detalle de cada una no está guardado para este día (importado de Myfxbook).</p>`:''}
    ${trades.length?`<div class="stack"><h3>Operaciones</h3><div class="tbl-wrap"><table><thead><tr><th>Cierre</th><th>Tipo</th><th>Lotes</th><th>Precio</th><th>Resultado</th></tr></thead><tbody>${trades.map(t=>`<tr><td>${t.t}</td><td><span class="tag ${t.s}">${t.s==='buy'?'Compra':'Venta'}</span></td><td>${String(t.v).replace('.',',')}</td><td>${nf2.format(t.pr)}</td><td class="${cls(t.p)}">${smoney(t.p)}</td></tr>`).join('')}</tbody></table></div></div>`:''}
    ${single && canWrite?`<div class="stack"><label class="f">Nota del día<textarea id="shNote">${esc(single.note||'')}</textarea></label>
      <div class="row">${single.src==='manual'?`<button class="btn danger" id="shDel">Borrar día</button>`:`<span class="hint">Día importado de ${single.src==='myfxbook'?'Myfxbook':'MT5'}.</span>`}<button class="btn" id="shSave">Guardar nota</button></div><div id="shMsg" class="msg" hidden></div></div>`
      : (single && single.note? `<div class="card"><div class="eyebrow">Nota</div>${esc(single.note)}</div>`:'')}
  </div></div>`;
  const close=()=>{ host.innerHTML=''; document.removeEventListener('keydown',onKey); };
  const onKey=e=>{ if(e.key==='Escape') close(); };
  document.addEventListener('keydown',onKey);
  $('scrim').addEventListener('click',e=>{ if(e.target.id==='scrim') close(); });
  $('shClose').addEventListener('click',close); $('shClose').focus();
  if ($('shSave')) $('shSave').addEventListener('click',async()=>{
    try{ await db.collection('days').doc(single.acc+'_'+date).update({note:$('shNote').value.trim()}); showMsg($('shMsg'),'Nota guardada.','ok'); }
    catch(e){ showMsg($('shMsg'),'No se pudo guardar la nota. Inténtalo de nuevo.','err'); } });
  if ($('shDel')){ let armed=false; $('shDel').addEventListener('click',async()=>{
    if(!armed){ armed=true; $('shDel').textContent='Pulsa otra vez para borrar'; return; }
    try{ await db.collection('days').doc(single.acc+'_'+date).delete(); close(); } catch(e){ showMsg($('shMsg'),'No se pudo borrar.','err'); } }); }
}

/* ---------- MT5 import ---------- */
function parseReport(aoa){
  const txt=v=>v==null?'':String(v).trim();
  let acc=null, usc=false;
  for (const r of aoa.slice(0,15)){ const a=txt(r[0]); if (/^(cuenta|account)/i.test(a)){ const v=r.slice(1).map(txt).find(Boolean)||''; const m=v.match(/(\d{4,})/); if(m){acc=m[1]; usc=/\bUSC\b/i.test(v);} break; } }
  if (!acc) throw new Error('No encuentro el número de cuenta. ¿Es un informe de MT5 (Historial → Informe)?');
  const si=aoa.findIndex(r=>/^(transacciones|deals|ofertas)$/i.test(txt(r[0])));
  if (si<0) throw new Error('El archivo no tiene la sección «Transacciones». Exporta el informe con Historial → Informe → Open XML.');
  const hdr=aoa[si+1].map(h=>txt(h).toLowerCase());
  const col=(names,fb)=>{ const i=hdr.findIndex(h=>names.some(n=>h===n||h.startsWith(n))); return i>=0?i:fb; };
  const C={time:col(['fecha/hora','time'],0), type:col(['tipo','type'],3), dir:col(['dirección','direction'],4), vol:col(['volumen','volume'],5), price:col(['precio','price'],6),
           comm:col(['comisión','commission'],8), fee:col(['tasa','fee'],9), swap:col(['swap'],10), profit:col(['beneficio','profit'],11), bal:col(['balance'],12), cmt:col(['comentario','comment'],13)};
  const div=usc?100:1, num=v=>typeof v==='number'?v:(parseFloat(String(v??'').replace(/\s/g,'').replace(',','.'))||0);
  const out=new Map();
  for (let i=si+2;i<aoa.length;i++){
    const r=aoa[i]; const t=txt(r[C.time]); const m=t.match(/^(\d{4})\.(\d\d)\.(\d\d)\s+(\d\d:\d\d)/); if(!m) break;
    const date=`${m[1]}-${m[2]}-${m[3]}`, tm=m[4], typ=txt(r[C.type]).toLowerCase(), dir=txt(r[C.dir]).toLowerCase();
    if(!out.has(date)) out.set(date,{acc,date,pnl:0,flow:0,end:0,n:0,wins:0,losses:0,trades:[],flows:[],src:'mt5'});
    const d=out.get(date); const comm=num(r[C.comm]), fee=num(r[C.fee]), swap=num(r[C.swap]), prof=num(r[C.profit]), bal=num(r[C.bal]);
    if (typ==='buy'||typ==='sell'){
      const p=comm+fee+swap+prof; d.pnl+=p;
      if (dir!=='in'){ d.n++; if(p>0)d.wins++; else if(p<0)d.losses++;
        d.trades.push({t:tm, s:typ==='sell'?'buy':'sell', v:num(r[C.vol]), pr:num(r[C.price]), p:Math.round(p/div*100)/100}); }
    } else { d.flow+=prof; d.flows.push({t:tm, a:Math.round(prof/div*100)/100, c:txt(r[C.cmt])||typ}); }
    d.end=bal;
  }
  const res=[...out.values()].map(d=>({...d, pnl:Math.round(d.pnl/div*100)/100, flow:Math.round(d.flow/div*100)/100, end:Math.round(d.end/div*100)/100}));
  if (!res.length) throw new Error('No hay transacciones en el informe. Revisa el periodo elegido en MT5.');
  return {acc, usc, days:res};
}
async function importFile(file){
  const msg=$('impMsg'); showMsg(msg,'Leyendo '+file.name+'…');
  try{
    if (typeof XLSX==='undefined') throw new Error('No se pudo cargar el lector de Excel. Recarga la página.');
    const wb=XLSX.read(await file.arrayBuffer(),{type:'array'});
    const aoa=XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]],{header:1,raw:true,defval:null,blankrows:true});
    const rep=parseReport(aoa);
    const existing=new Map(days.filter(d=>d.acc===rep.acc).map(d=>[d.date,d]));
    let added=0, updated=0, kept=0, i=0;
    for (const d of rep.days){
      i++; showMsg(msg,`Guardando ${i} de ${rep.days.length}…`);
      const ex=existing.get(d.date);
      if (ex && ex.src==='mt5' && (ex.n||0)>d.n){ kept++; continue; }
      await db.collection('days').doc(rep.acc+'_'+d.date).set({...d, note: ex&&ex.note&&ex.note!=='Desde captura de MT5'? ex.note : ''});
      ex? updated++ : added++;
    }
    if (!cfg.accounts || !cfg.accounts[rep.acc]){
      const nc=structuredClone(cfg); nc.accounts=nc.accounts||{}; nc.accounts[rep.acc]={name:'Cuenta '+rep.acc.slice(-4)}; await db.doc('config/main').set(nc);
    }
    const first=rep.days[0].date, last=rep.days[rep.days.length-1].date;
    showMsg(msg,`Cuenta ${rep.acc}${rep.usc?' (céntimos → pasada a $)':''}: ${added} días nuevos, ${updated} actualizados${kept?`, ${kept} conservados`:''}. Periodo ${shortDate(first)} – ${shortDate(last)}.`,'ok');
    sel=rep.acc; localSet('acc',sel); monthKey=last.slice(0,7);
  } catch(e){ showMsg(msg, e && e.message ? e.message : 'No se pudo leer el archivo.', 'err'); }
  $('fileIn').value='';
}

/* ---------- events ---------- */
$('accs').addEventListener('click',e=>{ const b=e.target.closest('.acc'); if(!b) return; sel=b.dataset.a; localSet('acc',sel); render(); });
document.querySelector('nav.tabs').addEventListener('click',e=>{ const b=e.target.closest('button'); if(!b) return; view=b.dataset.v;
  document.querySelectorAll('nav.tabs button').forEach(x=>x.setAttribute('aria-selected',x===b));
  ['cal','reg','cop','res','exp'].forEach(v=>$('v-'+v).hidden = v!==view); window.scrollTo(0,0); render(); });
$('mPrev').addEventListener('click',()=>{ const d=pd(monthKey+'-01'); d.setUTCMonth(d.getUTCMonth()-1); monthKey=fd(d).slice(0,7); render(); });
$('mNext').addEventListener('click',()=>{ const d=pd(monthKey+'-01'); d.setUTCMonth(d.getUTCMonth()+1); monthKey=fd(d).slice(0,7); render(); });
$('xPer').addEventListener('click',e=>{ const b=e.target.closest('[data-p]'); if(!b) return; xPer=b.dataset.p; if(xPer==='custom' && xCustom==null){ xCustom=+(avgRate(series(sel),0).geo*100).toFixed(2); $('xCustom').value=xCustom; } render(); });
$('xCustom').addEventListener('input',()=>{ const v=parseFloat($('xCustom').value); if(!isNaN(v)){ xCustom=v; render(); } });
$('xPrev').addEventListener('click',()=>{ const d=pd(xMonth+'-01'); d.setUTCMonth(d.getUTCMonth()-1); xMonth=fd(d).slice(0,7); render(); });
$('xNext').addEventListener('click',()=>{ const d=pd(xMonth+'-01'); d.setUTCMonth(d.getUTCMonth()+1); xMonth=fd(d).slice(0,7); render(); });
$('xCal').addEventListener('click',e=>{ const c=e.target.closest('[data-d]'); if(c) openExpDay(c.dataset.d); });
$('copSeg').addEventListener('click',e=>{ const b=e.target.closest('[data-c]'); if(!b) return; copSel=b.dataset.c; render(); });
$('cPrev').addEventListener('click',()=>{ const d=pd(copMonth+'-01'); d.setUTCMonth(d.getUTCMonth()-1); copMonth=fd(d).slice(0,7); render(); });
$('cNext').addEventListener('click',()=>{ const d=pd(copMonth+'-01'); d.setUTCMonth(d.getUTCMonth()+1); copMonth=fd(d).slice(0,7); render(); });
$('copCal').addEventListener('click',e=>{ const c=e.target.closest('[data-d]'); if(c) openCopDay(c.dataset.d); });
$('cal').addEventListener('click',e=>{ const c=e.target.closest('[data-d]'); if(c) openDay(c.dataset.d); });
$('regList').addEventListener('click',e=>{ const c=e.target.closest('[data-d]'); if(c) openDay(c.dataset.d); });
$('regList').addEventListener('keydown',e=>{ if(e.key==='Enter'){ const c=e.target.closest('[data-d]'); if(c) openDay(c.dataset.d); }});
$('fileIn').addEventListener('change',async e=>{ const f=e.target.files[0]; if(!f) return;
  if (/\.json$/i.test(f.name) || f.type==='application/json'){ const m=$('impMsg');
    try{ const data=JSON.parse(await f.text()); if(!data||!Array.isArray(data.days)) throw new Error('El archivo no es una copia de esta app.');
      showMsg(m,'Restaurando copia…'); const n=await restoreBackup(data); showMsg(m,`Copia restaurada: ${n.days} días, ${n.entries} movimientos${n.config?' y ajustes':''}.`,'ok'); }
    catch(err){ showMsg(m, err.message||'No se pudo leer la copia.','err'); }
    e.target.value=''; return; }
  importFile(f); });
$('mAcc').addEventListener('change',updateStartField);
/* Captura de MT5 → rellena el formulario «A mano» (no guarda nada hasta pulsar Guardar). */
let shot=null;
$('shotIn').addEventListener('change',async e=>{ const f=e.target.files[0]; e.target.value=''; if(!f) return;
  const m=$('shotMsg'); shot=null; showMsg(m,'Leyendo la captura… (la primera vez tarda un poco más)');
  try{
    const r=await readShot(f,p=>showMsg(m,`Leyendo la captura… ${Math.round(p*100)} %`));
    if (r.profit==null && r.balance==null) throw new Error('No encuentro «Beneficio» ni «Balance» en la captura. Usa la de Historial → Posiciones con el resumen arriba.');
    // Las cuentas son en céntimos (USC): 100 USC = 1 $.
    const raw=(r.profit||0)+(r.swap||0)+(r.commission||0), dep=r.deposit||0;
    const pnl=Math.round(raw)/100, flow=Math.round(dep)/100;
    const warn=[];
    if (r.balance!=null && Math.abs(raw+dep-r.balance)>0.5) warn.push(`el balance del resumen (${nf2.format(r.balance)}) no cuadra con la suma; revisa los números`);
    const tSum=r.trades.reduce((a,b)=>a+b,0);
    const tradesOk = r.trades.length && r.profit!=null && Math.abs(tSum-r.profit)<0.5;
    if (r.trades.length && !tradesOk) warn.push('no he leído bien todas las operaciones (el nº de operaciones no se guardará)');
    let date=$('mDate').value;
    if (r.dates.length===1) { date=r.dates[0]; $('mDate').value=date; }
    else if (r.dates.length>1) warn.push(`la captura tiene ${r.dates.length} días (${r.dates.map(shortDate).join(', ')}): el resultado es la suma de todos. Filtra por un solo día en MT5`);
    else warn.push('no veo la fecha; comprueba que es la correcta');
    $('mPnl').value=pnl.toFixed(2); $('mFlow').value=flow?flow.toFixed(2):'';
    if (!$('mNote').value) $('mNote').value='Desde captura de MT5';
    shot={date,pnl,n:tradesOk?r.trades.length:0,wins:tradesOk?r.trades.filter(x=>x>0).length:0,losses:tradesOk?r.trades.filter(x=>x<0).length:0};
    const ops = tradesOk? ` · ${shot.n} operaciones (${shot.wins} + / ${shot.losses} −)` : '';
    showMsg(m,`Leído ${shortDate(date)}: ${nf2.format(raw)} USC = ${smoney(pnl)}${flow?` · movimiento ${smoney(flow)}`:''}${ops}.`+
      (warn.length? ' Ojo: '+warn.join('; ')+'.' : '')+' Comprueba la cuenta y pulsa «Guardar día».', warn.length?'err':'ok');
    $('mAcc').focus();
  }catch(err){ showMsg(m, err.message||'No se pudo leer la captura.','err'); }
});
$('mDate').value=todayStr(); $('eDate').value=todayStr();
$('mSave').addEventListener('click',async()=>{
  const a=$('mAcc').value, date=$('mDate').value, msg=$('mMsg');
  if(!a||!date){ showMsg(msg,'Elige cuenta y fecha.','err'); return; }
  const pnl=parseFloat($('mPnl').value)||0, flow=parseFloat($('mFlow').value)||0;
  const ex=days.find(d=>d.acc===a&&d.date===date);
  // Día que venía del informe: se puede sustituir (p. ej. si seguiste operando después de exportarlo), con doble pulsación.
  const btn=$('mSave'), armKey=a+'_'+date;
  if (ex && ex.src!=='manual' && btn.dataset.armed!==armKey){
    btn.dataset.armed=armKey; btn.textContent='Pulsa otra vez para sustituir';
    showMsg(msg,`Ese día ya venía del informe (${smoney(+ex.pnl||0)}). Si has seguido operando después, pulsa otra vez y se guardarán estos datos en su lugar.`,'err');
    setTimeout(()=>{ if(btn.dataset.armed===armKey){ btn.dataset.armed=''; btn.textContent='Guardar día'; } },10000);
    return;
  }
  btn.dataset.armed=''; btn.textContent='Guardar día';
  const doc={acc:a,date,pnl,flow,n:0,wins:0,losses:0,trades:[],flows:flow?[{t:'',a:flow,c:flow>0?'Depósito':'Retiro'}]:[],src:'manual',note:$('mNote').value.trim()};
  // Si los datos vienen de una captura y no se han tocado, guarda también el nº de operaciones.
  if (shot && shot.date===date && Math.abs(shot.pnl-pnl)<0.005){ doc.n=shot.n; doc.wins=shot.wins; doc.losses=shot.losses; doc.via='captura'; }
  if (!$('mStartWrap').hidden){ const s=parseFloat($('mStart').value); if(isNaN(s)){ showMsg(msg,'Es el primer día de esta cuenta: escribe el balance inicial.','err'); return; } doc.start=s; }
  else if (ex && typeof ex.start==='number') doc.start=ex.start;
  try{ await db.collection('days').doc(a+'_'+date).set(doc); showMsg(msg,`Guardado ${shortDate(date)}: ${smoney(pnl)}.`,'ok'); shot=null; showMsg($('shotMsg'),''); $('mPnl').value=''; $('mFlow').value=''; $('mNote').value=''; }
  catch(e){ showMsg(msg,'No se pudo guardar. Inténtalo de nuevo.','err'); }
});
$('tDate').value=todayStr();
$('tSave').addEventListener('click',async()=>{
  const from=$('tFrom').value, to=$('tTo').value, date=$('tDate').value, amt=Math.round((parseFloat($('tAmt').value)||0)*100)/100, msg=$('tMsg');
  if(!from||!to||from===to){ showMsg(msg,'Elige dos cuentas distintas.','err'); return; }
  if(!(amt>0)||!date){ showMsg(msg,'Escribe un importe y la fecha.','err'); return; }
  const imported=[from,to].filter(a=>{ const d=days.find(x=>x.acc===a&&x.date===date); return d && d.src!=='manual'; });
  if(imported.length){ showMsg(msg,`Ese día de ${imported.map(accName).join(' y ')} ya viene de MT5/Myfxbook, que ya incluye la transferencia. No hace falta añadirla.`,'err'); return; }
  const apply=async(a,v,label)=>{
    const ex=days.find(x=>x.acc===a&&x.date===date);
    const flows=[...((ex&&ex.flows)||[]), {t:'',a:v,c:label}];
    const doc = ex ? {...ex, flow:(+ex.flow||0)+v, flows}
      : {acc:a,date,pnl:0,flow:v,n:0,wins:0,losses:0,trades:[],flows,src:'manual',note:'Transferencia'};
    await db.collection('days').doc(a+'_'+date).set(doc);
  };
  try{
    await apply(from,-amt,'Transferencia a '+accName(to));
    await apply(to,amt,'Transferencia desde '+accName(from));
    showMsg(msg,`Transferidos ${money(amt)} de ${accName(from)} a ${accName(to)} el ${shortDate(date)}.`,'ok'); $('tAmt').value='';
  }catch(e){ showMsg(msg,'No se pudo guardar. Inténtalo de nuevo.','err'); }
});
$('eTarget').addEventListener('change',updateKinds);
$('sCommAcc').addEventListener('change',()=>{ $('sCommWrap').hidden = $('sCommAcc').value!=='none'; });
$('eSave').addEventListener('click',async()=>{
  const amt=parseFloat($('eAmt').value), date=$('eDate').value, msg=$('eMsg');
  if(isNaN(amt)||!amt||!date){ showMsg(msg,'Escribe la fecha y un importe distinto de cero.','err'); return; }
  try{ await db.collection('entries').add({date,target:$('eTarget').value,kind:$('eKind').value,amount:amt,note:$('eNote').value.trim()}); showMsg(msg,'Movimiento añadido.','ok'); $('eAmt').value=''; $('eNote').value=''; }
  catch(e){ showMsg(msg,'No se pudo guardar. Inténtalo de nuevo.','err'); }
});
$('eList').addEventListener('click',async e=>{ const b=e.target.closest('[data-del-e]'); if(!b) return;
  if(!b.dataset.armed){ b.dataset.armed='1'; b.textContent='¿Seguro?'; return; }
  try{ await db.collection('entries').doc(b.dataset.delE).delete(); }catch(err){} });
$('sSave').addEventListener('click',async()=>{
  const nc=structuredClone(cfg); const msg=$('sMsg');
  nc.copyAcc=$('sAcc').value; nc.copyStart=$('sStart').value||nc.copyStart; nc.pct=parseFloat($('sPct').value)||0; nc.commInitial=parseFloat($('sComm').value)||0; nc.commAcc=$('sCommAcc').value;
  nc.copiers=(nc.copiers||[]).map((c,i)=>({...c,name:$('sc-n-'+i).value.trim()||c.name,capital:parseFloat($('sc-c-'+i).value)||0,mult:parseFloat($('sc-m-'+i).value)||1}));
  try{ await db.doc('config/main').set(nc); showMsg(msg,'Ajustes guardados.','ok'); }catch(e){ showMsg(msg,'No se pudo guardar.','err'); }
});
$('chBal').addEventListener('click',()=>{ chartMode='bal'; $('chBal').setAttribute('aria-pressed','true'); $('chPct').setAttribute('aria-pressed','false'); render(); });
$('chPct').addEventListener('click',()=>{ chartMode='pct'; $('chPct').setAttribute('aria-pressed','true'); $('chBal').setAttribute('aria-pressed','false'); render(); });

/* ---------- boot ---------- */
render();
let unsubs=[];
function stopListeners(){ unsubs.forEach(f=>{ try{f();}catch(e){} }); unsubs=[]; }
onAuthStateChanged(auth, user=>{
  stopListeners();
  if (window.SenalesApp) { try{ SenalesApp.onAuth(user ? (user.email||user.displayName||'Sesión iniciada') : ''); }catch(e){} }
  if(!user){ $('login').hidden=false; db=null; days=[]; entries=[]; cfg=structuredClone(DEFAULT_CFG); render(); return; }
  $('login').hidden=true;
  $('userLine').textContent='Sesión iniciada como '+(user.email||user.displayName||'tu cuenta')+'. Los datos se sincronizan solos.';
  db=makeDb(user.uid); canWrite=true;
  unsubs.push(db.collection('days').limit(1000).onSnapshot(s=>{ days=s.docs.map(d=>d.data()); render(); }, e=>console.error(e)));
  unsubs.push(db.collection('entries').limit(1000).onSnapshot(s=>{ entries=s.docs.map(d=>({...d.data(),_id:d.id})); render(); }, e=>console.error(e)));
  unsubs.push(db.doc('config/main').onSnapshot(s=>{ cfg = s.exists ? {...structuredClone(DEFAULT_CFG), ...s.data()} : structuredClone(DEFAULT_CFG); render(); }, e=>console.error(e)));
});
// Dentro de la app Android, Google no deja iniciar sesión en la página: lo hace la app y nos pasa el token.
window.nativeSignIn = async token => {
  try{ await signInWithCredential(auth, GoogleAuthProvider.credential(token)); $('loginMsg').hidden=true; }
  catch(e){ showMsg($('loginMsg'),'No se pudo iniciar sesión ('+(e&&e.code||'error')+').','err'); }
};
window.nativeSignInError = msg => showMsg($('loginMsg'), msg, 'err');
window.appSignOut = () => signOut(auth);
$('gbtn').addEventListener('click', async()=>{
  const m=$('loginMsg'); showMsg(m,'Abriendo Google…');
  if (window.SenalesApp){ SenalesApp.googleSignIn(); return; }
  try{ await signInWithPopup(auth, provider); m.hidden=true; }
  catch(e){
    if (e && (e.code==='auth/popup-blocked' || e.code==='auth/operation-not-supported-in-this-environment' || e.code==='auth/cancelled-popup-request')){ try{ await signInWithRedirect(auth, provider); return; }catch(e2){ e=e2; } }
    showMsg(m, e && e.code==='auth/popup-closed-by-user' ? 'Has cerrado la ventana de Google. Vuelve a intentarlo.' : 'No se pudo iniciar sesión ('+(e&&e.code||'error')+').', 'err');
  }
});
getRedirectResult(auth).catch(e=>showMsg($('loginMsg'),'No se pudo iniciar sesión ('+(e&&e.code||'error')+').','err'));
$('logout').addEventListener('click', async e=>{ const b=e.currentTarget; if(!b.dataset.armed){ b.dataset.armed='1'; b.textContent='Pulsa otra vez para salir'; return; } await signOut(auth); b.dataset.armed=''; b.textContent='Cerrar sesión'; });
$('bkExport').addEventListener('click',()=>{
  const data={app:'registro-xauusd',version:1,exported:new Date().toISOString(),days,entries:entries.map(({_id,...r})=>({...r,id:_id})),config:cfg};
  if (window.SenalesApp){ SenalesApp.saveFile('registro-xauusd-copia-'+todayStr()+'.json', JSON.stringify(data,null,1)); showMsg($('bkMsg'),`Copia guardada: ${days.length} días, ${entries.length} movimientos.`,'ok'); return; }
  const blob=new Blob([JSON.stringify(data,null,1)],{type:'application/json'}); const a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download='registro-xauusd-copia-'+todayStr()+'.json'; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(()=>URL.revokeObjectURL(a.href),4000); showMsg($('bkMsg'),`Copia descargada: ${days.length} días, ${entries.length} movimientos.`,'ok');
});
$('bkIn').addEventListener('change', async e=>{
  const f=e.target.files[0]; const m=$('bkMsg'); if(!f) return;
  try{
    const data=JSON.parse(await f.text());
    if(!data || !Array.isArray(data.days)) throw new Error('El archivo no es una copia de esta app.');
    showMsg(m,'Restaurando…');
    const n=await restoreBackup(data);
    showMsg(m,`Restaurado: ${n.days} días, ${n.entries} movimientos${n.config?' y ajustes':''}.`,'ok');
  }catch(err){ showMsg(m, err.message||'No se pudo leer la copia.','err'); }
  e.target.value='';
});
})();
