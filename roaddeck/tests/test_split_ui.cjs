'use strict';
// Execute the shipped scripts against a deliberately limited DOM/Native bridge.
// These are behavior/syntax checks; they do not verify Android window placement.
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const {load, assets} = require('./test_legacy_webview.cjs');
let checks = 0;
function check(value, message) {assert.ok(value, message);checks++;}
const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
const panelHtml = fs.readFileSync(path.join(assets, 'overlay.html'), 'utf8');
const css = fs.readFileSync(path.join(assets, 'style.css'), 'utf8');

check(html.includes('name="wazeMode" value="split"'), 'Saved mode is the real split mode');
check(!html.includes('name="overlayWidth"'), 'No fake application-owned split percentage control');
check(html.includes('Activer l’assistant d’écran partagé'), 'Accessibility setup action is explained');
check(html.includes('Android contrôle la séparation'), 'System-controlled divider is disclosed');
check(!/par-dessus|sans bordure|mode overlay/.test(html), 'No obsolete overlay or borderless promise');
check(html.includes('RoadDeck 0.5.0') && panelHtml.includes('RoadDeck 0.5.0'), 'Version labels agree');
check(panelHtml.includes('‹ Waze en grand'), 'Sidebar collapse wording describes fullscreen navigation');
check(css.includes('.panel-settings .two-cols{grid-template-columns:minmax(0,1fr)'), 'Compact settings form has one column');
check(css.includes('.panel-settings>:not(.overlay):not(.toast){display:none!important}'), 'Compact mode hides the launcher background');
check(css.includes('.panel-settings .settings-content{min-height:0;'), 'Compact settings content can scroll independently');

for (const width of [260, 360, 512]) {
    const app = load('app.js', {search:'?panelSettings=1', width, height:600});
    const rd = app.context.RoadDeck;
    check(app.get('stage').classList.contains('panel-settings'), 'Compact class at ' + width);
    check(app.get('stage').style.transform === 'none', 'No scaled 1648px canvas at ' + width);
    check(app.get('stage').style.width === '100%' && app.get('stage').style.height === '100%', 'Use the Android pane viewport at ' + width);
    check(app.get('overlay').hidden === false && app.get('settingsLayout').hidden === false, 'Settings visible immediately at ' + width);
    check(app.calls.length === 1 && app.calls[0].type === 'ready', 'One native handshake at ' + width);
    rd.receive({type:'settings',data:{wazeAuto:true}});
    app.flushTimeouts();
    check(!app.calls.some(c => c.type === 'openWaze'), 'Settings activity never auto-launches Waze at ' + width);
    app.clickAction('close');
    check(app.calls[app.calls.length-1].type === 'closePanelSettings', 'Close exits settings activity at ' + width);
}

const compact = load('app.js', {search:'?panelSettings=1', width:360, height:600});
const rd = compact.context.RoadDeck;
rd.openSettings('radio');rd.radioDirectory();
check(compact.get('picker').hidden === false, 'Radio picker opens in the compact pane');
rd.back();
check(compact.get('settingsLayout').hidden === false && compact.get('picker').hidden === true, 'Back from picker returns to settings');
check(!compact.calls.some(c => c.type === 'closePanelSettings'), 'Picker back does not close the settings activity');
rd.back();
check(compact.calls[compact.calls.length-1].type === 'closePanelSettings', 'Back from settings exits the settings activity');

compact.clickAction('wazeSetup');
check(compact.calls[compact.calls.length-1].type === 'wazeSetup', 'Setup asks native Android accessibility settings');
rd.receive({type:'wazeState', data:{mode:'split',active:false,requested:true,accessibilityGranted:false,message:'Activez l’assistant RoadDeck.'}});
check(compact.get('wazeHint').textContent === 'Activez l’assistant RoadDeck.' && compact.get('wazeSetupStatus').textContent === 'Activez l’assistant RoadDeck.', 'Native split status appears on home and setup');
check(compact.get('wazeTools').hidden === false, 'Pending split controls stay available');
rd.receive({type:'diagnostics',data:{sdk:29,android:'10',accessibilityGranted:true,wazePanel:{mode:'split',active:true,requested:false,message:'Écran partagé actif.'}}});
const diagnostic = compact.get('diagnostics').textContent;
check(diagnostic.includes('Mode : écran partagé') && diagnostic.includes('Assistant d’écran partagé : activé'), 'Diagnostics use split and accessibility state');
check(diagnostic.includes('Panneau actif : oui') && diagnostic.includes('Écran partagé actif.'), 'Diagnostics preserve actual native status');
check(!/par-dessus|34 %|Largeur du panneau/.test(diagnostic), 'No stale overlay diagnostic');

const before = compact.calls.length;
compact.clickAction('testWaze');
check(compact.calls[before].type === 'saveSettings' && compact.calls[before].data.wazeMode === 'split', 'Launch saves split mode first');
check(!Object.prototype.hasOwnProperty.call(compact.calls[before].data,'overlayWidth'), 'No invented split ratio sent to Android');
check(!compact.calls.slice(before).some(c => c.type === 'closePanelSettings'), 'Activity stays visible until native split launch');
compact.flushTimeouts();
const launch = compact.calls[compact.calls.length-1];
check(launch.type === 'openWaze' && launch.data.automatic === false, 'Explicit launch is sent after settings');
check(!('left' in launch.data) && !('width' in launch.data), 'No screen crop rectangle sent as split geometry');
check(!compact.calls.some(c => c.type === 'overlay' || c.type === 'wazeLayout'), 'Split UI sends no obsolete overlay geometry commands');

const sidebar = load('overlay.js');
sidebar.clickAction('gate');
check(sidebar.calls[sidebar.calls.length-1].type === 'gate', 'Gate still begins with native confirmation request');
sidebar.context.RoadDeckOverlay.receive({type:'gateConfirm',data:{token:'one-use-test-token'}});
check(sidebar.get('confirm').hidden === false && !sidebar.calls.some(c => c.type === 'gateConfirm'), 'Showing gate confirmation never sends it');
sidebar.get('confirmGate').click();
check(sidebar.calls[sidebar.calls.length-1].type === 'gateConfirm' && sidebar.calls[sidebar.calls.length-1].data.token === 'one-use-test-token', 'Explicit confirmation returns only the supplied token');
const confirmationCount = sidebar.calls.filter(c => c.type === 'gateConfirm').length;
sidebar.get('confirmGate').click();
check(sidebar.calls.filter(c => c.type === 'gateConfirm').length === confirmationCount, 'Confirmation token cannot be reused by another click');
sidebar.context.RoadDeckOverlay.receive({type:'gateConfirm',data:{token:'cancel-test-token'}});
sidebar.get('cancelGate').click();
check(sidebar.calls[sidebar.calls.length-1].type === 'gateCancel' && sidebar.get('confirm').hidden === true, 'Cancel clears the confirmation');
sidebar.clickAction('collapse');
check(sidebar.calls[sidebar.calls.length-1].type === 'collapse', 'Fullscreen navigation keeps the existing native action');
sidebar.clickAction('stop');
check(sidebar.calls[sidebar.calls.length-1].type === 'stop', 'Sidebar close keeps the existing native action');

console.log('PASS: ' + checks + ' split/compact-settings assertions; DOM/Android bridge simulated, no browser or device used.');
