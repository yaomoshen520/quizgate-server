(function (global) {
    'use strict';
    global.PetApp = { start: function (env) {
            var renderer = env.renderer, scene = env.scene, camera = env.camera, controls = env.controls, stars = env.stars;
            var config = global.PetConfig, state = config.base('fox'), model = null, frameID = 0, last = null, motion = new global.PetMotion.Motion();
            var reduced = window.matchMedia('(prefers-reduced-motion: reduce)'), contextLost = false, dead = false, lastMotionKind = null, rotateOn = true;
            var $ = function (id) { return document.getElementById(id); };
            var storageKey = 'quizgate.pet-demo.v1';
            function announce(text) { $('notice').textContent = text; }
            try {
                var saved = localStorage.getItem(storageKey);
                if (saved)
                    state = config.decode(saved);
            }
            catch (error) {
                announce('未能恢复本机造型，先显示默认形象；原记录未删除。');
            }
            function render() { if (!dead && !contextLost)
                renderer.render(scene, camera); }
            function rebuild() { if (model) {
                scene.remove(model.root);
                global.PetModels.dispose(model.root);
            } model = global.PetModels.build(state); scene.add(model.root); applyPose(reduced.matches ? global.PetMotion.pose('idle', 0, 0) : motion.step(0)); render(); }
            function applyPose(p) { var r = model.rig; r.body.scale.set(p.breath, 1.05 / p.breath, .95); r.root.position.y = p.y; r.root.rotation.set(0, p.ry, p.rz); r.handL.rotation.z = p.hl; r.handR.rotation.z = p.hr; r.footL.position.y = p.foot; r.footR.position.y = p.foot; r.eyeL.scale.y = p.eye; r.eyeR.scale.y = p.eye; }
            function syncMotion() { if (lastMotionKind === motion.kind)
                return; lastMotionKind = motion.kind; document.querySelectorAll('[data-anim]').forEach(function (button) { var selected = button.dataset.anim === motion.kind; button.classList.toggle('on', selected); button.setAttribute('aria-pressed', String(selected)); }); }
            function cancel() { if (frameID)
                cancelAnimationFrame(frameID); frameID = 0; last = null; }
            function running() { return !dead && !contextLost && !document.hidden; }
            function tick(now) {
                frameID = 0;
                if (!running())
                    return;
                var dt = last === null ? 0 : Math.min(.05, Math.max(0, (now - last) / 1000));
                last = now;
                controls.autoRotate = rotateOn && !reduced.matches;
                controls.update(dt);
                if (!reduced.matches) {
                    applyPose(motion.step(dt));
                    stars.rotation.y += dt * .02;
                    syncMotion();
                }
                render();
                frameID = requestAnimationFrame(tick);
            }
            // Reduced motion renders on interaction only; hidden pages have no animation frame or elapsed-time advance.
            function resume() { cancel(); if (!running())
                return; if (reduced.matches) {
                applyPose(global.PetMotion.pose('idle', 0, 0));
                controls.autoRotate = false;
                controls.update(0);
                render();
            }
            else
                frameID = requestAnimationFrame(tick); }
            function interact() { if (reduced.matches && running()) {
                controls.autoRotate = false;
                controls.update(0);
                render();
            } }
            renderer.domElement.addEventListener('touchmove', interact, { passive: true });
            window.addEventListener('mousemove', interact);
            renderer.domElement.addEventListener('wheel', interact, { passive: true });
            function swatches(id, colors, key) { var box = $(id); box.textContent = ''; colors.forEach(function (color, index) { var button = document.createElement('button'); button.type = 'button'; button.className = 'sw'; button.style.background = color; button.setAttribute('aria-label', (key === 'mainColor' ? '主色' : '副色') + ' ' + (index + 1)); button.dataset.color = color; button.onclick = function () { state[key] = color; changed(); }; box.appendChild(button); }); }
            function syncUI() {
                ['species', 'accessory', 'eyeSize', 'plump'].forEach(function (key) { $(key).value = state[key]; });
                $('eyeOut').textContent = state.eyeSize.toFixed(2);
                $('plumpOut').textContent = state.plump.toFixed(2);
                ['mainColor', 'bellyColor'].forEach(function (key) { $(key).querySelectorAll('button').forEach(function (b) { var on = b.dataset.color === state[key]; b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); }); });
                $('blushToggle').classList.toggle('on', state.blush);
                $('blushToggle').setAttribute('aria-checked', String(state.blush));
                $('petGallery').querySelectorAll('button').forEach(function (b) { var on = b.dataset.id === state.species; b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); });
                var pet = config.pets.filter(function (p) { return p[0] === state.species; })[0];
                $('petName').textContent = pet[2];
            }
            function changed() { state = config.normalize(state); document.querySelectorAll('[data-grade]').forEach(function (b) { b.classList.remove('on'); b.setAttribute('aria-pressed', 'false'); }); syncUI(); rebuild(); }
            swatches('mainColor', config.mainColors, 'mainColor');
            swatches('bellyColor', config.bellyColors, 'bellyColor');
            $('species').onchange = function () { state = config.base(this.value); changed(); };
            $('accessory').onchange = function () { state.accessory = this.value; changed(); };
            ['eyeSize', 'plump'].forEach(function (key) { $(key).oninput = function () { state[key] = Number(this.value); changed(); }; });
            function toggle(id, callback) { $(id).onclick = function () { var on = this.getAttribute('aria-checked') !== 'true'; this.setAttribute('aria-checked', String(on)); this.classList.toggle('on', on); callback(on); }; }
            toggle('blushToggle', function (on) { state.blush = on; changed(); });
            toggle('rotateToggle', function (on) { rotateOn = on; controls.autoRotate = on && !reduced.matches; render(); });
            toggle('starsToggle', function (on) { stars.visible = on; render(); });
            toggle('loopToggle', function (on) { motion.loop = on; });
            document.querySelectorAll('[data-anim]').forEach(function (button) { button.onclick = function () { motion.select(this.dataset.anim); applyPose(reduced.matches ? global.PetMotion.pose('idle', 0, 0) : motion.step(0)); syncMotion(); render(); }; });
            config.pets.forEach(function (pet) { var button = document.createElement('button'); button.type = 'button'; button.className = 'pet'; button.dataset.id = pet[0]; button.innerHTML = '<span class="face"></span><span class="g"></span>'; button.querySelector('.face').textContent = pet[1]; button.querySelector('.face').style.background = pet[3] + '33'; button.querySelector('.g').textContent = pet[2]; button.onclick = function () { state = config.base(pet[0]); changed(); }; $('petGallery').appendChild(button); });
            for (var grade = 1; grade <= 9; grade++) {
                (function (number) { var button = document.createElement('button'); button.type = 'button'; button.className = 'grade'; button.textContent = number + '年级'; button.dataset.grade = number; button.setAttribute('aria-pressed', 'false'); button.onclick = function () { state = config.grade(number); changed(); button.classList.add('on'); button.setAttribute('aria-pressed', 'true'); announce(number + '年级灵感造型，仅更换外观。'); }; $('gradeGallery').appendChild(button); })(grade);
            }
            document.querySelectorAll('[data-palette]').forEach(function (button) { button.onclick = function () { state = config.palette(state.species, Number(this.dataset.palette)); changed(); }; });
            var pick = function (array) { return array[Math.floor(Math.random() * array.length)]; };
            $('randBtn').onclick = function () { state = { species: pick(config.pets)[0], mainColor: pick(config.mainColors), bellyColor: pick(config.bellyColors), accessory: pick(config.accessories), eyeSize: .7 + Math.random() * .8, plump: .8 + Math.random() * .55, blush: Math.random() > .3 }; changed(); };
            $('resetBtn').onclick = function () { state = config.base('fox'); motion.select('idle'); changed(); syncMotion(); announce('已恢复默认造型；需要时点击“保存本机”覆盖之前的造型。'); };
            $('saveBtn').onclick = function () { try {
                localStorage.setItem(storageKey, config.encode(state));
                announce('造型已保存到此浏览器；不关联孩子档案。');
            }
            catch (error) {
                announce('本机保存未完成，当前造型仍可继续使用。');
            } };
            $('shotBtn').onclick = function () { try {
                render();
                var link = document.createElement('a');
                link.download = 'quizgate-' + state.species + '.png';
                link.href = renderer.domElement.toDataURL('image/png');
                link.click();
                announce('已生成当前造型截图。');
            }
            catch (error) {
                announce('此浏览器暂不支持保存截图。');
            } };
            var panel = $('panel'), scrim = $('scrim'), previousFocus = null;
            function openPanel() { previousFocus = document.activeElement; panel.classList.add('open'); scrim.classList.add('on'); $('fab').setAttribute('aria-expanded', 'true'); if ((innerWidth <= 820 || innerHeight <= 480)) {
                panel.setAttribute('aria-modal', 'true');
                $('panelClose').focus();
            } }
            function closePanel() { panel.classList.remove('open'); scrim.classList.remove('on'); panel.removeAttribute('aria-modal'); $('fab').setAttribute('aria-expanded', 'false'); if (previousFocus && (innerWidth <= 820 || innerHeight <= 480))
                previousFocus.focus(); }
            $('fab').onclick = openPanel;
            scrim.onclick = closePanel;
            $('panelClose').onclick = closePanel;
            $('panelGrip').onclick = closePanel;
            document.addEventListener('keydown', function (e) { if (e.key === 'Escape')
                closePanel(); if (e.key === 'Tab' && (innerWidth <= 820 || innerHeight <= 480) && panel.classList.contains('open')) {
                var nodes = panel.querySelectorAll('button:not(:disabled),select,input');
                var first = nodes[0], lastNode = nodes[nodes.length - 1];
                if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    lastNode.focus();
                }
                else if (!e.shiftKey && document.activeElement === lastNode) {
                    e.preventDefault();
                    first.focus();
                }
            } });
            function resize() { var mobile = (innerWidth <= 820 || innerHeight <= 480); camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, mobile ? 1.5 : 2)); renderer.setSize(innerWidth, innerHeight); renderer.shadowMap.enabled = !mobile; controls.target.set(0, mobile ? .8 : 1.1, 0); controls.tRadius = mobile ? (innerHeight <= 480 ? 8 : 9.5) : 8; controls.radius = controls.tRadius; controls.update(0); if (!mobile) {
                panel.classList.remove('open');
                scrim.classList.remove('on');
                panel.removeAttribute('aria-modal');
                $('fab').setAttribute('aria-expanded', 'false');
            } render(); }
            window.addEventListener('resize', resize);
            document.addEventListener('visibilitychange', resume);
            function reducedChanged() { document.querySelectorAll('[data-anim]').forEach(function (button) { button.disabled = reduced.matches; }); $('loopToggle').disabled = reduced.matches; $('rotateToggle').disabled = reduced.matches; $('motionNotice').hidden = !reduced.matches; if (reduced.matches) {
                motion.select('idle');
                syncMotion();
            } resume(); }
            if (reduced.addEventListener)
                reduced.addEventListener('change', reducedChanged);
            else
                reduced.addListener(reducedChanged);
            renderer.domElement.addEventListener('webglcontextlost', function (e) { e.preventDefault(); contextLost = true; cancel(); $('renderError').hidden = false; });
            renderer.domElement.addEventListener('webglcontextrestored', function () { contextLost = false; $('renderError').hidden = true; resize(); resume(); });
            $('retryRender').onclick = function () { location.reload(); };
            window.addEventListener('pagehide', function (e) { cancel(); if (!e.persisted) {
                dead = true;
                scene.remove(model.root);
                global.PetModels.dispose(model.root);
                global.PetModels.dispose(scene);
                renderer.dispose();
            } });
            window.addEventListener('pageshow', function () { if (!dead)
                resume(); });
            global.PetApp.inspect = function () { var meshes = 0; model.root.traverse(function (o) { if (o.isMesh)
                meshes++; }); return { species: state.species, petMeshes: meshes, drawCalls: renderer.info.render.calls, geometries: renderer.info.memory.geometries, textures: renderer.info.memory.textures, scheduled: !!frameID, hidden: document.hidden, reducedMotion: reduced.matches }; };
            syncUI();
            syncMotion();
            rebuild();
            resize();
            reducedChanged();
        } };
})(window);
