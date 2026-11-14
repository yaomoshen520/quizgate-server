/* Local procedural meshes. No network, downloaded art, or model loader. */
(function (global) {
    'use strict';
    var rig, materials;
    function mat(c, r) { r = r === undefined ? .55 : r; var key = c + ':' + r; if (!materials[key])
        materials[key] = new THREE.MeshStandardMaterial({ color: c, roughness: r, metalness: .02 }); return materials[key]; }
    function buildPet(cfg) {
        const g = new THREE.Group();
        const P = cfg.plump;
        const main = mat(cfg.mainColor), belly = mat(cfg.bellyColor, .6), dark = mat(0x3a2a30, .4);
        // 圆滚滚身体
        const body = new THREE.Mesh(new THREE.SphereGeometry(1 * P, 24, 16), main);
        body.scale.set(1, 1.05, .95);
        body.position.y = 1.05;
        body.castShadow = true;
        g.add(body);
        // 肚皮
        const bel = new THREE.Mesh(new THREE.SphereGeometry(.72 * P, 24, 16), belly);
        bel.scale.set(.85, .9, .5);
        bel.position.set(0, .85, .62 * P);
        g.add(bel);
        // 小脚
        const footGeo = new THREE.SphereGeometry(.26 * P, 20, 16);
        const fL = new THREE.Mesh(footGeo, main);
        fL.scale.set(1, .7, 1.2);
        fL.position.set(-.42 * P, .18, .25);
        fL.castShadow = true;
        const fR = fL.clone();
        fR.position.x = .42 * P;
        g.add(fL, fR);
        // 小手
        const handGeo = new THREE.SphereGeometry(.2 * P, 18, 16);
        const hL = new THREE.Group();
        const hLm = new THREE.Mesh(handGeo, main);
        hLm.position.y = -.15;
        hL.add(hLm);
        hL.position.set(-.95 * P, 1.0, .2);
        const hR = new THREE.Group();
        const hRm = new THREE.Mesh(handGeo, main);
        hRm.position.y = -.15;
        hR.add(hRm);
        hR.position.set(.95 * P, 1.0, .2);
        g.add(hL, hR);
        // 大眼睛
        const E = cfg.eyeSize;
        const white = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: .15 });
        const iris = new THREE.MeshStandardMaterial({ color: 0x3a2a40, roughness: .1 });
        function eye(x) {
            const grp = new THREE.Group();
            const w = new THREE.Mesh(new THREE.SphereGeometry(.24 * E, 24, 16), white);
            w.scale.set(1, 1.25, .5);
            const d = new THREE.Mesh(new THREE.SphereGeometry(.16 * E, 24, 16), iris);
            d.position.set(0, -.01, .13);
            d.scale.set(1, 1.25, .5);
            const h1 = new THREE.Mesh(new THREE.SphereGeometry(.06 * E, 12, 12), new THREE.MeshBasicMaterial({ color: 0xffffff }));
            h1.position.set(.06, .09, .22);
            const h2 = new THREE.Mesh(new THREE.SphereGeometry(.03 * E, 10, 10), new THREE.MeshBasicMaterial({ color: 0xffffff }));
            h2.position.set(-.05, -.05, .22);
            grp.add(w, d, h1, h2);
            grp.position.set(x * P, 1.35, .95 * P);
            return grp;
        }
        const eyeL = eye(-.36), eyeR = eye(.36);
        g.add(eyeL, eyeR);
        // 腮红
        if (cfg.blush) {
            const bm = new THREE.MeshBasicMaterial({ color: 0xff9fb0, transparent: true, opacity: .6 });
            const bL = new THREE.Mesh(new THREE.SphereGeometry(.15, 16, 16), bm);
            bL.scale.set(1, .6, .3);
            bL.position.set(-.62 * P, 1.05, .88 * P);
            const bR = bL.clone();
            bR.position.x = .62 * P;
            g.add(bL, bR);
        }
        // 嘴
        const mouth = new THREE.Mesh(new THREE.TorusGeometry(.1, .03, 10, 20, Math.PI), mat(0xc85a5a, .4));
        mouth.position.set(0, 1.02, .95 * P + .08);
        mouth.rotation.x = Math.PI;
        g.add(mouth);
        addSpecies(g, cfg, main, belly, dark);
        addAccessory(g, cfg);
        rig.root = g;
        rig.body = body;
        rig.handL = hL;
        rig.handR = hR;
        rig.eyeL = eyeL;
        rig.eyeR = eyeR;
        rig.footL = fL;
        rig.footR = fR;
        rig.baseY = 0;
        rig.baseHL = hL.rotation.z;
        rig.baseHR = hR.rotation.z;
        return g;
    }
    function addSpecies(g, cfg, main, belly, dark) {
        const s = cfg.species;
        const P = cfg.plump;
        const ear = (x, geo, m, rot) => { const e = new THREE.Mesh(geo, m); e.position.set(x, 2.0, 0); e.castShadow = true; if (rot)
            e.rotation.z = rot; g.add(e); return e; };
        if (s === 'fox' || s === 'cat') {
            const eg = new THREE.ConeGeometry(.3, .55, 4);
            ear(-.5, eg, main, .3);
            ear(.5, eg, main, -.3);
            const inner = new THREE.ConeGeometry(.15, .3, 4);
            ear(-.5, inner, belly, .3).position.y = 1.95;
            ear(.5, inner, belly, -.3).position.y = 1.95;
            // 尾巴
            if (s === 'fox') {
                const tail = new THREE.Mesh(new THREE.SphereGeometry(.35, 20, 16), main);
                tail.scale.set(.7, 1.4, .7);
                tail.position.set(.8 * P, .75, -.65);
                tail.rotation.z = -.7;
                g.add(tail);
                const tip = new THREE.Mesh(new THREE.SphereGeometry(.2, 16, 16), belly);
                tip.position.set(1.05 * P, 1.08, -.65);
                g.add(tip);
            }
            else {
                const tail = new THREE.Mesh(new THREE.TorusGeometry(.55, .12, 8, 20, Math.PI * 1.35), main);
                tail.position.set(.5 * P, .8, -.8);
                tail.rotation.z = .5;
                g.add(tail);
            }
            if (s === 'cat') { // 胡须
                const wm = mat(0xffffff, .3);
                for (const sx of [-1, 1])
                    for (let i = 0; i < 2; i++) {
                        const w = new THREE.Mesh(new THREE.CylinderGeometry(.012, .012, .4, 6), wm);
                        w.rotation.z = Math.PI / 2 + (i - .5) * .2;
                        w.position.set(sx * .7, 1.0 - i * .1, .85);
                        g.add(w);
                    }
            }
        }
        else if (s === 'bunny') {
            const eg = new THREE.CapsuleGeometry(.16, .7, 8, 16);
            ear(-.35, eg, main, .15);
            ear(.35, eg, main, -.15);
            const inner = new THREE.CapsuleGeometry(.08, .5, 8, 12);
            ear(-.35, inner, belly, .15).position.y = 1.95;
            ear(.35, inner, belly, -.15).position.y = 1.95;
            const tail = new THREE.Mesh(new THREE.SphereGeometry(.25, 16, 16), belly);
            tail.position.set(0, .8, -1.0);
            g.add(tail);
        }
        else if (s === 'panda') {
            const eg = new THREE.SphereGeometry(.28, 20, 16);
            ear(-.55, eg, dark);
            ear(.55, eg, dark);
            // 黑眼圈
            const pm = mat(0x3a2a30, .4);
            const pL = new THREE.Mesh(new THREE.SphereGeometry(.3 * cfg.eyeSize, 20, 16), pm);
            pL.scale.set(1, 1.2, .4);
            pL.position.set(-.36 * P, 1.35, .92 * P);
            const pR = pL.clone();
            pR.position.x = .36 * P;
            g.add(pL, pR);
            const tail = new THREE.Mesh(new THREE.SphereGeometry(.2, 16, 16), dark);
            tail.position.set(0, .8, -1.0);
            g.add(tail);
        }
        else if (s === 'dragon') {
            const horn = new THREE.ConeGeometry(.14, .4, 10);
            const hm = mat(0xffd166, .3);
            ear(-.35, horn, hm, .2);
            ear(.35, horn, hm, -.2);
            // 翅膀
            const wm = mat(cfg.bellyColor, .5);
            for (const sx of [-1, 1]) {
                const w = new THREE.Mesh(new THREE.ConeGeometry(.4, .8, 3), wm);
                w.scale.set(1, 1, .2);
                w.position.set(sx * 1.0, 1.3, -.4);
                w.rotation.z = sx * -.8;
                w.rotation.y = sx * .5;
                g.add(w);
            }
            const tail = new THREE.Mesh(new THREE.CapsuleGeometry(.15, .6, 8, 12), main);
            tail.position.set(0, .7, -1.1);
            tail.rotation.x = -.8;
            g.add(tail);
            const sp = new THREE.Mesh(new THREE.ConeGeometry(.12, .25, 8), hm);
            sp.position.set(0, 1.0, -1.35);
            sp.rotation.x = -1;
            g.add(sp);
        }
        else if (s === 'unicorn') {
            const horn = new THREE.ConeGeometry(.12, .6, 12);
            const hm = new THREE.MeshStandardMaterial({ color: 0xffd166, roughness: .2, metalness: .6, emissive: 0x6a4a00, emissiveIntensity: .3 });
            const h = new THREE.Mesh(horn, hm);
            h.position.set(0, 2.2, .1);
            g.add(h);
            const eg = new THREE.ConeGeometry(.2, .35, 10);
            ear(-.45, eg, main, .3);
            ear(.45, eg, main, -.3);
            // 鬃毛
            const mm = mat(0xff6fa5, .5);
            for (let i = 0; i < 4; i++) {
                const m = new THREE.Mesh(new THREE.SphereGeometry(.2, 16, 16), mm);
                m.position.set(0, 2.0 - i * .25, -.5 - i * .1);
                g.add(m);
            }
            const tail = new THREE.Mesh(new THREE.CapsuleGeometry(.18, .6, 8, 12), mm);
            tail.position.set(0, .9, -1.05);
            tail.rotation.x = -.6;
            g.add(tail);
        }
        else if (s === 'penguin') {
            // 喙
            const beak = new THREE.Mesh(new THREE.ConeGeometry(.14, .3, 10), mat(0xffa63d, .4));
            beak.position.set(0, 1.15, .95);
            beak.rotation.x = Math.PI / 2;
            g.add(beak);
            // 鳍
            const fm = mat(cfg.mainColor, .5);
            for (const sx of [-1, 1]) {
                const f = new THREE.Mesh(new THREE.SphereGeometry(.3, 16, 16), fm);
                f.scale.set(.3, 1, .6);
                f.position.set(sx * 1.0, 1.0, 0);
                f.rotation.z = sx * .3;
                g.add(f);
            }
        }
        else if (s === 'puppy') {
            const eg = new THREE.SphereGeometry(.3, 20, 16);
            const eL = ear(-.6, eg, main);
            eL.scale.set(.6, 1.2, .6);
            eL.position.y = 1.7;
            const eR = ear(.6, eg, main);
            eR.scale.set(.6, 1.2, .6);
            eR.position.y = 1.7;
            const nose = new THREE.Mesh(new THREE.SphereGeometry(.09, 12, 12), dark);
            nose.position.set(0, 1.12, .95);
            g.add(nose);
            const tail = new THREE.Mesh(new THREE.CapsuleGeometry(.12, .4, 8, 12), main);
            tail.position.set(0, 1.0, -1.0);
            tail.rotation.x = -1;
            g.add(tail);
        }
        else if (s === 'slime') {
            // 触角
            const am = mat(cfg.bellyColor, .3);
            const a = new THREE.Mesh(new THREE.CylinderGeometry(.03, .03, .4, 8), am);
            a.position.set(0, 2.2, 0);
            g.add(a);
            const ball = new THREE.Mesh(new THREE.SphereGeometry(.12, 16, 16), mat(0xffd166, .2));
            ball.position.set(0, 2.42, 0);
            g.add(ball);
            body2gloss(g);
        }
    }
    function body2gloss(g) { g.traverse(o => { if (o.isMesh && o.material && o.material.color && o.geometry.type === 'SphereGeometry' && o.geometry.parameters.radius >= 1) {
        o.material.roughness = .15;
        o.material.metalness = .1;
    } }); }
    function addAccessory(g, cfg) {
        const a = cfg.accessory, P = cfg.plump, E = cfg.eyeSize;
        if (a === 'bow') {
            const bm = mat(0xff5d8a, .4);
            const bL = new THREE.Mesh(new THREE.SphereGeometry(.2, 16, 16), bm);
            bL.scale.set(1.3, .8, .5);
            bL.position.set(-.2, 2.05, .3);
            const bR = bL.clone();
            bR.position.x = .2;
            const k = new THREE.Mesh(new THREE.SphereGeometry(.1, 12, 12), bm);
            k.position.set(0, 2.05, .35);
            g.add(bL, bR, k);
        }
        else if (a === 'scarf') {
            const sm = mat(0xff6fa5, .6);
            const s = new THREE.Mesh(new THREE.TorusGeometry(.85, .16, 16, 40), sm);
            s.position.set(0, .55, 0);
            s.rotation.x = Math.PI / 2;
            g.add(s);
            const tail = new THREE.Mesh(new THREE.CapsuleGeometry(.14, .4, 8, 12), sm);
            tail.position.set(.5, .3, .6);
            g.add(tail);
        }
        else if (a === 'crown') {
            const cm = new THREE.MeshStandardMaterial({ color: 0xffd166, roughness: .25, metalness: .7, emissive: 0x6a4a00, emissiveIntensity: .3 });
            const b = new THREE.Mesh(new THREE.CylinderGeometry(.35, .4, .2, 20), cm);
            b.position.set(0, 2.15, 0);
            g.add(b);
            for (let i = 0; i < 5; i++) {
                const p = new THREE.Mesh(new THREE.ConeGeometry(.07, .2, 8), cm);
                const ang = (i / 5) * Math.PI * 2;
                p.position.set(Math.cos(ang) * .32, 2.32, Math.sin(ang) * .32);
                g.add(p);
            }
        }
        else if (a === 'glasses') {
            const gm = mat(0x3a3a4a, .3);
            const rL = new THREE.Mesh(new THREE.TorusGeometry(.26 * E, .035, 8, 24), gm);
            rL.position.set(-.36 * P, 1.35, .95 * P + .25);
            const rR = rL.clone();
            rR.position.x = .36 * P;
            const br = new THREE.Mesh(new THREE.CylinderGeometry(.02, .02, .24, 8), gm);
            br.rotation.z = Math.PI / 2;
            br.position.set(0, 1.35, .95 * P + .27);
            g.add(rL, rR, br);
        }
        else if (a === 'flower') {
            const fm = mat(0xffd166, .4);
            const pm = mat(0xff6fa5, .4);
            for (let i = 0; i < 5; i++) {
                const p = new THREE.Mesh(new THREE.SphereGeometry(.1, 12, 12), pm);
                const ang = (i / 5) * Math.PI * 2;
                p.position.set(.55 + Math.cos(ang) * .12, 2.0 + Math.sin(ang) * .12, .3);
                g.add(p);
            }
            const c = new THREE.Mesh(new THREE.SphereGeometry(.08, 12, 12), fm);
            c.position.set(.55, 2.0, .35);
            g.add(c);
        }
    }
    // Merge static siblings by shared material. Keep breathing body, eyes, hands and feet independent.
    function batch(root) {
        var groups = {}, removed = [];
        root.children.slice().forEach(function (mesh) {
            if (!mesh.isMesh || mesh === rig.body || mesh === rig.footL || mesh === rig.footR)
                return;
            var key = mesh.material.uuid;
            (groups[key] || (groups[key] = [])).push(mesh);
        });
        Object.keys(groups).forEach(function (key) {
            var meshes = groups[key];
            if (meshes.length < 2)
                return;
            var pieces = meshes.map(function (mesh) { mesh.updateMatrix(); var geo = mesh.geometry.index ? mesh.geometry.toNonIndexed() : mesh.geometry.clone(); geo.applyMatrix4(mesh.matrix); return geo; });
            var geometry = new THREE.BufferGeometry();
            ['position', 'normal', 'uv'].forEach(function (name) {
                var total = pieces.reduce(function (n, g) { return n + g.attributes[name].array.length; }, 0), array = new Float32Array(total), offset = 0;
                pieces.forEach(function (g) { array.set(g.attributes[name].array, offset); offset += g.attributes[name].array.length; });
                geometry.setAttribute(name, new THREE.BufferAttribute(array, pieces[0].attributes[name].itemSize));
            });
            pieces.forEach(function (g) { g.dispose(); });
            var merged = new THREE.Mesh(geometry, meshes[0].material);
            merged.castShadow = meshes.some(function (m) { return m.castShadow; });
            root.add(merged);
            meshes.forEach(function (m) { removed.push(m.geometry); root.remove(m); });
        });
        var alive = new Set();
        root.traverse(function (o) { if (o.geometry)
            alive.add(o.geometry); });
        new Set(removed).forEach(function (g) { if (!alive.has(g))
            g.dispose(); });
    }
    function dispose(root) { var geometries = new Set(), mats = new Set(); root.traverse(function (o) { if (o.geometry)
        geometries.add(o.geometry); if (o.material)
        (Array.isArray(o.material) ? o.material : [o.material]).forEach(function (m) { mats.add(m); }); }); geometries.forEach(function (g) { g.dispose(); }); mats.forEach(function (m) { m.dispose(); }); }
    global.PetModels = { build: function (cfg) { rig = {}; materials = {}; var root = buildPet(global.PetConfig.normalize(cfg)); batch(root); return { root: root, rig: rig }; }, dispose: dispose };
})(window);
