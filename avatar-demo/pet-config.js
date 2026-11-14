/* Classic script; no module loader or network dependency. Presets are visual choices, not child profiles. */
(function (global) {
    'use strict';
    var pets = [
        ['fox', '🦊', '小狐狸', '#ff9a5c'], ['cat', '🐱', '小猫咪', '#ffb3c8'],
        ['bunny', '🐰', '小兔子', '#f5f0ff'], ['panda', '🐼', '小熊猫', '#ffffff'],
        ['dragon', '🐲', '小火龙', '#5fe3b3'], ['unicorn', '🦄', '独角兽', '#b388ff'],
        ['penguin', '🐧', '小企鹅', '#5fc8ff'], ['puppy', '🐶', '小狗狗', '#8a6a50'], ['slime', '🫧', '史莱姆', '#ffd166']
    ];
    var main = pets.map(function (p) { return p[3]; });
    var belly = ['#fff5ec', '#ffe0ec', '#e8dcff', '#dcf2ff', '#dcfff0', '#fff3d0', '#ffffff', '#f0e0d0'];
    var accessories = ['none', 'bow', 'scarf', 'crown', 'glasses', 'flower'];
    function base(species) {
        var pet = pets.filter(function (p) { return p[0] === species; })[0] || pets[0];
        return { species: pet[0], mainColor: pet[3], bellyColor: belly[0], accessory: 'none', eyeSize: 1, plump: 1, blush: true };
    }
    function normalize(input) {
        input = input && typeof input === 'object' ? input : {};
        var value = base(input.species);
        ['mainColor', 'bellyColor', 'accessory'].forEach(function (key) {
            var options = key === 'mainColor' ? main : key === 'bellyColor' ? belly : accessories;
            if (options.indexOf(input[key]) >= 0)
                value[key] = input[key];
        });
        [['eyeSize', .7, 1.5], ['plump', .8, 1.35]].forEach(function (range) {
            var number = input[range[0]];
            if (typeof number === 'number' && isFinite(number))
                value[range[0]] = Math.round(Math.max(range[1], Math.min(range[2], number)) * 100) / 100;
        });
        if (typeof input.blush === 'boolean')
            value.blush = input.blush;
        return value;
    }
    function palette(species, variant) {
        var value = base(species);
        if (variant === 1) {
            value.mainColor = main[(pets.findIndex(function (p) { return p[0] === value.species; }) + 3) % main.length];
            value.bellyColor = belly[2];
            value.accessory = 'scarf';
        }
        if (variant === 2) {
            value.mainColor = main[(pets.findIndex(function (p) { return p[0] === value.species; }) + 5) % main.length];
            value.bellyColor = belly[4];
            value.accessory = 'flower';
        }
        return value;
    }
    function grade(number) {
        if (!Number.isInteger(number) || number < 1 || number > 9)
            throw new Error('Invalid grade preset');
        var value = palette(pets[number - 1][0], (number - 1) % 3);
        value.accessory = accessories[(number - 1) % accessories.length];
        return value;
    }
    function encode(value) { return JSON.stringify({ schemaVersion: 1, pet: normalize(value) }); }
    function decode(text) {
        if (typeof text !== 'string' || text.length > 2048)
            throw new Error('Invalid saved pet');
        var value = JSON.parse(text);
        if (!value || value.schemaVersion !== 1 || !value.pet || typeof value.pet !== 'object')
            throw new Error('Unsupported saved pet');
        return normalize(value.pet);
    }
    global.PetConfig = { pets: pets, mainColors: main, bellyColors: belly, accessories: accessories, base: base, normalize: normalize, palette: palette, grade: grade, encode: encode, decode: decode };
})(typeof window !== 'undefined' ? window : globalThis);
