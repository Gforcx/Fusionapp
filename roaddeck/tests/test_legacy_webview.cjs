'use strict';
// Regression checks for the shipped JavaScript on older Android WebViews.
// Syntax is checked as ES2019. The DOM and Android bridge are simulated;
// this is not a browser, emulator, device or live-service integration test.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'ui');

let acorn;
try {
    acorn = require('acorn');
} catch (error) {
    // Node includes Acorn internally. Reuse it when no npm package is installed.
    const source = process.binding('natives')['internal/deps/acorn/acorn/dist/acorn'];
    if (!source) throw new Error('Acorn unavailable: install the acorn npm package to run this test.');
    const module = {exports: {}};
    new Function('exports', 'module', source)(module.exports, module);
    acorn = module.exports;
}
for (const filename of ['app.js', 'overlay.js']) {
    const source = fs.readFileSync(path.join(assets, filename), 'utf8');
    acorn.parse(source, {ecmaVersion: 2019});
    assert.ok(!/replaceChildren|\?\./.test(source), 'New-only syntax/API remains in ' + filename);
    console.log(filename + ': ES2019 syntax PASS (Acorn ' + acorn.version + ')');
}

let checks = 0;
function check(value, message) {
    assert.ok(value, message);
    checks++;
}
class Element {
    constructor(id = '') {
        this.id = id;
        this.children = [];
        this.parentNode = null;
        this.attrs = {};
        this.style = {};
        this.dataset = {};
        this.listeners = {};
        this.textContent = '';
        this.value = '';
        this.checked = false;
        this.hidden = false;
        this.elements = new Proxy({}, {get: (object, key) => object[key] || (object[key] = new Element(String(key)))});
        const classes = new Set();
        this.classList = {contains: name => classes.has(name), toggle: (name, enabled) => {
            const active = enabled === undefined ? !classes.has(name) : !!enabled;
            if (active) classes.add(name); else classes.delete(name);
            return active;
        }};
    }
    get firstChild() { return this.children[0] || null; }
    appendChild(child) {
        if (child.parentNode) child.parentNode.removeChild(child);
        this.children.push(child);
        child.parentNode = this;
        return child;
    }
    removeChild(child) {
        const index = this.children.indexOf(child);
        assert.ok(index >= 0);
        this.children.splice(index, 1);
        child.parentNode = null;
        return child;
    }
    append(...children) { for (const child of children) this.appendChild(child); }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    getAttribute(key) { return this.attrs[key] === undefined ? null : this.attrs[key]; }
    addEventListener(type, fn) { (this.listeners[type] || (this.listeners[type] = [])).push(fn); }
    querySelector(selector) {
        const subs = this.subs || (this.subs = {});
        return subs[selector] || (subs[selector] = new Element());
    }
    querySelectorAll() { return []; }
    getBoundingClientRect() { return {left: 0, top: 0, width: 400, height: 400}; }
    checkValidity() { return true; }
    reportValidity() { return true; }
    closest(selector) {
        if (selector === '[data-act]' && this.dataset.act) return this;
        if (selector === '[data-section]' && this.dataset.section) return this;
        return null;
    }
    click() { for (const fn of this.listeners.click || []) fn({target: this, preventDefault() {}}); }
}
function load(filename, options = {}) {
    const nodes = new Map(), events = {}, calls = [], timers = new Map();
    let timerId = 0;
    const get = id => {
        if (!nodes.has(id)) nodes.set(id, new Element(id));
        return nodes.get(id);
    };
    const document = {
        body: get('body'),
        getElementById: get,
        createElement: tag => new Element(tag),
        querySelector: selector => get(selector),
        querySelectorAll: () => [],
        addEventListener: (type, fn) => (events[type] || (events[type] = [])).push(fn)
    };
    const context = {
        document,
        Native: {post: serialized => calls.push(JSON.parse(serialized))},
        innerWidth: options.width || 1280,
        innerHeight: options.height || 800,
        location: {search: options.search || ''},
        addEventListener() {},
        setTimeout: fn => {timers.set(++timerId, fn); return timerId;},
        clearTimeout: id => timers.delete(id),
        setInterval: () => 1,
        clearInterval() {},
        URL,
        console
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(assets, filename), 'utf8'), context, {filename});
    function clickAction(action) {
        const element = new Element();
        element.dataset.act = action;
        for (const fn of events.click || []) fn({target: element, preventDefault() {}});
    }
    function flushTimeouts() {
        const queued = Array.from(timers.values());timers.clear();
        for (const fn of queued) fn();
    }
    return {context, get, calls, clickAction, flushTimeouts};
}

const main = load('app.js');
check(main.calls.length === 1 && main.calls[0].type === 'ready', 'Main bridge ready without modern DOM APIs');
const rd = main.context.RoadDeck;
rd.receive({type: 'diagnostics', data: {}});
check(main.get('diagnostics').textContent.includes('Panneau actif : non'), 'Diagnostics tolerate missing wazePanel');
rd.receive({type: 'diagnostics', data: {wazePanel: null}});
check(main.get('diagnostics').textContent.includes('Panneau actif : non'), 'Diagnostics tolerate null wazePanel');
rd.receive({type: 'diagnostics', data: {wazePanel: {active: true}}});
check(main.get('diagnostics').textContent.includes('Panneau actif : oui'), 'Diagnostics active panel');

main.get('pickerResults').appendChild(new Element('old-row'));
main.clickAction('apps');
check(main.get('pickerResults').children.length === 1, 'Applications loading placeholder replaces previous rows');
rd.receive({type: 'apps', data: {purpose: 'open', items: [
    {name: 'Spotify', package: 'com.spotify.music'},
    {name: 'Deezer', package: 'deezer.android.app'}
]}});
check(main.get('pickerResults').children.length === 2, 'Application results replace loading placeholder');
main.get('pickerResults').children[0].click();
check(main.calls[main.calls.length - 1].type === 'openApp', 'Application selection still dispatches native command');

rd.radioDirectory();
check(main.get('pickerResults').children.length === 1, 'Empty radio directory replaces application rows');
main.get('radioQuery').value = 'FIP';
main.clickAction('searchRadio');
const request = main.calls[main.calls.length - 1].data.request;
rd.receive({type: 'radioResults', data: {request, stations: [{name: 'FIP', url: 'https://example.invalid/fip'}]}});
check(main.get('pickerResults').children.length === 1 && main.get('pickerResults').children[0].children[0].textContent === 'FIP', 'Radio search replaces loading row');
main.get('pickerResults').children[0].click();
check(main.calls[main.calls.length - 1].type === 'radioPlay', 'Radio result still dispatches radioPlay');

rd.openSettings('weather');
main.get('cityQuery').value = 'Paris';
main.clickAction('searchCities');
const cityRequest = main.calls[main.calls.length - 1].data.request;
rd.receive({type: 'cities', data: {request: cityRequest, items: [{name: 'Paris', country: 'France', latitude: 48.85, longitude: 2.35}]}});
check(main.get('cityResults').children.length === 1 && main.get('cityResults').children[0].children[0].textContent === 'Paris, France', 'City results replace loading row');
main.get('cityResults').children[0].click();
check(main.get('cityResults').children.length === 0, 'Selecting city clears results');
check(main.calls[main.calls.length - 1].type === 'weatherRefresh', 'Selecting city still refreshes weather');

main.get('tuyaScenes').appendChild(new Element('old-scene'));
rd.receive({type: 'tuyaScenes', data: [{name: 'Portail', id: 'scene-test'}]});
check(main.get('tuyaScenes').children.length === 1, 'Tuya scenes replace old contents');
main.get('tuyaScenes').children[0].click();
check(main.get('settingsForm').elements.tuyaScene.value === 'scene-test', 'Selecting scene still updates configuration');
check(!main.calls.some(item => item.type === 'gate'), 'Scene selection sends no gate command');
rd.receive({type: 'tuyaScenes', data: []});
check(main.get('tuyaScenes').children.length === 1 && main.get('tuyaScenes').children[0].textContent.includes('aucune scène'), 'Empty scene result displays placeholder');

const overlay = load('overlay.js');
check(overlay.calls.length === 1 && overlay.calls[0].type === 'ready', 'Overlay bridge ready without modern DOM APIs');
overlay.context.RoadDeckOverlay.receive({type: 'favorites', data: [
    {name: 'FIP', url: 'https://example.invalid/fip'},
    {name: 'Jazz', url: 'https://example.invalid/jazz'}
]});
overlay.get('stationList').appendChild(new Element('old-favorite'));
overlay.clickAction('radio');
overlay.clickAction('source');
check(overlay.get('stationList').children.length === 2, 'Favorites replace prior rows');
overlay.get('stationList').children[1].click();
check(overlay.calls[overlay.calls.length - 1].type === 'radioPlay' && overlay.calls[overlay.calls.length - 1].data.name === 'Jazz', 'Favorite dispatch keeps selected station');
check(overlay.get('stations').hidden === true, 'Favorite selection closes station chooser');
overlay.context.RoadDeckOverlay.receive({type: 'favorites', data: []});
overlay.clickAction('source');
check(overlay.get('stationList').children.length === 1 && overlay.get('stationList').children[0].textContent.includes('Ajoutez'), 'Empty favorites replace prior rows with placeholder');
check(Element.prototype.replaceChildren === undefined, 'DOM harness intentionally lacks replaceChildren');
console.log('PASS: ' + checks + ' targeted UI compatibility assertions; DOM/Android bridge simulated, no browser or device used.');
module.exports = {load, Element, assets};
