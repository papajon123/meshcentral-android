'use strict';
// Isolated 1.3.1 fixture. Credentials exist only in memory; never log bootstrap bodies.
// Usage: node large-servers.cjs <installed MeshCentral package> <new empty run directory>
const fs = require('fs'), path = require('path'), crypto = require('crypto'), http = require('http');
const Module = require('module');
const pkg = path.resolve(process.argv[2]);
const root = path.resolve(process.argv[3]);
const paging = process.argv[4] !== 'unpaged';
if (JSON.parse(fs.readFileSync(path.join(pkg, 'package.json'))).version !== '1.3.1') throw Error('Requires MeshCentral 1.3.1');
if (fs.existsSync(root)) throw Error('Use a new isolated directory');
const data = path.join(root, 'meshcentral-data');
fs.mkdirSync(data, {recursive:true});
const password = crypto.randomBytes(32).toString('base64url') + 'aA7!';
const groupId = 'mesh//' + 'a'.repeat(64);
const nodeId = i => 'node//' + String(i).padStart(64, '0');
const group = {type:'mesh', _id:groupId, name:'Large server lab', mtype:2, domain:'', links:{'user//large-lab':{name:'large-lab',rights:4294967295}}};
const node = (i, name) => ({type:'node',_id:nodeId(i),meshid:groupId,name:name || 'Workstation ' + String(i).padStart(4,'0'),domain:'',mtype:2,icon:1,agent:{id:1,caps:15,ver:1},osdesc:'Synthetic test device'});
const sleep = ms => new Promise(resolve=>setTimeout(resolve,ms));
async function main() {
  const pass = require(path.join(pkg, 'pass.js'));
  const hash = await new Promise((resolve,reject)=>pass.hash(password,(e,salt,hash)=>e?reject(e):resolve({salt,hash})));
  const user = {type:'user',_id:'user//large-lab',name:'large-lab',domain:'',siteadmin:4294967295,links:{[groupId]:{rights:4294967295}},...hash};
  fs.writeFileSync(path.join(data,'meshcentral.db'), [user,group,...Array.from({length:2000},(_,i)=>node(i))].map(JSON.stringify).join('\n')+'\n');
  fs.writeFileSync(path.join(data,'config.json'), JSON.stringify({settings:{cert:'10.0.2.2',port:4445,portbind:'127.0.0.1',agentdownloads:{enabled:false,fetchreleases:false},exactPorts:true,redirPort:0,mpsPort:0,wanonly:true,selfupdate:false,agentupdates:false,webrtc:false,datapath:data,filespath:path.join(root,'files'),backuppath:path.join(root,'backups'),recordings:path.join(root,'recordings')},domains:{'':{title:'Large server lab',newaccounts:false,...(paging ? {maxdeviceview:25} : {}),minify:false,allowedorigin:['localhost','127.0.0.1','10.0.2.2']}}}));
  // Expose the real server instance to this local test driver, without editing the installed package.
  // All protocol, DB, event filtering and WebSockets remain the upstream implementation.
  const entry = path.join(pkg,'meshcentral.js');
  const loaded = new Module(entry, module);
  loaded.filename=entry; loaded.paths=Module._nodeModulePaths(pkg);
  loaded._compile(fs.readFileSync(entry,'utf8').replace('meshserver.Start();','global.largeServerFixture = meshserver; meshserver.Start();'),entry);
  process.argv=[process.execPath,entry,'--launch','--datapath',data];
  loaded.exports.mainStart();
  for (let i=0;i<120 && !global.largeServerFixture?.webserver?.tlsServer?.listening;i++) await sleep(500);
  const server=global.largeServerFixture;
  if (!server?.webserver) throw Error('Fixture not ready');
  const WebSocket=require(require.resolve('ws',{paths:[pkg]}));
  const ws=new WebSocket('wss://localhost:4445/control.ashx',{rejectUnauthorized:false,headers:{'x-meshauth':['large-lab',password].map(x=>Buffer.from(x).toString('base64')).join(',')}});
  const pending=new Map(); let seq=0, authenticated=false, connections=0;
  const actions={};
  let slowNodes=false, reads={full:0,addressed:0,offsets:[]};
  const originalNodesRead=server.db.GetAllTypeNoTypeFieldMeshFiltered;
  server.db.GetAllTypeNoTypeFieldMeshFiltered=function(...args) {
    if(args[4]) reads.addressed++; else {reads.full++;reads.offsets.push(args[5] || 0);}
    if(slowNodes && !args[4]) {
      const callback=args[args.length-1];
      args[args.length-1]=(...result)=>setTimeout(()=>callback(...result),150);
    }
    return originalNodesRead.apply(this,args);
  };
  ws.on('message',raw=>{ const m=JSON.parse(raw); if(m.action==='userinfo')authenticated=true; if(pending.has(m.responseid)){pending.get(m.responseid)(m);pending.delete(m.responseid);} });
  ws.on('error',()=>{});
  function command(value) { return new Promise((resolve,reject)=>{const id='driver-'+(++seq); const timer=setTimeout(()=>{pending.delete(id);reject(Error('Local command timeout'));},10000);pending.set(id,m=>{clearTimeout(timer);resolve(m);});ws.send(JSON.stringify({...value,responseid:id}));}); }
  for(let i=0;i<40&&!authenticated;i++)await sleep(250);
  if(!authenticated)throw Error('Fixture authentication failed');
  // Record connections, not credentials or raw request URLs.
  server.webserver.tlsServer.prependListener('upgrade',()=>{connections++;});
  const helper=http.createServer(async(req,res)=>{
    res.setHeader('Content-Type','application/json');
    try {
      if(req.method==='GET'&&req.url==='/fixture') return res.end(JSON.stringify({serverUrl:'https://10.0.2.2:4445/',username:'large-lab',password}));
      if(req.method==='GET'&&req.url==='/status') return res.end(JSON.stringify({connections,actions,paging,reads,version:server.currentVer}));
      if(req.method!=='POST') {res.statusCode=404;return res.end('{}');}
      if(req.url==='/reset-reads') {reads={full:0,addressed:0,offsets:[]};}
      else if(req.url==='/burst-outside'||req.url==='/burst-members') {
        const outside=req.url==='/burst-outside';
        const changed=Array.from({length:1000},(_,j)=>{const i=outside?j+1000:j;return node(i,outside?'Unrelated worker '+i:'Workstation '+String(i).padStart(4,'0')+' updated');});
        await Promise.all(changed.map(n=>new Promise(resolve=>server.db.Set(n,resolve))));
        for(const n of changed) server.DispatchEvent(server.webserver.CreateMeshDispatchTargets(groupId,[n._id]),null,
          {etype:'node',action:'changenode',node:server.webserver.CloneSafeNode(n),nodeid:n._id,meshid:groupId,domain:''});
        const marker=node(0,outside?'Workstation 0000':'Workstation 0000 updated');
        marker.osdesc=outside?'Outside burst complete':'Member burst complete';
        await new Promise(resolve=>server.db.Set(marker,resolve));
        server.DispatchEvent(server.webserver.CreateMeshDispatchTargets(groupId,[marker._id]),null,
          {etype:'node',action:'changenode',node:server.webserver.CloneSafeNode(marker),nodeid:marker._id,meshid:groupId,domain:''});
        actions[outside?'outsideBurst':'memberBurst']=1000;
      }
      else if(req.url==='/restore-burst') {
        await Promise.all(Array.from({length:2000},(_,i)=>new Promise(resolve=>server.db.Set(node(i),resolve))));
        server.ClearConnectivityState(groupId,nodeId(0),1,null,{});
      }
      else if(req.url==='/slow') {slowNodes=true;reads={full:0,addressed:0,offsets:[]};}
      else if(req.url==='/fast') {slowNodes=false;}
      else if(req.url==='/group-event') {server.DispatchEvent(server.webserver.CreateMeshDispatchTargets(groupId),null,
        {etype:'mesh',action:'meshchange',meshid:groupId,domain:''});}
      else if(req.url==='/rename-last') {await command({action:'changedevice',nodeid:nodeId(1999),name:'Renamed retained node'});}
      else if(req.url==='/restore-last') {await command({action:'changedevice',nodeid:nodeId(1999),name:'Workstation 1999'});}
      else if(req.url==='/rename') {await command({action:'changedevice',nodeid:nodeId(0),name:'Renamed live node'}); actions.rename=true;}
      else if(req.url==='/restore') {await command({action:'changedevice',nodeid:nodeId(0),name:'Workstation 0000'});}
      else if(req.url==='/connect') {server.SetConnectivityState(groupId,nodeId(0),Date.now(),1,1,null,{});actions.connect=true;}
      else if(req.url==='/disconnect') {server.ClearConnectivityState(groupId,nodeId(0),1,null,{});actions.disconnect=true;}
      else if(req.url==='/add') {
        const n=node(2000,'Added live node');
        await new Promise(resolve=>server.db.Set(n,resolve));
        server.DispatchEvent(server.webserver.CreateMeshDispatchTargets(groupId,[n._id]),null,
          {etype:'node',action:'addnode',node:server.webserver.CloneSafeNode(n),nodeid:n._id,meshid:groupId,domain:''});
        actions.add=true;
      } else if(req.url==='/remove') {await command({action:'removedevices',nodeids:[nodeId(2000)]});actions.remove=true;}
      else {res.statusCode=404;return res.end('{}');}
      res.end('{"ok":true}');
    } catch (_) {res.statusCode=500;res.end('{"ok":false}');}
  });
  helper.listen(18885,'127.0.0.1',()=>console.log('Large server fixture ready: 4445; local helper 18885; 2000 devices.'));
}
main().catch(()=>{console.error('Isolated fixture failed (sensitive details withheld)');process.exit(1);});
