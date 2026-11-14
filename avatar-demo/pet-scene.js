(function () {
    'use strict';
    try {
        /* 兼容老浏览器/微信内核：经典脚本 + 全局 THREE，无 importmap/ES 模块/CDN */
        function OrbitCam(cam, dom, target) {
            this.target = target.clone();
            this.theta = 0;
            this.phi = Math.PI / 2.3;
            this.radius = 8;
            this.tTheta = 0;
            this.tPhi = this.phi;
            this.tRadius = 8;
            this.autoRotate = true;
            this.autoSpeed = 1.1;
            this.minR = 4;
            this.maxR = 15;
            this.minPhi = .35;
            this.maxPhi = Math.PI * .55;
            var self = this, drag = false, px = 0, py = 0, pinch = 0;
            function down(x, y) { drag = true; px = x; py = y; }
            function move(x, y) { if (!drag)
                return; var dx = x - px, dy = y - py; px = x; py = y; self.tTheta -= dx * .005; self.tPhi = Math.max(self.minPhi, Math.min(self.maxPhi, self.tPhi - dy * .005)); }
            function up() { drag = false; }
            function zoom(f) { self.tRadius = Math.max(self.minR, Math.min(self.maxR, self.tRadius * f)); }
            dom.addEventListener('mousedown', function (e) { down(e.clientX, e.clientY); });
            window.addEventListener('mousemove', function (e) { move(e.clientX, e.clientY); });
            window.addEventListener('mouseup', up);
            dom.addEventListener('wheel', function (e) { e.preventDefault(); zoom(Math.exp(Math.max(-100, Math.min(100, e.deltaY)) * .001)); }, { passive: false });
            dom.addEventListener('touchstart', function (e) { if (e.touches.length === 1)
                down(e.touches[0].clientX, e.touches[0].clientY);
            else if (e.touches.length === 2) {
                drag = false;
                pinch = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
            } }, { passive: true });
            dom.addEventListener('touchmove', function (e) { e.preventDefault(); if (e.touches.length === 1)
                move(e.touches[0].clientX, e.touches[0].clientY);
            else if (e.touches.length === 2) {
                var d = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
                if (pinch > 0)
                    zoom(pinch / d);
                pinch = d;
            } }, { passive: false });
            dom.addEventListener('touchend', function (e) { pinch = 0; up(); if (e.touches.length === 1)
                down(e.touches[0].clientX, e.touches[0].clientY); });
            dom.addEventListener('touchcancel', up);
            this.update = function (dt) { if (this.autoRotate && !drag)
                this.tTheta += this.autoSpeed * dt * .12; this.theta += (this.tTheta - this.theta) * .08; this.phi += (this.tPhi - this.phi) * .08; this.radius += (this.tRadius - this.radius) * .08; var sp = Math.sin(this.phi); cam.position.set(this.target.x + this.radius * sp * Math.sin(this.theta), this.target.y + this.radius * Math.cos(this.phi), this.target.z + this.radius * sp * Math.cos(this.theta)); cam.lookAt(this.target); };
        }
        const stage = document.getElementById('stage');
        if (THREE.ColorManagement)
            THREE.ColorManagement.legacyMode = false;
        const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: false });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, (innerWidth <= 820 || innerHeight <= 480) ? 1.5 : 2));
        renderer.setSize(innerWidth, innerHeight);
        renderer.shadowMap.enabled = innerWidth > 820 && innerHeight > 480;
        renderer.shadowMap.type = THREE.PCFSoftShadowMap;
        renderer.outputEncoding = THREE.sRGBEncoding;
        renderer.toneMapping = THREE.ACESFilmicToneMapping;
        renderer.toneMappingExposure = 1.0;
        stage.appendChild(renderer.domElement);
        const scene = new THREE.Scene();
        const camera = new THREE.PerspectiveCamera(38, innerWidth / innerHeight, .1, 100);
        camera.position.set(0, 1.6, 8);
        const controls = new OrbitCam(camera, renderer.domElement, new THREE.Vector3(0, 1.1, 0));
        scene.add(new THREE.HemisphereLight(0xfff0f5, 0x3a2a6a, .75));
        const key = new THREE.DirectionalLight(0xffe6c8, 1.5);
        key.position.set(4, 7, 5);
        key.castShadow = true;
        key.shadow.mapSize.set(1024, 1024);
        key.shadow.camera.left = -6;
        key.shadow.camera.right = 6;
        key.shadow.camera.top = 6;
        key.shadow.camera.bottom = -6;
        key.shadow.radius = 6;
        key.shadow.bias = -0.0005;
        scene.add(key);
        const rim = new THREE.DirectionalLight(0x7fd8ff, 1.0);
        rim.position.set(-5, 4, -6);
        scene.add(rim);
        const fill = new THREE.DirectionalLight(0xff9fc8, .5);
        fill.position.set(-3, 2, 5);
        scene.add(fill);
        const ground = new THREE.Mesh(new THREE.CylinderGeometry(4.2, 4.6, .5, 64), new THREE.MeshStandardMaterial({ color: 0x4a3585, roughness: .85 }));
        ground.position.y = -.25;
        ground.receiveShadow = true;
        scene.add(ground);
        const ring = new THREE.Mesh(new THREE.TorusGeometry(4.2, .07, 16, 100), new THREE.MeshStandardMaterial({ color: 0xffd166, emissive: 0xffa63d, emissiveIntensity: .8, roughness: .4 }));
        ring.rotation.x = Math.PI / 2;
        ring.position.y = .02;
        scene.add(ring);
        const starGeo = new THREE.BufferGeometry();
        const N = 260, pos = new Float32Array(N * 3), col = new Float32Array(N * 3);
        const pal = [0xff6fa5, 0xffd166, 0x5fe3b3, 0x5fc8ff, 0xb388ff].map(c => new THREE.Color(c));
        for (let i = 0; i < N; i++) {
            const r = 9 + Math.random() * 14, th = Math.random() * Math.PI * 2, ph = Math.acos(2 * Math.random() - 1);
            pos[i * 3] = r * Math.sin(ph) * Math.cos(th);
            pos[i * 3 + 1] = Math.abs(r * Math.cos(ph)) * .7 + 1;
            pos[i * 3 + 2] = r * Math.sin(ph) * Math.sin(th);
            const c = pal[i % pal.length];
            col[i * 3] = c.r;
            col[i * 3 + 1] = c.g;
            col[i * 3 + 2] = c.b;
        }
        starGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
        starGeo.setAttribute('color', new THREE.BufferAttribute(col, 3));
        const stars = new THREE.Points(starGeo, new THREE.PointsMaterial({ size: .14, vertexColors: true, transparent: true, opacity: .85, depthWrite: false }));
        scene.add(stars);
        PetApp.start({ renderer: renderer, scene: scene, camera: camera, controls: controls, stars: stars });
    }
    catch (error) {
        document.getElementById('renderError').hidden = false;
        document.querySelectorAll('.panel button,.panel select,.panel input').forEach(function (el) { el.disabled = true; });
        document.getElementById('retryRender').onclick = function () { location.reload(); };
    }
})();
