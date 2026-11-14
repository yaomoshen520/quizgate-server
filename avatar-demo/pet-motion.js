(function (global) {
    'use strict';
    var durations = { idle: 0, wave: 2, jump: 1.5, dance: 3, spin: 2 };
    function pose(kind, time, visibleTime) {
        var p = { y: Math.sin(visibleTime * 2) * .03, ry: 0, rz: 0, hl: Math.sin(visibleTime * 2) * .1, hr: -Math.sin(visibleTime * 2) * .1, foot: .18, eye: 1, breath: 1 + Math.sin(visibleTime * 2) * .02 };
        var blink = visibleTime % 3.8;
        if (blink > 3.55 && blink < 3.71)
            p.eye = Math.max(.08, Math.abs(blink - 3.63) / .08);
        if (kind === 'wave')
            p.hr = 2.2 + Math.sin(time * 8) * .5;
        if (kind === 'jump') {
            var j = Math.abs(Math.sin(time * 4));
            p.y = j * .7;
            p.hl = j * 1.5;
            p.hr = -j * 1.5;
            p.foot = .18 + j * .2;
        }
        if (kind === 'dance') {
            p.ry = Math.sin(time * 3) * .5;
            p.y = Math.abs(Math.sin(time * 6)) * .2;
            p.rz = Math.sin(time * 6) * .1;
            p.hl = Math.sin(time * 6) * 1.3;
            p.hr = Math.cos(time * 6) * 1.3;
        }
        if (kind === 'spin') {
            p.ry = time * Math.PI;
            p.hl = 1.2;
            p.hr = -1.2;
        }
        return p;
    }
    function Motion() { this.kind = 'idle'; this.time = 0; this.visibleTime = 0; this.loop = true; }
    Motion.prototype.select = function (kind) { if (!Object.prototype.hasOwnProperty.call(durations, kind))
        return false; if (this.kind === kind)
        return false; this.kind = kind; this.time = 0; return true; };
    Motion.prototype.step = function (delta) { delta = Math.max(0, Math.min(.05, Number(delta) || 0)); this.time += delta; this.visibleTime += delta; if (!this.loop && durations[this.kind] && this.time >= durations[this.kind]) {
        this.kind = 'idle';
        this.time = 0;
    } return pose(this.kind, this.time, this.visibleTime); };
    global.PetMotion = { Motion: Motion, pose: pose };
})(typeof window !== 'undefined' ? window : globalThis);
