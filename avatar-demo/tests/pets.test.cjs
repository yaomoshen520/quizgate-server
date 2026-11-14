const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const THREE=require('../vendor/three.min.js');
const context=vm.createContext({window:{},THREE,console});
for(const file of ['pet-config.js','pet-motion.js','pet-models.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),context,{filename:file});
const {PetConfig:config,PetMotion:motion,PetModels:models}=context.window;
test('nine named species, exact default colors, three distinct palettes and nine grade choices',()=>{
  assert.equal(config.pets.length,9);assert.equal(new Set(config.mainColors).size,9);assert.equal(config.bellyColors.length,8);
  for(const pet of config.pets){assert.equal(config.base(pet[0]).mainColor,pet[3]);assert.equal(new Set([0,1,2].map(i=>config.palette(pet[0],i).mainColor)).size,3);}
  for(let grade=1;grade<=9;grade++)assert.equal(config.grade(grade).species,config.pets[grade-1][0]);
  for(const grade of [0,10,1.5,'1',NaN])assert.throws(()=>config.grade(grade));
});
test('saved appearances roundtrip without identity, URLs or unknown fields; corrupt/oversized records fail',()=>{
  const original=config.palette('panda',1),encoded=config.encode({...original,childID:'private',token:'private'});
  assert.deepEqual(JSON.parse(config.encode(config.decode(encoded))),JSON.parse(encoded));assert.ok(!encoded.includes('private'));
  for(const value of ['{bad',' '.repeat(2049),'{"schemaVersion":2,"pet":{}}'])assert.throws(()=>config.decode(value));
  const safe=config.normalize({species:'not-a-pet',mainColor:'https://untrusted',eyeSize:Infinity,plump:100,blush:'false'});
  assert.equal(safe.species,'fox');assert.equal(safe.eyeSize,1);assert.equal(safe.plump,1.35);assert.equal(safe.blush,true);
});
test('animation changes reset feet and transforms, repeated active state is idempotent',()=>{
  const clock=new motion.Motion();clock.select('jump');for(let i=0;i<12;i++)clock.step(.03);
  assert.ok(clock.step(.03).foot>.18);const elapsed=clock.time;assert.equal(clock.select('jump'),false);assert.equal(clock.time,elapsed);
  clock.select('wave');const pose=clock.step(0);assert.equal(pose.foot,.18);assert.equal(pose.ry,0);assert.equal(pose.rz,0);
  clock.select('spin');clock.step(.05);clock.select('idle');assert.equal(clock.step(0).ry,0);
});
test('once returns to idle; long stalls cannot advance the visible animation clock',()=>{
  const clock=new motion.Motion();clock.loop=false;clock.select('wave');clock.step(999);assert.equal(clock.time,.05);
  for(let i=0;i<50;i++)clock.step(.05);assert.equal(clock.kind,'idle');
  const before=clock.visibleTime;clock.step(-10);assert.equal(clock.visibleTime,before);assert.equal(clock.select('unknown'),false);
});
test('all 9 x 6 x 3 appearances build finite meshes within the 40-pet-mesh budget',()=>{
  let maxMeshes=0,maxTriangles=0;
  for(const pet of config.pets)for(const accessory of config.accessories)for(let variant=0;variant<3;variant++){
    const settings=config.palette(pet[0],variant);settings.accessory=accessory;settings.plump=variant===0?.8:1.35;settings.eyeSize=variant===0?.7:1.5;
    const model=models.build(settings);let count=0,triangles=0;
    model.root.traverse(o=>{if(!o.isMesh)return;count++;const p=o.geometry.attributes.position;for(const n of p.array)assert.ok(Number.isFinite(n));triangles+=(o.geometry.index?o.geometry.index.count:p.count)/3;});
    maxMeshes=Math.max(maxMeshes,count);maxTriangles=Math.max(maxTriangles,triangles);assert.ok(count<=40,pet[0]+'/'+accessory+' meshes='+count);
    assert.ok(model.rig.eyeL.parent===model.root);models.dispose(model.root);
  }
  console.log(JSON.stringify({variants:162,maxPetMeshes:maxMeshes,maxPetTriangles:maxTriangles,scope:'CPU geometry; renderer draw calls and target-device FPS are separate'}));
});
test('shared geometry and materials dispose once per live resource over repeated rebuilds',()=>{
  for(let i=0;i<30;i++){
    const model=models.build({...config.grade(i%9+1),accessory:'crown'}),counts=new Map();
    model.root.traverse(o=>{for(const resource of [o.geometry,o.material])if(resource&&!counts.has(resource)){counts.set(resource,0);resource.addEventListener('dispose',()=>counts.set(resource,counts.get(resource)+1));}});
    models.dispose(model.root);for(const count of counts.values())assert.equal(count,1);
  }
});
test('runtime stays pinned and all HTML script sources are local classic scripts',()=>{
  assert.equal(THREE.REVISION,'147');const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  for(const match of html.matchAll(/<script\s+([^>]+)>/g)){assert.ok(!/type=["']module/.test(match[1]));const src=match[1].match(/src="([^"]+)"/)[1];assert.ok(!src.includes('://'));assert.ok(fs.existsSync(path.join(__dirname,'..',src)));}
});
