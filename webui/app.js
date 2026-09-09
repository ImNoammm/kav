/* Kav, the whole app runs on the device. After the single bundle fetch there
   are no further network calls: routing, search, departure boards and the map
   are all local. That is the architecture claim, made testable. */

const MIN_CHANGE = 120, INF = 0x7fffffff, WALK_MS = 1.2;
const $ = s => document.querySelector(s);
// the app lives inside a device frame on desktop, so measure the frame
// ?r=tlv for the metro bundle, ?r=il (default) for the national one
const REGION = new URLSearchParams(location.search).get("r") || "il";
/* Moovit shows live times green and scheduled times grey. Israel publishes no
   open real-time feed (MOT SIRI is IP-whitelisted), so
   NOTHING is live today and every time here is honestly scheduled grey. This
   flag is the real hook: when the relay lands it flips per trip. `?rt=demo`
   forces it on purely to preview the styling, it is simulated, not data. */
const RT_DEMO = new URLSearchParams(location.search).get("rt") === "demo";
const isLive = trip => RT_DEMO && (trip % 3 !== 0);
const appWidth = () => (document.querySelector(".app")||document.body).clientWidth;
const esc = s => String(s).replace(/[&<>"]/g, m => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[m]));
const rtl = s => /[֐-ࣿ]/.test(s) ? "rtl" : "ltr";

/* varint reader */
let B, P = 0;
const td = new TextDecoder();
function vi(){ let sh=0,r=0,b; do{ b=B[P++]; r |= (b&0x7f)<<sh; sh+=7; }while(b&0x80); return (r>>>1) ^ -(r&1); }
function vs(){ const n=vi(); const s=td.decode(B.subarray(P,P+n)); P+=n; return s; }
class Vec{
  constructor(n=1<<16){ this.a=new Int32Array(n); this.n=0; }
  push(v){ if(this.n===this.a.length){ const b=new Int32Array(this.a.length*2); b.set(this.a); this.a=b; } this.a[this.n++]=v; }
  get view(){ return this.a.subarray(0,this.n); }
}

const N = {};

/* decode */
function parse(buf){
  B = new Uint8Array(buf); P = 0;
  if (String.fromCharCode(B[0],B[1],B[2],B[3]) !== "KAV3") throw new Error("bad bundle");
  P = 4;
  const nS = vi(), nR = vi(), nT = vi(), nC = vi();
  N.city = new Array(nC);
  for (let i=0;i<nC;i++) N.city[i]=vs();

  N.name = new Array(nS); N.lat = new Float64Array(nS);
  N.lon = new Float64Array(nS); N.code = new Int32Array(nS); N.cityOf = new Int32Array(nS);
  let la=0, lo=0;
  for (let i=0;i<nS;i++){
    la+=vi(); lo+=vi();
    N.lat[i]=la/1e5; N.lon[i]=lo/1e5; N.code[i]=vi(); N.cityOf[i]=vi(); N.name[i]=vs();
  }
  N.sMerc = new Float64Array(nS);
  for (let i=0;i<nS;i++) N.sMerc[i]=Math.log(Math.tan(Math.PI/4+N.lat[i]*Math.PI/360));
  N.hay = new Array(nS);   // lowercase once, not per keystroke

  N.rShort = new Array(nR); N.rLong = new Array(nR); N.rType = new Int32Array(nR);
  for (let i=0;i<nR;i++){ N.rShort[i]=vs(); N.rLong[i]=vs(); N.rType[i]=vi(); }

  N.tripRoute = new Int32Array(nT); N.tripStart = new Int32Array(nT+1);
  const stStop=new Vec(1<<21), stArr=new Vec(1<<21), stDep=new Vec(1<<21);
  for (let t=0;t<nT;t++){
    N.tripRoute[t]=vi();
    const n=vi(); let pt=vi(), ps=0;
    N.tripStart[t]=stStop.n;
    for (let k=0;k<n;k++){
      const a=pt+vi(), d=a+vi(), s=ps+vi();
      stArr.push(a); stDep.push(d); stStop.push(s); pt=d; ps=s;
    }
  }
  N.tripStart[nT]=stStop.n;
  N.stStop=stStop.view; N.stArr=stArr.view; N.stDep=stDep.view;

  N.xStart = new Int32Array(nS+1);
  const xTo=new Vec(1<<18), xW=new Vec(1<<18);
  for (let i=0;i<nS;i++){
    N.xStart[i]=xTo.n;
    const c=vi();
    for (let k=0;k<c;k++){ xTo.push(i+vi()); xW.push(vi()); }
  }
  N.xStart[nS]=xTo.n; N.xTo=xTo.view; N.xW=xW.view;

  // trip -> road line, so a ride leg can follow the road it actually drives
  const nTS=vi();
  N.tripShape=new Int32Array(nTS);
  for(let i=0;i<nTS;i++) N.tripShape[i]=vi()-1;   // -1 = no shape

  // road network: tiered polylines, coords kept as 1e5 ints
  const nL=vi();
  N.lineTier=new Uint8Array(nL); N.lineStart=new Int32Array(nL+1);
  const LA=new Vec(1<<18), LO=new Vec(1<<18);
  let pla=0, plo=0;
  for(let i=0;i<nL;i++){
    N.lineTier[i]=vi();
    const n=vi();
    N.lineStart[i]=LA.n;
    for(let k=0;k<n;k++){ pla+=vi(); plo+=vi(); LA.push(pla); LO.push(plo); }
  }
  N.lineStart[nL]=LA.n;
  N.lLat=LA.view; N.lLon=LO.view;
  /* The Mercator term is a log(tan()) per point. Doing that for 134k points on
     every pan frame is what made this crawl, hoist it to load time so drawing
     is a pure linear transform. */
  const nP=LA.n;
  N.lLonF=new Float32Array(nP); N.lMerc=new Float32Array(nP);
  for(let i=0;i<nP;i++){
    const lo=N.lLon[i]/1e5, la=N.lLat[i]/1e5;
    N.lLonF[i]=lo; N.lMerc[i]=Math.log(Math.tan(Math.PI/4+la*Math.PI/360));
  }
  // lines bucketed by tier, so a frame does not rescan all of them four times
  const cnt=[0,0,0,0];
  for(let i=0;i<nL;i++) cnt[N.lineTier[i]]++;
  N.tierIdx=cnt.map(c=>new Int32Array(c));
  const fillT=[0,0,0,0];
  for(let i=0;i<nL;i++){ const t=N.lineTier[i]; N.tierIdx[t][fillT[t]++]=i; }
  N.lBox=new Int32Array(nL*4);
  for(let i=0;i<nL;i++){
    let a0=2147483647,a1=-2147483648,o0=2147483647,o1=-2147483648;
    for(let k=N.lineStart[i];k<N.lineStart[i+1];k++){
      const a=N.lLat[k], o=N.lLon[k];
      if(a<a0)a0=a; if(a>a1)a1=a; if(o<o0)o0=o; if(o>o1)o1=o;
    }
    N.lBox[i*4]=a0; N.lBox[i*4+1]=a1; N.lBox[i*4+2]=o0; N.lBox[i*4+3]=o1;
  }
  for(let i=0;i<nS;i++) N.hay[i]=(N.name[i]+" "+(N.city[N.cityOf[i]]||"")).toLowerCase();
  buildConnections();
}

/* A connection is the hop from stop_time j to j+1, so j and the trip index
   describe it completely:
     dep = stDep[j]   arr = stArr[j+1]   from = stStop[j]   to = stStop[j+1]
   Storing those four separately cost five Int32Arrays per connection; at
   national scale (4.35M connections) that was ~122 MB. This keeps two, and
   drops the `order` permutation by counting-sorting straight into place. */
function buildConnections(){
  const nT=N.tripRoute.length, ts=N.tripStart, stDep=N.stDep, stStop=N.stStop;
  let m=0, maxT=0;
  for(let t=0;t<nT;t++){
    const a=ts[t], z=ts[t+1]-1;
    if(z>a) m+=z-a;
    for(let i=a;i<z;i++) if(stDep[i]>maxT) maxT=stDep[i];
  }
  const SPAN=maxT+2, cnt=new Int32Array(SPAN+2);
  for(let t=0;t<nT;t++)
    for(let i=ts[t],z=ts[t+1]-1;i<z;i++) cnt[stDep[i]+1]++;
  for(let i=0;i<=SPAN;i++) cnt[i+1]+=cnt[i];
  // cnt[d] is now the first index whose departure is >= d, exactly the bucket
  N.bucket=cnt.slice(0,SPAN+1);
  const cST=new Int32Array(m), cTrip=new Int32Array(m);
  for(let t=0;t<nT;t++)
    for(let i=ts[t],z=ts[t+1]-1;i<z;i++){
      const p=cnt[stDep[i]]++;
      cST[p]=i; cTrip[p]=t;
    }
  N.cST=cST; N.cTrip=cTrip; N.nConn=m;

  // per-stop departures, already in time order
  const nS=N.lat.length;
  N.dStart=new Int32Array(nS+1);
  const deg=new Int32Array(nS);
  for(let i=0;i<m;i++) deg[stStop[cST[i]]]++;
  for(let s=0;s<nS;s++) N.dStart[s+1]=N.dStart[s]+deg[s];
  const fill=N.dStart.slice(0,nS);
  N.dConn=new Int32Array(m);
  for(let i=0;i<m;i++) N.dConn[fill[stStop[cST[i]]]++]=i;
}

const tripLast = t => N.stStop[N.tripStart[t+1]-1];

/* CSA */
let arrT,boardT,viaConn,viaWalk,tripSeen,tripBoard,tripBoardT;
function planJourney(from,to,depTime){
  const nS=N.lat.length, nT=N.tripRoute.length;
  if(!arrT){
    arrT=new Int32Array(nS); boardT=new Int32Array(nS);
    viaConn=new Int32Array(nS); viaWalk=new Int32Array(nS);
    tripSeen=new Uint8Array(nT); tripBoard=new Int32Array(nT); tripBoardT=new Int32Array(nT);
  }
  arrT.fill(INF); boardT.fill(INF); viaConn.fill(-1); viaWalk.fill(-1); tripSeen.fill(0);
  arrT[from]=depTime; boardT[from]=depTime;
  for(let x=N.xStart[from];x<N.xStart[from+1];x++){
    const j=N.xTo[x], a=depTime+N.xW[x];
    if(a<arrT[j]){ arrT[j]=a; boardT[j]=a; viaWalk[j]=from; }
  }
  const {cST,cTrip,nConn,bucket,stStop,stArr,stDep}=N;
  for(let i=bucket[Math.min(depTime,bucket.length-1)]; i<nConn; i++){
    const j=cST[i], d=stDep[j];
    if(d>=arrT[to]) break;
    const t=cTrip[i], f=stStop[j];
    if(!tripSeen[t]){
      if(boardT[f]>d) continue;
      tripSeen[t]=1; tripBoard[t]=f; tripBoardT[t]=d;
    }
    const to2=stStop[j+1], a=stArr[j+1];
    if(a<arrT[to2]){
      arrT[to2]=a; boardT[to2]=a+MIN_CHANGE; viaConn[to2]=i; viaWalk[to2]=-1;
      for(let x=N.xStart[to2];x<N.xStart[to2+1];x++){
        const nb=N.xTo[x], aw=a+N.xW[x];        // nb, not j, j is the stop_time above
        if(aw<arrT[nb]){ arrT[nb]=aw; boardT[nb]=aw; viaWalk[nb]=to2; viaConn[nb]=-1; }
      }
    }
  }
  if(arrT[to]>=INF) return null;
  const legs=[]; let cur=to, guard=0;
  while(cur!==from && guard++<200){
    if(viaWalk[cur]>=0){
      const p=viaWalk[cur];
      legs.push({mode:"walk",from:p,to:cur,dep:arrT[p],arr:arrT[cur]});
      cur=p;
    } else if(viaConn[cur]>=0){
      const c=viaConn[cur], t=N.cTrip[c], b=tripBoard[t];
      legs.push({mode:"ride",trip:t,route:N.tripRoute[t],from:b,to:cur,
                 dep:tripBoardT[t],arr:N.stArr[N.cST[c]+1]});
      cur=b;
    } else break;
  }
  legs.reverse();
  return {arrive:arrT[to], depart:legs.length?legs[0].dep:depTime, legs};
}

/* A ride leg drawn stop-to-stop is a straight hop across the city block. The
   trip's shape is the road it actually drives, so snap the leg onto that:
   find the shape vertices nearest the boarding and alighting stops and take
   the span between them. Memoised per leg, the search is O(shape points). */
function nearestOnLine(li,lat,lon,fromIdx){
  let best=-1,bd=Infinity;
  const cosl=Math.cos(lat*Math.PI/180);
  for(let k=(fromIdx===undefined?N.lineStart[li]:fromIdx); k<N.lineStart[li+1]; k++){
    const dla=N.lLat[k]/1e5-lat, dlo=(N.lLon[k]/1e5-lon)*cosl;
    const d=dla*dla+dlo*dlo;
    if(d<bd){ bd=d; best=k; }
  }
  return {k:best, m:Math.sqrt(bd)*111320};
}
function legRoad(leg){
  if(leg._road!==undefined) return leg._road;
  let out=null;
  const li = N.tripShape ? N.tripShape[leg.trip] : -1;
  if(li>=0 && N.lineStart[li+1]-N.lineStart[li]>=2){
    const A=nearestOnLine(li,N.lat[leg.from],N.lon[leg.from]);
    /* The alighting stop lies further along the shape, so search forward from
       the boarding vertex. Searching the whole line let a route that passes the
       same street twice snap backwards, which drew a long stray line. */
    let B=nearestOnLine(li,N.lat[leg.to],N.lon[leg.to],A.k);
    if(B.k<0||B.k===A.k) B=nearestOnLine(li,N.lat[leg.to],N.lon[leg.to]);
    if(A.k>=0&&B.k>=0&&A.k!==B.k){
      const idx=[];
      if(A.k<B.k) for(let k=A.k;k<=B.k;k++) idx.push(k);
      else        for(let k=A.k;k>=B.k;k--) idx.push(k);
      let along=0;
      for(let k=1;k<idx.length;k++)
        along+=metresLL(N.lLat[idx[k-1]]/1e5,N.lLon[idx[k-1]]/1e5,
                        N.lLat[idx[k]]/1e5,  N.lLon[idx[k]]/1e5);
      const straight=metres(leg.from,leg.to);
      /* Reject a snap that clearly is not this leg: a bus does not travel 3x
         the straight-line distance between adjacent stops, and a stop is not
         250 m from the road its own line drives. Either means we matched the
         wrong part of the shape, fall back to the straight line, which is
         honest rather than decorative. */
      if(along <= Math.max(400, straight*3) && A.m < 250 && B.m < 250) out=idx;
    }
  }
  return (leg._road=out);
}
/* screen-space polyline for a leg: along the road when we have it, straight
   otherwise (walking legs are straight by nature) */
function legScreen(leg){
  if(leg.mode==="walk")
    return [[px(N.lon[leg.from]),py(N.lat[leg.from])],[px(N.lon[leg.to]),py(N.lat[leg.to])]];
  const road=legRoad(leg);
  if(!road) return legStops(leg).map(s=>[px(N.lon[s]),py(N.lat[s])]);
  const pts=[[px(N.lon[leg.from]),py(N.lat[leg.from])]];
  for(const k of road) pts.push([px(N.lLon[k]/1e5),py(N.lLat[k]/1e5)]);
  pts.push([px(N.lon[leg.to]),py(N.lat[leg.to])]);
  return pts;
}
function legStops(leg){
  if(leg.mode==="walk") return [leg.from,leg.to];
  const out=[]; let on=false;
  for(let i=N.tripStart[leg.trip];i<N.tripStart[leg.trip+1];i++){
    const s=N.stStop[i];
    if(s===leg.from) on=true;
    if(on) out.push(s);
    if(on && s===leg.to) break;
  }
  return out.length>1?out:[leg.from,leg.to];
}
function legStopTimes(leg){
  const out=[]; let on=false;
  for(let i=N.tripStart[leg.trip];i<N.tripStart[leg.trip+1];i++){
    const s=N.stStop[i];
    if(s===leg.from) on=true;
    if(on) out.push({stop:s,arr:N.stArr[i],dep:N.stDep[i]});
    if(on && s===leg.to) break;
  }
  return out;
}

/* geo + format */
const R=6371000, rad=d=>d*Math.PI/180;
function metres(a,b){
  const dp=rad(N.lat[b]-N.lat[a]);
  const dl=rad(N.lon[b]-N.lon[a])*Math.cos(rad((N.lat[a]+N.lat[b])/2));
  return Math.hypot(dp,dl)*R;
}
function metresLL(la1,lo1,la2,lo2){
  const dp=rad(la2-la1), dl=rad(lo2-lo1)*Math.cos(rad((la1+la2)/2));
  return Math.hypot(dp,dl)*R;
}
const hhmm=s=>`${String(Math.floor(s/3600)%24).padStart(2,"0")}:${String(Math.floor(s/60)%60).padStart(2,"0")}`;
const mins=s=>Math.max(1,Math.round(s/60));
const dur=s=>s>=3600?`${Math.floor(s/3600)}h ${Math.round(s%3600/60)}m`:`${Math.round(s/60)} min`;
function nowSec(){ const d=new Date(); return d.getHours()*3600+d.getMinutes()*60+d.getSeconds(); }
function relative(t){
  const d=t-nowSec();
  if(d<0) return null;
  if(d<60) return "now";
  if(d<5400) return `${Math.round(d/60)} min`;
  return null;
}

/* icons */
const IC={
  bus:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="3" width="14" height="14" rx="2.5"/><path d="M5 9h14"/><path d="M8 21v-2M16 21v-2"/><circle cx="8.5" cy="13.5" r=".6" fill="currentColor"/><circle cx="15.5" cy="13.5" r=".6" fill="currentColor"/></svg>`,
  rail:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="3" width="12" height="13" rx="3"/><path d="M6 10h12"/><path d="M8 21l2-3M16 21l-2-3"/><circle cx="9" cy="13" r=".6" fill="currentColor"/><circle cx="15" cy="13" r=".6" fill="currentColor"/></svg>`,
  tram:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="12" height="13" rx="2.5"/><path d="M6 11h12"/><path d="M12 4V2"/><path d="M8 21l2-4M16 21l-2-4"/></svg>`,
  walk:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="13" cy="4" r="1.6"/><path d="M11 21l1.5-6L10 12V8l4 1 2 3"/><path d="M10 12l-2 4"/></svg>`,
  pin:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-6-5.4-6-10a6 6 0 1112 0c0 4.6-6 10-6 10z"/><circle cx="12" cy="11" r="2"/></svg>`,
  clock:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 1.8"/></svg>`,
};
function modeIcon(type){
  if(type===2) return IC.rail;
  if(type===0||type===1||type===5) return IC.tram;
  return IC.bus;
}
const lineBadge = r =>
  `<span class="line">${modeIcon(N.rType[r])}${esc(N.rShort[r]||"—")}</span>`;

/* state */
let originStop=-1, destStop=-1, journey=null, journeys=[], pickedStop=-1;
let sheetMode="nearby", pickingFor=null, useNow=true, here=null;
const RECENT_KEY="kav.recent";
const recents=()=>{ try{ return JSON.parse(localStorage.getItem(RECENT_KEY))||[] }catch{ return [] } };
function remember(s){
  const r=recents().filter(x=>x!==s); r.unshift(s);
  try{ localStorage.setItem(RECENT_KEY,JSON.stringify(r.slice(0,8))) }catch{}
}

/* map */
const cv=$("#map"), cx=cv.getContext("2d");
const view={cx:34.79,cy:32.08,scale:0,w:0,h:0,oy:0};
const merc=lat=>Math.log(Math.tan(Math.PI/4+lat*Math.PI/360));
const px=lon=>(lon-view.cx)*view.scale+view.w/2;
const py=lat=>-(merc(lat)-merc(view.cy))*view.scale*(180/Math.PI)+view.h/2+view.oy;

function fit(){
  const r=cv.getBoundingClientRect(), dpr=Math.min(devicePixelRatio||1,1.5);
  cv.width=r.width*dpr; cv.height=r.height*dpr;
  cx.setTransform(dpr,0,0,dpr,0,0);
  view.w=r.width; view.h=r.height;
  if(!view.scale) view.scale=r.width/0.16;
  draw();
}
/* Palette sampled from Google Maps' battery-saver style. It is 99.98%
   desaturated, so every value here is a pure grey. */
const MAP={
  bg:"#000000",
  road:[{c:"#161616",w:0.9},{c:"#242424",w:1.3},{c:"#343434",w:2.0},{c:"#565656",w:2.8}],
  roadCase:"#0d0d0d",
  stop:"rgba(125,125,125,.5)", stopRing:"#0a0a0a",
  route:"#ffffff", routeCase:"#000000",
  walk:"rgba(200,200,200,.7)",
  label:"#b4b4b4", halo:"#000000", stopRingHi:"#8a8a8a",
  markerFill:"#ffffff", markerRing:"rgba(255,255,255,.30)",
};
const invMerc=m=>(Math.atan(Math.exp(m))-Math.PI/4)*360/Math.PI;
function viewBounds(){
  const k=view.scale*(180/Math.PI);
  const mTop=merc(view.cy)+(view.h/2+view.oy)/k;
  const mBot=merc(view.cy)-(view.h/2-view.oy)/k;
  return {n:invMerc(mTop), s:invMerc(mBot),
          w:view.cx-(view.w/2)/view.scale, e:view.cx+(view.w/2)/view.scale};
}
const zoomK=()=>Math.max(0.75,Math.min(2.6,view.scale/9000));

/* Per-frame projection constants. With the Mercator term precomputed at load,
   projecting a point is two multiply-adds, no transcendentals in the loop. */
const FR={mcy:0,ky:0,cx:0,k:0,w2:0,hoy:0};
function frameConst(){
  FR.mcy=merc(view.cy); FR.ky=view.scale*(180/Math.PI);
  FR.cx=view.cx; FR.k=view.scale; FR.w2=view.w/2; FR.hoy=view.h/2+view.oy;
}

/* Coalesce every pan/zoom/wheel event into at most one paint per frame, and
   drop the cheapest detail while the finger is down. */
let rafPending=false, interacting=false, idleTimer=null;
function requestDraw(){
  if(rafPending) return;
  rafPending=true;
  requestAnimationFrame(()=>{ rafPending=false; draw(); });
}
function beginInteract(){
  interacting=true;
  clearTimeout(idleTimer);
  idleTimer=setTimeout(()=>{ interacting=false; requestDraw(); },140);
}

/* Panning re-stroked ~123k road points every frame. The road network only
   changes when the zoom changes, so render it once into an offscreen canvas a
   little larger than the viewport and just blit it at an offset while panning.
   A pan becomes one drawImage instead of tens of thousands of lineTo calls. */
const RC={cv:null,cx:null,w:0,h:0,scale:0,ccx:0,ccy:0,dpr:1,margin:0.35,stale:false};
function roadCacheBlit(){
  if(!N.lineTier) return false;
  const dpr=Math.min(devicePixelRatio||1,1.5);
  const needW=Math.ceil(view.w*(1+RC.margin*2)), needH=Math.ceil(view.h*(1+RC.margin*2));
  const ky=view.scale*(180/Math.PI);
  const sizeOk = RC.cv && RC.w===needW && RC.h===needH && RC.dpr===dpr;
  const scaleOk = sizeOk && RC.scale===view.scale;
  let inRange=false, ox=0, oy=0;
  if(scaleOk){
    ox=-RC.w/2+(RC.ccx-view.cx)*view.scale+view.w/2;
    oy=-RC.h/2+(merc(view.cy)-merc(RC.ccy))*ky+view.h/2+view.oy;
    inRange = Math.abs((RC.ccx-view.cx)*view.scale) <= RC.w/2-view.w/2
           && Math.abs((merc(view.cy)-merc(RC.ccy))*ky) <= RC.h/2-view.h/2;
  }
  /* A rebuild costs ~100 ms on the national bundle. Doing that mid-gesture is
     what made panning hitch, and because the frame was blocked, the road layer
     looked frozen while everything else moved. So during a gesture we blit the
     cache we have, even out of range (the leading edge simply runs out of
     roads), and refresh once the finger lifts. */
  if(!scaleOk || !inRange){
    if(RC.cv && interacting && scaleOk){ RC.stale=true; }
    else {
      if(!RC.cv){ RC.cv=document.createElement("canvas"); RC.cx=RC.cv.getContext("2d"); }
      const pw=Math.round(needW*dpr), ph=Math.round(needH*dpr);
      RC.w=needW; RC.h=needH; RC.scale=view.scale; RC.ccx=view.cx; RC.ccy=view.cy;
      RC.dpr=dpr; RC.stale=false;
      const g=RC.cx;
      if(RC.cv.width!==pw||RC.cv.height!==ph){ RC.cv.width=pw; RC.cv.height=ph; }
      g.setTransform(dpr,0,0,dpr,0,0);
      g.clearRect(0,0,needW,needH);
      const save={w:view.w,h:view.h,oy:view.oy};
      view.w=needW; view.h=needH; view.oy=0;
      frameConst();
      strokeRoads(g);
      view.w=save.w; view.h=save.h; view.oy=save.oy;
      frameConst();
      ox=-RC.w/2+view.w/2; oy=-RC.h/2+view.h/2+view.oy;
    }
  }
  cx.drawImage(RC.cv,0,0,RC.cv.width,RC.cv.height,ox,oy,RC.w,RC.h);
  return true;
}

function strokeRoads(g){
  const b=viewBounds(), M=1e5, pad=250;
  const north=b.n*M+pad, south=b.s*M-pad, west=b.w*M-pad, east=b.e*M+pad;
  const k=zoomK();
  const lo=N.lLonF, mz=N.lMerc, st0=N.lineStart, box=N.lBox;
  const {mcy,ky,cx:vcx,k:vk,w2,hoy}=FR;
  const minSeg=1.3;
  /* Real basemaps drop minor roads as you zoom out instead of drawing every
     road at every scale, that is what keeps the mesh from turning to mush. */
  let first = view.scale<5000 ? 2 : view.scale<12000 ? 1 : 0;
  g.lineCap="round"; g.lineJoin="round";

  const strokeTier=(idx,color,width)=>{
    g.strokeStyle=color; g.lineWidth=width;
    g.beginPath();
    for(let q=0;q<idx.length;q++){
      const i=idx[q], o=i*4;
      if(box[o+1]<south||box[o]>north||box[o+3]<west||box[o+2]>east) continue;
      const a=st0[i], z=st0[i+1];
      let lx=0,ly=0,on=false;
      for(let j=a;j<z;j++){
        const X=(lo[j]-vcx)*vk+w2, Y=(mcy-mz[j])*ky+hoy;
        if(!on){ g.moveTo(X,Y); lx=X; ly=Y; on=true; continue; }
        if(j<z-1 && Math.abs(X-lx)+Math.abs(Y-ly)<minSeg) continue;
        g.lineTo(X,Y); lx=X; ly=Y;
      }
    }
    g.stroke();
  };

  for(let tier=first;tier<4;tier++){
    const idx=N.tierIdx[tier], st=MAP.road[tier];
    // arteries get a darker casing under a lighter core, the way a real
    // basemap outlines its major roads
    if(tier>=2) strokeTier(idx, MAP.roadCase, st.w*k+2.0);
    strokeTier(idx, st.c, st.w*k);
  }
}

function drawStops(){
  if(view.scale<26000||interacting) return;
  const r=view.scale>70000?2.4:1.8;
  const {mcy,ky,cx:vcx,k:vk,w2,hoy}=FR;
  const lat=N.lat, lon=N.lon, mz=N.sMerc;
  cx.fillStyle=MAP.stop;
  cx.beginPath();
  for(let i=0;i<lat.length;i++){
    const X=(lon[i]-vcx)*vk+w2;
    if(X<-6||X>view.w+6) continue;
    const Y=(mcy-mz[i])*ky+hoy;
    if(Y<-6||Y>view.h+6) continue;
    cx.moveTo(X+r,Y); cx.arc(X,Y,r,0,7);
  }
  cx.fill();
}

/* Street labels come from stop names, the Israeli feed names stops after the
   streets they sit on ("דיזנגוף/ארלוזורוב"). A real basemap labels the important
   things sparsely, so pick one stop per grid cell, ranked by how many
   departures it serves, and cap the total. */
function drawLabels(){
  if(view.scale<25000||interacting||settings.labels===false) return;
  const cell=160, best=new Map();
  const {mcy,ky,cx:vcx,k:vk,w2,hoy}=FR;
  for(let i=0;i<N.lat.length;i++){
    const X=(N.lon[i]-vcx)*vk+w2;
    if(X<48||X>view.w-10) continue;
    const Y=(mcy-N.sMerc[i])*ky+hoy;
    if(Y<18||Y>view.h-24) continue;
    const key=((X/cell)|0)*1000+((Y/cell)|0);
    const imp=N.dStart[i+1]-N.dStart[i];
    const cur=best.get(key);
    if(!cur||imp>cur.imp) best.set(key,{i,X,Y,imp});
  }
  const list=[...best.values()].sort((a,b)=>b.imp-a.imp).slice(0,22);
  cx.font='500 10.5px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif';
  cx.textBaseline="middle"; cx.lineJoin="round"; cx.lineWidth=3.2;
  for(const {i,X,Y} of list){
    const t=N.name[i], isR=rtl(t)==="rtl";
    cx.textAlign=isR?"right":"left";
    const tx=isR?X-7:X+7;
    cx.strokeStyle=MAP.halo; cx.strokeText(t,tx,Y);
    cx.fillStyle=MAP.label;  cx.fillText(t,tx,Y);
    cx.fillStyle=MAP.stopRingHi;
    cx.beginPath(); cx.arc(X,Y,2,0,7); cx.fill();
  }
}

function draw(){
  if(!N.lat) return;
  frameConst();
  cx.fillStyle=MAP.bg; cx.fillRect(0,0,view.w,view.h);
  roadCacheBlit();
  drawStops();

  if(journey){
    const k=zoomK();
    for(const pass of ["case","line"]){
      for(const leg of journey.legs){
        const pts=legScreen(leg);
        cx.beginPath();
        pts.forEach(([x,y],i)=>i?cx.lineTo(x,y):cx.moveTo(x,y));
        cx.lineJoin=cx.lineCap="round";
        if(pass==="case"){                       // dark casing lifts the route off the roads
          cx.setLineDash([]); cx.strokeStyle=MAP.routeCase;
          cx.lineWidth=(leg.mode==="walk"?2.4:4.2)*k+2.6;
        } else if(leg.mode==="walk"){
          cx.setLineDash([2.5,4]); cx.strokeStyle=MAP.walk; cx.lineWidth=1.7*k;
        } else {
          cx.setLineDash([]); cx.strokeStyle=MAP.route; cx.lineWidth=4.2*k;
        }
        cx.stroke(); cx.setLineDash([]);
      }
    }
    if(view.scale>26000){                        // interchange dots along the ridden legs
      for(const leg of journey.legs){
        if(leg.mode!=="ride") continue;
        const pts=legStops(leg).map(s=>[px(N.lon[s]),py(N.lat[s])]);
        cx.fillStyle=MAP.bg; cx.strokeStyle=MAP.route; cx.lineWidth=1.4;
        for(const [x,y] of pts.slice(1,-1)){ cx.beginPath(); cx.arc(x,y,2.4,0,7); cx.fill(); cx.stroke(); }
      }
    }
  }

  if(here){
    const x=px(here.lon), y=py(here.lat);
    cx.beginPath(); cx.arc(x,y,13,0,7); cx.fillStyle="#1f1f1f"; cx.fill();
    cx.beginPath(); cx.moveTo(x,y-6); cx.lineTo(x+4.5,y+5); cx.lineTo(x,y+2.6); cx.lineTo(x-4.5,y+5);
    cx.closePath(); cx.fillStyle=MAP.markerFill; cx.fill();
  }
  for(const [s,filled] of [[originStop,false],[destStop,true]]){
    if(s<0) continue;
    const x=px(N.lon[s]), y=py(N.lat[s]);
    cx.beginPath(); cx.arc(x,y,10,0,7); cx.strokeStyle=MAP.markerRing; cx.lineWidth=1.5; cx.stroke();
    cx.beginPath(); cx.arc(x,y,5.5,0,7);
    if(filled){ cx.fillStyle=MAP.markerFill; cx.fill(); }
    else { cx.fillStyle=MAP.bg; cx.fill(); cx.strokeStyle=MAP.markerFill; cx.lineWidth=2.2; cx.stroke(); }
  }
  if(pickedStop>=0){
    const x=px(N.lon[pickedStop]), y=py(N.lat[pickedStop]);
    cx.beginPath(); cx.arc(x,y,4.5,0,7); cx.strokeStyle="#ffffff"; cx.lineWidth=1.8; cx.stroke();
  }
  drawLabels();
}

function frameOn(stops){
  if(!stops.length) return;
  let mnx=1e9,mxx=-1e9,mny=1e9,mxy=-1e9;
  for(const s of stops){
    mnx=Math.min(mnx,N.lon[s]); mxx=Math.max(mxx,N.lon[s]);
    mny=Math.min(mny,N.lat[s]); mxy=Math.max(mxy,N.lat[s]);
  }
  view.cx=(mnx+mxx)/2; view.cy=(mny+mxy)/2;
  const wide=appWidth()>=760;
  const top=wide?0:document.querySelector(".topbar").getBoundingClientRect().height;
  const bot=wide?0:sheet.getBoundingClientRect().height;
  view.oy=(top-bot)/2;                       // centre inside the visible band
  const usableH=Math.max(120,view.h-top-bot-40);
  view.scale=Math.min(view.w/((mxx-mnx)*1.6||0.012), usableH/(((mxy-mny)*1.9)||0.012));
}

/* pan + pinch */
let ptrs=new Map(), pinch=null, moved=0;
cv.addEventListener("pointerdown",e=>{
  try{ cv.setPointerCapture(e.pointerId); }catch{}   // never let a failed capture kill panning
  ptrs.set(e.pointerId,e); moved=0;
  beginInteract(); if(ptrs.size===2) pinch=gap(); });
cv.addEventListener("pointermove",e=>{
  if(!ptrs.has(e.pointerId)) return;
  const prev=ptrs.get(e.pointerId); ptrs.set(e.pointerId,e);
  beginInteract();
  if(ptrs.size===2 && pinch){ const g=gap(); if(g&&pinch){ view.scale*=g/pinch; pinch=g; requestDraw(); } return; }
  const dx=e.clientX-prev.clientX, dy=e.clientY-prev.clientY;
  moved+=Math.abs(dx)+Math.abs(dy);
  view.cx-=dx/view.scale;
  /* py = (merc(cy)-merc(lat))*scale*(180/PI); inverting that gives
     dlat = dy*cos(lat)/scale. The earlier form divided by an extra 180/PI,
     which made vertical panning ~57x too slow, it looked like the map was
     locked to horizontal movement. */
  view.cy+=dy*Math.cos(view.cy*Math.PI/180)/view.scale;
  requestDraw();
});
function gap(){ const a=[...ptrs.values()]; return a.length<2?null:Math.hypot(a[0].clientX-a[1].clientX,a[0].clientY-a[1].clientY); }
function endPtr(e){
  const was=ptrs.size;
  ptrs.delete(e.pointerId); if(ptrs.size<2) pinch=null;
  if(was===1 && moved<6) pickAt(e);
}
cv.addEventListener("pointerup",endPtr);
cv.addEventListener("pointercancel",e=>{ ptrs.delete(e.pointerId); pinch=null; });
cv.addEventListener("wheel",e=>{ e.preventDefault(); beginInteract(); view.scale*=e.deltaY<0?1.18:1/1.18; requestDraw(); },{passive:false});
$("#zin").onclick=()=>{ view.scale*=1.35; requestDraw(); };
$("#zout").onclick=()=>{ view.scale/=1.35; requestDraw(); };

function pickAt(e){
  const r=cv.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  let best=-1, bd=18*18;
  for(let i=0;i<N.lat.length;i++){
    const dx=px(N.lon[i])-mx, dy=py(N.lat[i])-my, d=dx*dx+dy*dy;
    if(d<bd){ bd=d; best=i; }
  }
  if(best>=0) showStop(best);
}

/* sheet */
const sheet=$("#sheet");
const STATES=["peek","half","full"], HEIGHT={peek:"var(--peek)",half:"62%",full:"92%"};
let reframe=null;
function setSheet(state){
  sheet.dataset.state=state;
  document.documentElement.style.setProperty("--sheet-h",HEIGHT[state]||HEIGHT.peek);
  clearTimeout(reframe);
  reframe=setTimeout(()=>{ if(journey) frameOn(journey.legs.flatMap(legStops)); draw(); },440);
}
const stepSheet=d=>{
  const i=STATES.indexOf(sheet.dataset.state||"peek");
  setSheet(STATES[Math.max(0,Math.min(STATES.length-1,i+d))]);
};
$("#grab").onclick=()=>stepSheet(sheet.dataset.state==="full"?-2:1);
let sy=null;
$("#grab").addEventListener("pointerdown",e=>{ sy=e.clientY; });
addEventListener("pointerup",e=>{
  if(sy===null) return;
  const d=e.clientY-sy; sy=null;
  if(d<-30) stepSheet(1); else if(d>30) stepSheet(-1);
});
$("#sheet-back").onclick=()=>{ if(journeys.length) renderResults(); else showNearby(); };

function head(title,accent,back){
  $("#sheet-title").innerHTML=`${esc(title)} <em>${esc(accent)}</em>`;
  $("#sheet-back").hidden=!back;
}

/* nearby */
function nearestStops(lat,lon,k=12){
  const out=[];
  for(let i=0;i<N.lat.length;i++){
    const d=metresLL(lat,lon,N.lat[i],N.lon[i]);
    if(d<2500) out.push([i,d]);
  }
  return out.sort((a,b)=>a[1]-b[1]).slice(0,k);
}
function showNearby(){
  sheetMode="nearby"; head("Nearby","stops",false);
  const c=here||{lat:view.cy,lon:view.cx};
  const list=nearestStops(c.lat,c.lon);
  const body=$("#sheet-body");
  if(!list.length){ body.innerHTML=`<p class="msg">No stops in view, pan the map.</p>`; return; }
  body.innerHTML=list.map(([s,d],i)=>
    `<button class="srow2 enter" dir="${rtl(N.name[s])}" style="--i:${Math.min(i,6)}" data-stop="${s}">
       <span class="srow2-n" dir="${rtl(N.name[s])}">${esc(N.name[s])}</span>
       <span class="srow2-d">${d<1000?Math.round(d)+" m":(d/1000).toFixed(1)+" km"}</span>
     </button>`).join("");
  body.querySelectorAll("[data-stop]").forEach(el=>el.onclick=()=>showStop(+el.dataset.stop));
  proof();
}

/* departures at a stop */
function showStop(s){
  pickedStop=s; sheetMode="stop";
  head("Next","departures",true);
  const t0=useNow?nowSec():timeSeconds();
  const rows=[];
  for(let i=N.dStart[s];i<N.dStart[s+1] && rows.length<60;i++){
    const c=N.dConn[i];
    if(N.stDep[N.cST[c]]<t0) continue;
    rows.push(c);
  }
  const body=$("#sheet-body");
  body.innerHTML=`<p class="msg" style="margin:0 0 var(--sp-2); text-align:start" dir="${rtl(N.name[s])}">${esc(N.name[s])}${cityOf(s)?` · ${esc(cityOf(s))}`:""}</p>`+
    `<p class="msg" style="margin:0 0 var(--sp-2); text-align:start; font-size:11px">${
      RT_DEMO ? "simulated live times, not real data" : "scheduled times · no live feed available"}</p>`+
    (rows.length?rows.map((c,i)=>{
      const t=N.cTrip[c], r=N.tripRoute[t], dep=N.stDep[N.cST[c]], rel=relative(dep);
      const live=isLive(t);
      return `<button class="brow enter" dir="${rtl(N.name[tripLast(t)])}" style="--i:${Math.min(i,6)}" data-to="${tripLast(t)}">
        ${lineBadge(r)}
        <span class="brow-in${live?" is-live":""}">${live?'<span class="live-dot"></span>':""}${rel?`<b>${esc(rel)}</b>`:hhmm(dep)}</span>
        <span class="brow-h" dir="${rtl(N.name[tripLast(t)])}">${esc(N.name[tripLast(t)])}</span>
      </button>`;
    }).join("") : `<p class="msg">Nothing more today.</p>`);
  body.querySelectorAll("[data-to]").forEach(el=>el.onclick=()=>{
    destStop=+el.dataset.to; if(originStop<0) originStop=s;
    syncLabels(); tryPlan();
  });
  setSheet("half"); draw();
}

/* journeys */
function timeSeconds(){
  const v=$("#time").value;
  if(!v) return nowSec();
  const [h,m]=v.split(":").map(Number);
  return h*3600+m*60;
}
function tryPlan(){
  if(originStop<0||destStop<0){ showNearby(); return; }
  if(originStop===destStop){
    journeys=[]; journey=null; sheetMode="results";
    head("Same","place",false);
    $("#sheet-body").innerHTML=
      `<p class="msg">That is the same stop, you are already there.</p>`;
    frameOn([originStop]); setSheet("half"); draw(); return;
  }
  const base=useNow?nowSec():timeSeconds();
  const t0=performance.now();
  /* Successive departures, then collapse any that ride the same lines to the
     same arrival, keeping the latest one you could leave on, which is the
     useful fact. Showing four identical itineraries is noise, not choice. */
  const found=[]; let t=base;
  for(let k=0;k<6;k++){
    const j=planJourney(originStop,destStop,t);
    if(!j) break;
    found.push(j); t=j.depart+60;
  }
  const byShape=new Map();
  for(const j of found){
    const key=j.legs.filter(l=>l.mode==="ride").map(l=>l.route).join(",")+"@"+j.arrive;
    const prev=byShape.get(key);
    if(!prev||j.depart>prev.depart) byShape.set(key,j);
  }
  journeys=[...byShape.values()].sort((a,b)=>a.depart-b.depart).slice(0,4);
  // two stops within footpath range: the honest answer is "just walk"
  if(journeys.length && journeys.every(j=>j.legs.every(l=>l.mode==="walk")))
    journeys=journeys.slice(0,1);
  stat.route=performance.now()-t0;
  journey=journeys[0]||null;
  renderResults();
  if(journey) frameOn(journey.legs.flatMap(legStops));
  draw();
}
function renderResults(){
  sheetMode="results"; head("Suggested","routes",false);
  const body=$("#sheet-body");
  if(!journeys.length){ body.innerHTML=`<p class="msg">No route found. Try a later time.</p>`; proof(); return; }
  body.innerHTML=journeys.map((j,i)=>{
    const rel=relative(j.depart);
    const firstRide=(j.legs.find(l=>l.mode==="ride")||{}).trip;
    return `<button class="jour enter" style="--i:${i}" data-j="${i}"${i===0?" data-sel":""}>
      <div class="jour-top">
        <div class="jour-when">${hhmm(j.depart)}<span>→</span>${hhmm(j.arrive)}</div>
        <div class="jour-dur">${dur(j.arrive-j.depart)}</div>
      </div>
      <div class="jour-legs">${legChips(j)}</div>
      <div class="jour-foot${isLive(firstRide)?" is-live":""}">${
        isLive(firstRide)?'<span class="live-dot"></span>':""}${
        rel?`<b>${rel==="now"?"Leaves now":`Leaves in ${esc(rel)}`}</b>`:`Departs ${hhmm(j.depart)}`}</div>
    </button>`;
  }).join("");
  body.querySelectorAll("[data-j]").forEach(el=>el.onclick=()=>showDetail(+el.dataset.j));
  setSheet("half"); proof();
}
function legChips(j){
  return j.legs.map(l=>l.mode==="ride"
    ? lineBadge(l.route)
    : `<span class="walk">${IC.walk}${mins(l.arr-l.dep)}</span>`
  ).join(`<span class="sep">›</span>`);
}

/* trip detail */
function showDetail(i){
  journey=journeys[i]; sheetMode="detail";
  head("Your","trip",true);
  frameOn(journey.legs.flatMap(legStops)); draw();
  const body=$("#sheet-body");
  let html=`<div class="tl">`;
  for(const leg of journey.legs){
    if(leg.mode==="walk"){
      html+=`<div class="tl-step" data-mode="walk">
        <div class="tl-rail"><span class="tl-node"></span></div>
        <div class="tl-body">
          <div class="tl-t">${hhmm(leg.dep)}</div>
          <div class="tl-name" dir="${rtl(N.name[leg.to])}">${esc(N.name[leg.to])}</div>
          <div class="tl-meta">Walk ${mins(leg.arr-leg.dep)} min · ${Math.round(metres(leg.from,leg.to))} m</div>
        </div></div>`;
    } else {
      const st=legStopTimes(leg), mid=st.slice(1,-1);
      html+=`<div class="tl-step" data-mode="ride">
        <div class="tl-rail"><span class="tl-node"></span></div>
        <div class="tl-body">
          <div class="tl-t">${hhmm(leg.dep)}</div>
          <div class="tl-name" dir="${rtl(N.name[leg.from])}">${esc(N.name[leg.from])}</div>
          <div class="tl-line">${lineBadge(leg.route)}
            <span class="tl-meta" dir="${rtl(N.name[tripLast(leg.trip)])}">towards ${esc(N.name[tripLast(leg.trip)])}</span></div>
          <div class="tl-meta">${st.length-1} stop${st.length-1===1?"":"s"} · ${mins(leg.arr-leg.dep)} min</div>
          ${mid.length?`<button class="tl-more">Show ${mid.length} stop${mid.length===1?"":"s"}</button>
          <div class="tl-stops">${mid.map(x=>
            `<span class="tl-stop"><span>${hhmm(x.arr)}</span><span dir="${rtl(N.name[x.stop])}">${esc(N.name[x.stop])}</span></span>`).join("")}</div>`:""}
        </div></div>`;
    }
  }
  const last=journey.legs[journey.legs.length-1];
  html+=`<div class="tl-step" data-mode="end">
    <div class="tl-rail"><span class="tl-node"></span></div>
    <div class="tl-body">
      <div class="tl-t">${hhmm(journey.arrive)}</div>
      <div class="tl-name" dir="${rtl(N.name[last.to])}">${esc(N.name[last.to])}</div>
      <div class="tl-meta">Arrive · ${dur(journey.arrive-journey.depart)} total</div>
    </div></div></div>`;
  body.innerHTML=html;
  body.querySelectorAll(".tl-more").forEach(b=>b.onclick=()=>{
    const box=b.nextElementSibling;
    const open=box.hasAttribute("data-open");
    open?box.removeAttribute("data-open"):box.setAttribute("data-open","");
    b.textContent=open?`Show ${box.children.length} stop${box.children.length===1?"":"s"}`:"Hide stops";
  });
  setSheet("half");
}

/* search overlay */
const ov=$("#overlay"), ovIn=$("#ov-input"), ovList=$("#ov-list");
let ovItems=[], ovSel=0;

const cityOf = s => N.city[N.cityOf[s]] || "";
/* Multi-token: every word must appear somewhere in "<name> <city>", in any
   order, so "הדרור 30 ראש העין" finds Haderor in Rosh HaAyin. Bare numbers are
   house numbers, not stop names, so they are dropped unless nothing else is
   given (in which case they are tried against the stop code). */
function search(q,limit=60){
  const raw=q.trim().toLowerCase();
  if(!raw) return [];
  const all=raw.split(/\s+/).filter(Boolean);
  const words=all.filter(t=>!/^\d+$/.test(t));
  const need=words.length?words:all;
  const hits=[];
  for(let i=0;i<N.name.length;i++){
    const hay=N.hay[i];
    let ok=true, sc=0;
    for(const t of need){
      const at=hay.indexOf(t);
      if(at<0){ ok=false; break; }
      sc += at===0 ? 0 : Math.min(at,40)/100;
    }
    if(ok){ hits.push([i,sc]); continue; }
    if(need.length===1 && String(N.code[i]).startsWith(need[0])) hits.push([i,0.5]);
  }
  hits.sort((a,b)=>a[1]-b[1]);
  return hits.slice(0,limit).map(h=>h[0]);
}
function openSearch(which){
  pickingFor=which;
  $("#ov-dot").className="dot "+(which==="from"?"dot-from":"dot-to");
  ovIn.value=""; ovIn.placeholder=which==="from"?"choose a start…":"choose a destination…";
  ov.hidden=false; renderSearch(); ovIn.focus();
}
function closeSearch(){ ov.hidden=true; pickingFor=null; }
$("#ov-back").onclick=closeSearch;
$("#ov-clear").onclick=()=>{ ovIn.value=""; $("#ov-clear").hidden=true; renderSearch(); ovIn.focus(); };
$("#pick-from").onclick=()=>openSearch("from");
$("#pick-to").onclick=()=>openSearch("to");

function renderSearch(){
  const q=ovIn.value.trim();
  $("#ov-clear").hidden=!q;
  let html="", items=[];
  if(!q){
    const rec=recents().filter(s=>s<N.name.length);
    if(rec.length){
      html+=`<div class="ov-head2">recent</div>`;
      html+=rec.map(s=>{ items.push(s); return itemHtml(s,items.length-1,IC.clock); }).join("");
    }
    const c=here||{lat:view.cy,lon:view.cx};
    const near=nearestStops(c.lat,c.lon,8);
    if(near.length){
      html+=`<div class="ov-head2">nearby</div>`;
      html+=near.map(([s,d])=>{ items.push(s); return itemHtml(s,items.length-1,IC.pin,d); }).join("");
    }
  } else {
    const res=search(q);
    if(!res.length) html=`<p class="msg">No stop matches “${esc(q)}”.</p>`;
    else {
      html+=`<div class="ov-head2">stops</div>`;
      html+=res.map(s=>{ items.push(s); return itemHtml(s,items.length-1,IC.pin); }).join("");
    }
  }
  ovItems=items; ovSel=0;
  ovList.innerHTML=html;
  const first=ovList.querySelector(".ov-item");
  if(first) first.setAttribute("data-sel","");
  ovList.querySelectorAll(".ov-item").forEach(el=>{
    el.onclick=()=>chooseStop(ovItems[+el.dataset.k]);
  });
}
function itemHtml(s,k,icon,d){
  const sub=[];
  if(cityOf(s)) sub.push(cityOf(s));
  if(N.code[s]) sub.push(String(N.code[s]));
  if(d!==undefined) sub.push(d<1000?Math.round(d)+" m":(d/1000).toFixed(1)+" km");
  return `<button class="ov-item" dir="${rtl(N.name[s])}" data-k="${k}">
    <span class="ov-ic">${icon}</span>
    <span class="ov-txt"><span class="ov-n" dir="${rtl(N.name[s])}">${esc(N.name[s])}</span>
      ${sub.length?`<span class="ov-sub">${esc(sub.join(" · "))}</span>`:""}</span></button>`;
}
function chooseStop(s){
  if(s===undefined) return;
  if(pickingFor==="from") originStop=s; else destStop=s;
  remember(s); closeSearch(); syncLabels();
  if(originStop>=0&&destStop>=0) tryPlan(); else { frameOn([s]); showStop(s); }
}
ovIn.addEventListener("input",renderSearch);
ovIn.addEventListener("keydown",e=>{
  const els=[...ovList.querySelectorAll(".ov-item")];
  if(!els.length) return;
  if(e.key==="ArrowDown"||e.key==="ArrowUp"){
    e.preventDefault();
    ovSel=Math.max(0,Math.min(els.length-1,ovSel+(e.key==="ArrowDown"?1:-1)));
    els.forEach((n,i)=>i===ovSel?n.setAttribute("data-sel",""):n.removeAttribute("data-sel"));
    els[ovSel].scrollIntoView({block:"nearest"});
  } else if(e.key==="Enter"){ e.preventDefault(); chooseStop(ovItems[ovSel]); }
  else if(e.key==="Escape") closeSearch();
});

function syncLabels(){
  for(const [id,s,empty] of [["#lbl-from",originStop,"from…"],["#lbl-to",destStop,"to…"]]){
    const el=$(id);
    if(s<0){ el.textContent=empty; el.classList.add("is-empty"); el.removeAttribute("dir"); }
    else { el.textContent=N.name[s]; el.classList.remove("is-empty"); el.setAttribute("dir",rtl(N.name[s])); }
  }
}
$("#swap").onclick=()=>{ [originStop,destStop]=[destStop,originStop]; syncLabels(); tryPlan(); };
$("#when-now").onclick=()=>{ useNow=true; $("#when-now").classList.add("chip-lit"); $("#time").value=hhmm(nowSec()); tryPlan(); };
$("#time").addEventListener("change",()=>{ useNow=false; $("#when-now").classList.remove("chip-lit"); tryPlan(); });
$("#locate").onclick=()=>{
  if(!navigator.geolocation) return;
  navigator.geolocation.getCurrentPosition(p=>{
    here={lat:p.coords.latitude,lon:p.coords.longitude};
    view.cx=here.lon; view.cy=here.lat; view.scale=Math.max(view.scale,innerWidth/0.05);
    draw(); if(sheetMode==="nearby") showNearby();
  },()=>{},{enableHighAccuracy:false,timeout:8000});
};


/* settings + trip-plan sources */
const SET_KEY="kav.settings";
const settings=Object.assign(
  {source:"device", region:REGION, labels:true},
  (()=>{ try{ return JSON.parse(localStorage.getItem(SET_KEY))||{} }catch{ return {} } })());
// the loaded bundle is the truth; a stale stored value must not misreport it
settings.region = REGION;
function saveSettings(){ try{ localStorage.setItem(SET_KEY,JSON.stringify(settings)) }catch{} }

/* Moovit as a trip-plan source. Planning is two calls,
     GET /api/route/search?...fromLocation_latitude=<lat*1e6>... -> a token
     GET /api/route/result?token=<t>&offset=0                    -> itineraries
   Both sit behind an AWS WAF challenge: without the `aws-waf-token` cookie the
   API returns 403; with it, 400 for a malformed query. That cookie is issued by
   awswaf.com's browser challenge, so the WAF is the ACCESS GATE, not merely
   tracking, it cannot be stripped and still reach the API. The strippable
   parts are the OneTrust consent/analytics (cdn.cookielaw.org), the
   ay.delivery beacon and Moovit's own /api/debug/dump telemetry.
   From this page the call is cross-origin and Moovit sends no CORS header, so
   it cannot work in the browser prototype at all. It becomes possible in the
   native Android build, where there is no CORS and a one-time hidden WebView
   can obtain the WAF cookie. */
const MOOVIT={
  origin:"https://moovitapp.com",
  needs:["aws-waf-token cookie (WAF challenge)","non-browser HTTP client (no CORS)"],
  strippable:["cdn.cookielaw.org (OneTrust consent/analytics)",
              "qpzrpqq5gx7fffedb.ay.delivery beacon",
              "moovitapp.com/api/debug/dump telemetry"],
  required:["moovitapp.com/api/route/search","moovitapp.com/api/route/result",
            "moovitapp.com/api/location/address (geocoding)"],
  lastError:null,
  async probe(){
    try{
      const r=await fetch(`${this.origin}/api/route/search?time=${Date.now()}`,{mode:"cors"});
      this.lastError = r.ok ? null : `HTTP ${r.status}`;
      return r.ok;
    }catch(e){ this.lastError="blocked by CORS: "+e.name; return false; }
  },
  async plan(){ throw new Error(this.lastError||"unavailable in the browser build"); },
};

const openSettings =()=>{ $("#settings").hidden=false; renderSettings(); };
const closeSettings=()=>{ $("#settings").hidden=true; };
$("#open-settings").onclick=openSettings;
$("#set-back").onclick=closeSettings;

function setRow(group,val,title,desc,sel,extra){
  return `<button class="set-row${extra||""}" data-g="${group}" data-v="${val}"${sel?" data-sel":""}>
    <span><span class="set-t">${esc(title)}</span><span class="set-d">${desc}</span></span>
    <span class="set-mark"></span></button>`;
}
function renderSettings(){
  const box=$("#set-list");
  const live = settings.source==="moovit";
  box.innerHTML =
    `<div class="set-group">trip planning</div>` +
    setRow("source","device","On-device",
      "Plans on your phone from the downloaded timetable. Works with no signal. "+
      "Nothing leaves the device, not your location, not where you are going.",
      settings.source==="device") +
    setRow("source","moovit","Through Moovit's servers",
      "Live arrivals, their ranking and fares. Every trip you plan is sent to "+
      "Moovit with its origin, destination and time.",
      live) +
    (live?`<div class="set-note set-warn">${
      MOOVIT.lastError
        ? `<b>Unavailable in this web build.</b> ${esc(MOOVIT.lastError)}. `
        : "<b>Checking…</b> "}
      Moovit's API sits behind an AWS WAF challenge and sends no CORS header, so a
      browser page on another origin cannot call it. It becomes possible in the
      native Android build. Trip planning stays on-device until then.</div>`:"") +

    `<div class="set-group">region</div>` +
    setRow("region","il","All of Israel",
      "35,303 stops · 123,076 trips · 6.18 MB · about 122 MB in memory",
      settings.region==="il") +
    setRow("region","tlv","Tel Aviv metro only",
      "7,862 stops · 46,581 trips · 2.00 MB · lighter on an older phone",
      settings.region==="tlv") +

    `<div class="set-group">map</div>` +
    setRow("labels","on","Show street labels","Stop names drawn on the map when zoomed in",
      settings.labels===true) +
    setRow("labels","off","Hide street labels","A cleaner map",
      settings.labels===false) +

    `<div class="set-note">Live arrival times need a real-time feed. Israel publishes
      none openly, so every time in this app is a <b>scheduled</b> time.</div>`;

  box.querySelectorAll(".set-row").forEach(el=>el.onclick=async()=>{
    const g=el.dataset.g, v=el.dataset.v;
    if(g==="labels") settings.labels = v==="on";
    else if(g==="region"){
      settings.region=v; saveSettings();
      location.search=`?r=${v}`; return;             // a different bundle: reload
    }
    else if(g==="source"){
      settings.source=v;
      if(v==="moovit"){ renderSettings(); await MOOVIT.probe(); }
    }
    saveSettings(); renderSettings(); draw();
  });
}

/* proof strip */
const stat={};
function proof(){
  const wide=appWidth()>=760;
  const route=stat.route!==undefined?`<span><i>${stat.route.toFixed(1)} ms</i> route</span>`:"";
  $("#proof").innerHTML = wide
    ? `<span><b>${(stat.wire/1e6).toFixed(2)} MB</b> over the wire</span>`+
      `<span><b>${stat.decode|0} ms</b> decode</span>`+
      `<span><b>${(N.nConn/1e6).toFixed(2)} M</b> connections</span>`+ route +
      `<span><b>${stat.reqs}</b> network request${stat.reqs===1?"":"s"} since load</span>`
    : `<span><b>${(stat.wire/1e6).toFixed(2)} MB</b></span>`+ route +
      `<span><b>${stat.reqs}</b> request${stat.reqs===1?"":"s"}</span>`;
}

/* boot */
addEventListener("resize",()=>{ fit(); });
/* <script src> is not CORS-checked, so it still works when the page has an
   opaque origin (CSP: sandbox without allow-same-origin) and fetch() does not. */
function loadViaScript(){
  return new Promise((res,rej)=>{
    const el=document.createElement("script");
    el.src=`data/${REGION}.kav.js`;
    el.onload=()=>{
      const b64=self.KAV_GZ_B64;
      if(!b64) return rej(new Error("bundle script loaded but was empty"));
      const bin=atob(b64);
      const u=new Uint8Array(bin.length);
      for(let i=0;i<bin.length;i++) u[i]=bin.charCodeAt(i);
      self.KAV_GZ_B64=null; el.remove();
      res({bytes:u, wire:b64.length});
    };
    el.onerror=()=>rej(new Error("could not load data/tlv.kav.js"));
    document.head.appendChild(el);
  });
}
async function load(url,onProgress){
  const res=await fetch(url);
  if(!res.ok) throw new Error(url+" -> "+res.status);
  const total=+res.headers.get("content-length")||0;
  const chunks=[]; let got=0;
  const rd=res.body.getReader();
  for(;;){
    const {done,value}=await rd.read();
    if(done) break;
    chunks.push(value); got+=value.length;
    onProgress(got,total);
  }
  let bytes=new Uint8Array(got); let off=0;
  for(const c of chunks){ bytes.set(c,off); off+=c.length; }
  return bytes;
}
/* An opaque origin (CSP: sandbox without allow-same-origin) throws on
   localStorage and refuses fetch. Detect it synchronously up front rather than
   waiting for a fetch failure, under that CSP the request can hang instead of
   rejecting, so a catch alone is not dependable. */
const opaqueOrigin = (()=>{ try{ localStorage.getItem("kav.probe"); return false; }
                            catch{ return true; } })();
function withTimeout(p,ms,label){
  let t;
  return Promise.race([p, new Promise((_,rej)=>{ t=setTimeout(()=>rej(new Error(label)),ms); })])
                .finally(()=>clearTimeout(t));
}
(async function boot(){
  const fill=$("#boot-fill"), note=$("#boot-s");
  try{
    let bytes;
    const viaScript=async()=>{
      note.textContent="sandboxed page, loading via script…";
      fill.style.width="55%";
      const r=await loadViaScript();
      bytes=r.bytes; stat.wire=r.wire;
    };
    if(opaqueOrigin){
      await viaScript();
    } else {
      try{
        bytes=await withTimeout(load(`data/${REGION}.kav.gz`,(got,total)=>{
          stat.wire=got;
          fill.style.width=total?`${Math.round(got/total*100)}%`:"40%";
          note.textContent=`${(got/1e6).toFixed(2)} MB${total?` / ${(total/1e6).toFixed(2)} MB`:""}`;
        }),20000,"fetch timed out");
      }catch(netErr){
        await viaScript();
      }
    }
    fill.style.width="100%";
    stat.reqs=1;
    // if the server didn't already decode it, the gzip magic is still there
    if(bytes[0]===0x1f && bytes[1]===0x8b){
      note.textContent="decompressing…";
      const ds=new DecompressionStream("gzip");
      bytes=new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(ds)).arrayBuffer());
    }
    note.textContent="building the index…";
    await new Promise(r=>setTimeout(r,16));
    const t0=performance.now();
    parse(bytes.buffer);
    stat.decode=performance.now()-t0;
  }catch(err){
    note.textContent="could not load the timetable, "+err.message;
    fill.style.background="var(--bad)";
    return;
  }
  $("#boot").hidden=true;
  $("#time").value=hhmm(nowSec());
  fit(); syncLabels(); showNearby(); proof();
})();
