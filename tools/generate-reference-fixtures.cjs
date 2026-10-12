'use strict';
// 只读取交接包中的原版 JS 引擎；生成后的压缩夹具随项目保存，运行 Java 测试不依赖 Node 或交接包。
const fs = require('fs'), path = require('path'), zlib = require('zlib'), crypto = require('crypto'), assert = require('assert/strict');
const bundle = path.resolve(process.argv[2] || path.join(__dirname, '../../AI接手资料-2026-10-05'));
const engineFile = path.join(bundle, '卡牌与规则数据/reference-engine.cjs');
const E = require(engineFile);
const sourceHash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const keys = ['mana','max','reserve','turnBudget','turn','completed','resourcePending','deathPending',
  'reserveReceived','resourceReceived','resourceLost','deathReceived','deathLost','deathTriggered',
  'blocked','unitsPlayed','spellsPlayed','resourcesPlayed','autoCompleted'];
function snapshot(s) {
  return {active:s.active,balance:s.balance,over:s.over,winner:s.winner,
    players:s.players.map(p => ({deck:[...p.deck],hand:[...p.hand],grave:[...p.grave],
      board:p.board.map(u => u && {id:u.id,hp:u.hp,enteredTurn:u.enteredTurn ?? 0,anxiety:u.anxiety || 0}),
      ...Object.fromEntries(keys.map(k => [k,p[k] || 0]))}))};
}
function create(rule, mode, seed, first, source) {
  const state = E.create(mode, seed, first, ['A','B'], rule);
  return {state, fixture:{source,rule,mode,seed,first,initialDecks:state.initialDecks,
    initial:snapshot(state),steps:[]}};
}
function apply(run, action) {
  const {state:s,fixture} = run;
  if(action.type === 'play') E.play(s, action.index, action.lane, action.sacrifice);
  else E.end(s, action.blocked || false);
  E.fastForward(s);
  fixture.steps.push({action,expected:snapshot(s)});
}
const fixtures = [];
const seeds = ['test-01','0','1','42','99999','中文种子','心🃏理',''];
for(const rule of ['F','G']) for(const mode of ['base','spells']) for(const [n,seed] of seeds.entries()) {
  const run = create(rule, mode, seed, n % 2, 'synthetic');
  const s = run.state;
  while(!s.over) {
    const p=s.players[s.active], q=s.players[1-s.active];
    for(let i=p.hand.length-1;i>=0 && !s.over;i--) {
      const c=E.C[p.hand[i]];
      if(c.c>p.mana) continue;
      const lane=c.spell?q.board.findIndex(u=>u&&!E.C[u.id].resource):p.board.findIndex(u=>!u);
      const sacrifice=p.board.findIndex(Boolean);
      if(lane<0 || (c.id==='grief'&&sacrifice<0)) continue;
      apply(run,{type:'play',index:i,lane,sacrifice});
    }
    if(!s.over) apply(run,{type:'end',blocked:p.board.every(Boolean)&&p.hand.some(id=>!E.C[id].spell)});
  }
  fixtures.push(run.fixture);
}
// 历史记录从 turn_start / play 事件恢复用户命令；最后再次断言原始终态，而非只相信重放器。
const recordDir = path.join(bundle,'测试记录/测试员3');
for(const filename of fs.readdirSync(recordDir).filter(n=>n.endsWith('.json')).sort()) {
  const original = JSON.parse(fs.readFileSync(path.join(recordDir,filename),'utf8')).game;
  assert.equal(original.ruleSet,'F');
  const run = create('F',original.mode,original.seed,original.first,filename);
  run.fixture.sourceSha256 = sourceHash(path.join(recordDir,filename));
  let started=false, blocked=false;
  for(const e of original.events) {
    if(e.type==='blocked') blocked=true;
    if(e.type==='turn_start') {
      if(started) { apply(run,{type:'end',blocked}); blocked=false; }
      started=true;
    }
    if(['play_unit','play_spell','play_resource'].includes(e.type)) {
      const index=e.handIndex ?? run.state.players[run.state.active].hand.indexOf(e.card);
      apply(run,{type:'play',index,lane:e.lane,sacrifice:e.sacrifice ?? -1});
    }
  }
  if(!run.state.over) apply(run,{type:'end',blocked});
  assert.deepEqual(snapshot(run.state),snapshot(original),'原始真人记录与 JS 重放结果不一致：'+filename);
  fixtures.push(run.fixture);
}
const result = {format:'prototype-build07-per-action-v1',referenceEngineSha256:sourceHash(engineFile),playerFields:keys,fixtures};
const output = path.resolve(__dirname,'../src/test/resources/prototype-fixtures.json.gz');
fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,zlib.gzipSync(JSON.stringify(result),{level:9}));
console.log(JSON.stringify({output,games:fixtures.length,synthetic:fixtures.filter(f=>f.source==='synthetic').length,
  historical:fixtures.filter(f=>f.source!=='synthetic').length,actions:fixtures.reduce((n,f)=>n+f.steps.length,0),bytes:fs.statSync(output).size}));
