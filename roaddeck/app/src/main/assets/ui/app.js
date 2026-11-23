'use strict';
(() => {
    const $ = id => document.getElementById(id);
    function replaceContents(parent, child) {
        while (parent.firstChild) parent.removeChild(parent.firstChild);
        if (child) parent.appendChild(child);
    }
    const native = !!(window.Native && typeof window.Native.post === 'function');
    const panelSettings = /(?:^|[?&])panelSettings=1(?:&|$)/.test(window.location ? window.location.search : '');
    const state = {
        tab: 'music', touchedTab: false, overlay: '', section: 'general', pickerReturn: null,
        settings: {musicPackage:'com.spotify.music',musicName:'Spotify',wazeMode:'split',wazeAuto:false,
            keepScreen:true,gateConfirm:true,radioBuffer:15,tuyaEndpoint:'https://openapi.tuyaeu.com',
            tuyaHome:'',tuyaScene:''},
        waze: {}, music: {}, radio: {}, favorites: [], weather: {}, radioRequest: 0, cityRequest: 0,
        initialized: false, toastTimer: 0
    };
    function send(type, data = {}) {
        if (!native) { notify('Aperçu uniquement. Cette commande nécessite l’application Android.'); return false; }
        window.Native.post(JSON.stringify({type, data})); return true;
    }
    function notify(message, error = false) {
        clearTimeout(state.toastTimer);
        $('toast').textContent = String(message || 'Une erreur est survenue.');
        $('toast').classList.toggle('error', error); $('toast').hidden = false;
        state.toastTimer = setTimeout(() => {$('toast').hidden = true;}, error ? 8500 : 4800);
    }
    function layout() {
        const stage = $('stage');
        stage.classList.toggle('panel-settings',panelSettings);
        if(panelSettings) {
            stage.style.transform='none';stage.style.left='0px';stage.style.top='0px';
            stage.style.width='100%';stage.style.height='100%';return;
        }
        const scale = Math.min(innerWidth / 1648, innerHeight / 928);
        stage.style.transform = `scale(${scale})`;
        stage.style.left = `${(innerWidth - 1648 * scale) / 2}px`;
        stage.style.top = `${(innerHeight - 928 * scale) / 2}px`;
    }
    function clock() {
        const now = new Date();
        $('clock').textContent = now.toLocaleTimeString('fr-FR', {hour:'2-digit', minute:'2-digit'});
        const d = now.toLocaleDateString('fr-FR', {weekday:'short', day:'numeric', month:'short', year:'numeric'});
        $('date').textContent = d.charAt(0).toUpperCase() + d.slice(1);
    }
    function setTab(tab, user = true) {
        state.tab = tab; if (user) state.touchedTab = true;
        for (const item of ['music', 'radio']) {
            $(item+'Tab').classList.toggle('selected', tab === item);
            $(item+'Tab').setAttribute('aria-selected', String(tab === item));
        }
        renderMedia();
    }
    function duration(ms) {
        if (!Number.isFinite(ms) || ms < 0) return '--:--';
        const n = Math.floor(ms / 1000); return `${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;
    }
    function renderMedia() {
        const radio = state.tab === 'radio', m = radio ? state.radio : state.music;
        $('radioCover').hidden = !radio;
        const image = !radio && m.art && /^data:image\/(jpeg|png|webp);base64,/.test(m.art) ? m.art : 'cover.webp';
        if ($('cover').getAttribute('src') !== image) $('cover').setAttribute('src', image);
        let title, artist, time, progress;
        if (radio) {
            title = m.name || 'Choisir une radio';
            const buffered = Math.max(0, Number(m.buffered) || 0);
            artist = m.track || (m.url ? m.status || 'Prête à écouter' : 'Radio Internet · recherche et favoris');
            time = m.loading ? `${buffered} / ${m.initialBuffer || state.settings.radioBuffer} s` : m.url ? `Réserve ${buffered} s` : 'Internet';
            progress = Math.min(1, buffered / (m.initialBuffer || state.settings.radioBuffer));
            $('timeline').title = 'Réserve audio réellement disponible';
        } else {
            title = m.active ? m.title || 'Lecture en cours' : `Ouvrir ${state.settings.musicName || 'le lecteur'}`;
            artist = m.active ? m.artist || m.app || '' : m.authorized === false ? 'Autoriser les commandes dans les réglages' : 'Votre musique dans l’application choisie';
            time = m.duration > 0 ? `${duration(m.position || 0)} / ${duration(m.duration)}` : '--:-- / --:--';
            progress = m.duration > 0 ? (m.position || 0) / m.duration : 0;
            $('timeline').title = m.seekable ? 'Changer la position' : 'Position de lecture';
        }
        $('trackTitle').textContent = title; $('trackArtist').textContent = artist;
        $('mediaTime').textContent = time;
        $('progress').style.width = `${Math.max(0,Math.min(1,progress || 0))*100}%`;
        const playing = radio ? m.wanted || m.playing : m.playing;
        $('playButton').querySelector('use').setAttribute('href', playing ? '#pause' : '#play');
        $('playButton').classList.toggle('loading', !!(radio && m.loading));
        $('playButton').setAttribute('aria-label', playing ? 'Pause' : 'Lecture');
        $('favoriteButton').classList.toggle('selected', !!(radio && state.favorites.some(f => f.url === m.url)));
        $('favoriteButton').setAttribute('aria-label', radio ? 'Ajouter ou retirer des favoris radio' : 'Gérer les favoris dans l’application musicale');
    }
    const descriptions = new Map([[0,'Ciel dégagé'],[1,'Peu nuageux'],[2,'Partiellement nuageux'],[3,'Couvert'],[45,'Brouillard'],[48,'Brouillard givrant'],[51,'Bruine faible'],[53,'Bruine'],[55,'Bruine dense'],[56,'Bruine verglaçante'],[57,'Bruine verglaçante'],[61,'Pluie faible'],[63,'Pluie'],[65,'Pluie forte'],[66,'Pluie verglaçante'],[67,'Pluie verglaçante'],[71,'Neige faible'],[73,'Neige'],[75,'Neige forte'],[77,'Grains de neige'],[80,'Averses'],[81,'Averses'],[82,'Fortes averses'],[85,'Averses de neige'],[86,'Fortes averses de neige'],[95,'Orage'],[96,'Orage avec grêle'],[99,'Orage avec grêle']]);
    function renderWeather() {
        const w = state.weather, hasData = typeof w.temperature === 'number';
        if (!hasData) {
            $('weatherTemp').textContent='-- °C';
            $('weatherDescription').textContent=w.error?'Météo indisponible':state.settings.weatherCity?'Actualisation…':'Choisir une ville';
            $('weatherRange').textContent='↑ -- °C   ↓ -- °C';
            $('weatherCity').textContent=state.settings.weatherCity||'Open-Meteo'; return;
        }
        $('weatherTemp').textContent=`${Math.round(w.temperature)} °C`;
        $('weatherDescription').textContent=descriptions.get(w.code)||'Conditions météo';
        $('weatherRange').textContent=`↑ ${Math.round(w.high)} °C   ↓ ${Math.round(w.low)} °C`;
        const stale=Date.now()-w.updatedAt>1800000 || w.error;
        const stamp=new Date(w.updatedAt).toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'});
        $('weatherCity').textContent=`${w.city || ''} · ${stale?'ancienne donnée · ':''}${stamp} · Open-Meteo`;
        const icon=w.code===0||w.code===1?'#sun':w.code>=51&&w.code<=67||w.code>=80?'#rain':'#cloud';
        $('weatherIcon').querySelector('use').setAttribute('href',icon);
    }
    function fillSettings() {
        const form=$('settingsForm');
        for(const input of form.querySelectorAll('[name]')) {
            if(input.name==='wazeMode') {input.value='split';continue;}
            if(input.name==='tuyaSecret') {input.value=''; input.placeholder=state.settings.hasSecret?'Secret enregistré — laisser vide pour conserver':'À renseigner uniquement sur l’appareil';continue;}
            if(input.type==='checkbox')input.checked=!!state.settings[input.name];
            else input.value=state.settings[input.name] === undefined?'':String(state.settings[input.name]);
        }
        $('selectedMusic').textContent=state.settings.musicName||state.settings.musicPackage||'Aucune';
    }
    function saveSettings(extra = {}) {
        const form=$('settingsForm'); if(!form.checkValidity()){form.reportValidity();return false;}
        const values={};
        for(const input of form.querySelectorAll('[name]')) {
            if(input.name==='tuyaSecret'&&!input.value.trim())continue;
            values[input.name]=input.type==='checkbox'?input.checked:input.type==='number'||input.name==='radioBuffer'?Number(input.value):input.value.trim();
        }
        Object.assign(values,extra);
        values.wazeMode='split';
        if(native)send('saveSettings',values);
        else {
            delete values.tuyaSecret; delete values.clearSecret;
            Object.assign(state.settings,values);fillSettings();renderMedia();renderWeather();
            $('saveStatus').textContent='Aperçu uniquement — rien transmis ni enregistré sur Android';
            notify('Réglages d’aperçu uniquement. Aucun compte n’a été connecté.');
        }
        return true;
    }
    function openSettings(section='general') {
        const wasSettings=state.overlay==='settings';state.overlay='settings';state.section=section;
        $('overlay').hidden=false;$('dialogTitle').textContent='Réglages';$('settingsLayout').hidden=false;$('picker').hidden=true;
        if(!wasSettings)fillSettings();
        for(const page of document.querySelectorAll('[data-page]'))page.hidden=page.dataset.page!==section;
        for(const btn of document.querySelectorAll('[data-section]'))btn.classList.toggle('active',btn.dataset.section===section);
        document.querySelector('.settings-content').scrollTop=0;
        if(section==='about'&&native)send('diagnostics');
    }
    function closeOverlay() {
        state.radioRequest++;state.cityRequest++;
        if(panelSettings) {
            if(state.overlay&&state.overlay!=='settings') {openSettings(state.pickerReturn||state.section||'general');return;}
            send('closePanelSettings');return;
        }
        $('overlay').hidden=true;state.overlay='';
    }
    function showPicker(title,mode) {
        state.overlay=mode;$('overlay').hidden=false;$('dialogTitle').textContent=title;
        $('settingsLayout').hidden=true;$('picker').hidden=false;
        $('radioSearchControls').hidden=mode!=='radio';$('manualRadioForm').hidden=true;
        replaceContents($('pickerResults'));
    }
    function empty(container,text) {const el=document.createElement('div');el.className='empty';el.textContent=text;replaceContents(container,el);}
    function resultButton(title,subtitle,callback) {
        const b=document.createElement('button');b.type='button';
        const a=document.createElement('span');a.textContent=title;
        const c=document.createElement('small');c.textContent=subtitle||'';
        b.append(a,c);b.addEventListener('click',callback);return b;
    }
    function chooseApps(purpose='open') {
        state.pickerReturn=state.overlay==='settings'?state.section:null;
        showPicker(purpose==='music'?'Choisir l’application musicale':'Applications installées','apps');
        empty($('pickerResults'),native?'Chargement des applications…':'Liste des applications disponible sur Android.');
        if(native)send('apps',{purpose});
    }
    function showApps(data) {
        if(state.overlay!=='apps')return;
        const list=$('pickerResults');replaceContents(list);
        for(const app of data.items||[])list.append(resultButton(app.name,app.package,()=>{
            if(data.purpose==='music') {
                const back=state.pickerReturn;
                Object.assign(state.settings,{musicPackage:app.package,musicName:app.name});
                saveSettings({musicPackage:app.package,musicName:app.name});
                if(back)openSettings(back);else closeOverlay();
                renderMedia();
            }else {send('openApp',{package:app.package});closeOverlay();}
        }));
        if(!list.children.length)empty(list,'Aucune application trouvée.');
    }
    function radioDirectory() {
        state.pickerReturn=state.overlay==='settings'?state.section:(panelSettings?'radio':null);
        showPicker('Radio Internet','radio');
        if(state.favorites.length)showStations(state.favorites,true);
        else empty($('pickerResults'),'Recherchez une station ou ajoutez son adresse de flux.');
    }
    function searchRadio() {
        const request=++state.radioRequest;
        empty($('pickerResults'),native?'Recherche dans Radio Browser…':'La recherche Internet fonctionne dans l’application Android.');
        if(native)send('radioSearch',{query:$('radioQuery').value,franceOnly:$('franceOnly').checked,request});
    }
    function showStations(stations,favorites=false) {
        const list=$('pickerResults');replaceContents(list);
        for(const station of stations) {
            const description=favorites?'Favori':`${station.codec||'Audio'}${station.bitrate?' · '+station.bitrate+' kb/s':''}${station.hls?' · HLS':''}`;
            list.append(resultButton(station.name,description,()=>{
                if(send('radioPlay',station)){setTab('radio');closeOverlay();}
            }));
        }
        if(!list.children.length)empty(list,favorites?'Aucun favori. Touchez le cœur pendant l’écoute d’une station.':'Aucune station trouvée. Essayez un autre nom ou désactivez le filtre France.');
    }
    function openWaze(automatic=false) {send('openWaze',{automatic:automatic===true});}
    function renderWaze() {
        $('wazeTools').hidden=!(state.waze.requested||state.waze.active);
        const message=state.waze.message||(state.waze.accessibilityGranted?'Assistant activé. Affichez Waze à gauche.':'Activez l’assistant d’écran partagé dans les réglages.');
        $('wazeHint').textContent=message;
        $('wazeSetupStatus').textContent=message;
        $('wazeStatus').textContent=state.waze.message||'';
    }
    function previousOrNext(direction) {
        if(state.tab==='music'){send('media',{action:direction>0?'next':'previous'});return;}
        const favorites=state.favorites;
        if(!favorites.length){notify('Ajoutez des radios aux favoris pour passer de l’une à l’autre.');return;}
        let i=favorites.findIndex(s=>s.url===state.radio.url);if(i<0)i=direction>0?-1:0;
        send('radioPlay',favorites[(i+direction+favorites.length)%favorites.length]);
    }
    function source() {if(state.tab==='music')send('openMusic');else radioDirectory();}
    const actions={
        settings:()=>openSettings(),weatherSettings:()=>openSettings('weather'),gateSettings:()=>openSettings('gate'),
        close:closeOverlay,waze:()=>openWaze(false),musicTab:()=>setTab('music'),radioTab:()=>setTab('radio'),
        mediaPanel:()=>{if(state.tab==='music')chooseApps('music');else radioDirectory();},
        sources:()=>state.tab==='music'?chooseApps('music'):radioDirectory(),
        apps:()=>chooseApps('open'),chooseMusic:()=>chooseApps('music'),openSource:source,
        previous:()=>previousOrNext(-1),next:()=>previousOrNext(1),
        play:()=>{if(state.tab==='music')send('media',{action:'toggle'});else if(state.radio.url)send('radioControl',{action:'toggle'});else radioDirectory();},
        favorite:()=>{if(state.tab==='radio'){if(state.radio.url)send('favorite',state.radio);else notify('Choisissez d’abord une radio.');}else{send('openMusic');}},
        gate:()=>send('gate'),radioDirectory,searchRadio,
        showFavorites:()=>showStations(state.favorites,true),
        manualRadio:()=>{$('manualRadioForm').hidden=!$('manualRadioForm').hidden;},
        playManual:()=>{
            const url=$('manualUrl').value.trim(),name=$('manualName').value.trim()||'Ma radio';
            try{const parsed=new URL(url);if(!['http:','https:'].includes(parsed.protocol)||parsed.username||parsed.password)throw new Error();}
            catch(e){notify('Saisissez une adresse de flux http(s), sans identifiants.',true);return;}
            if(send('radioPlay',{url,name,hls:/\.m3u8(?:\?|$)/i.test(url)})){setTab('radio');closeOverlay();}
        },
        searchCities:()=>{
            const query=$('cityQuery').value.trim();if(query.length<2){notify('Saisissez au moins deux caractères.');return;}
            empty($('cityResults'),native?'Recherche…':'La recherche de villes nécessite l’application Android.');
            if(native)send('cities',{query,request:++state.cityRequest});
        },
        testWaze:()=>{if(saveSettings()){if(!panelSettings)closeOverlay();setTimeout(()=>openWaze(false),100);}},
        tuyaTest:()=>{if(saveSettings()){empty($('tuyaScenes'),'Vérification de l’accès Tuya…');send('tuyaTest');}},
        clearSecret:()=>{send('saveSettings',{clearSecret:true});},
        weatherRefresh:()=>{if(saveSettings())send('weatherRefresh');}
    };
    for(const type of ['wazeSetup','wazeBack','wazeClose','appInfo','notificationAccess','permissions','notificationPermission','defaultLauncher','androidSettings','wifiSettings','bluetoothSettings','diagnostics','openMusic','openTuya'])actions[type]=()=>send(type);
    document.addEventListener('click',event=>{
        const section=event.target.closest('[data-section]');
        if(section){openSettings(section.dataset.section);return;}
        const button=event.target.closest('[data-act]');
        if(button && actions[button.dataset.act]){event.preventDefault();actions[button.dataset.act]();}
    });
    $('settingsForm').addEventListener('submit',event=>{event.preventDefault();saveSettings();});
    $('timeline').addEventListener('click',event=>{
        if(state.tab!=='music'||!state.music.seekable||!state.music.duration)return;
        const box=$('timeline').getBoundingClientRect();
        const fraction=Math.max(0,Math.min(1,(event.clientX-box.left)/box.width));
        send('media',{action:'seek',position:Math.round(fraction*state.music.duration)});
    });
    $('radioQuery').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();searchRadio();}});
    $('cityQuery').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();actions.searchCities();}});
    document.addEventListener('keydown',event=>{if(event.key==='Escape')closeOverlay();});
    window.RoadDeck={
        openWaze, openSettings, radioDirectory,
        back:()=>{if(state.overlay||panelSettings)closeOverlay();else openSettings();},
        receive:message=>{
            const data=message.data||{};
            switch(message.type){
                case 'settings':
                    state.settings={...state.settings,...data};fillSettings();renderMedia();renderWeather();
                    if(!state.initialized){state.initialized=true;if(native&&!panelSettings&&data.wazeAuto&&!data.suppressAutoWaze)setTimeout(()=>{if(!state.overlay)openWaze(true);},1300);}
                    break;
                case 'saved': $('settingsForm').elements.tuyaSecret.value='';$('saveStatus').textContent=data.message||'Enregistré';notify(data.message);break;
                case 'state':
                    state.music=data.music||{};state.radio=data.radio||{};
                    $('networkDot').classList.toggle('online',!!data.online);
                    $('networkDot').title=data.online?'Internet disponible':'Internet indisponible ou non vérifié';
                    if(state.radio.wanted&&!state.touchedTab)setTab('radio',false);else renderMedia();break;
                case 'favorites':state.favorites=Array.isArray(data)?data:[];renderMedia();break;
                case 'weather':state.weather=data;renderWeather();break;
                case 'notice':notify(data.message);break;
                case 'error':notify(data.message,true);break;
                case 'gateState':
                    $('gateButton').disabled=!!data.busy;$('gateButton').textContent=data.busy?'Envoi…':'Ouvrir';
                    $('gateStatus').textContent=data.message||'';
                    if(!data.busy)notify(data.message,!!data.error);break;
                case 'radioResults':
                    if(data.request!==state.radioRequest||state.overlay!=='radio')break;
                    if(data.error){empty($('pickerResults'),data.error);notify(data.error,true);}else showStations(data.stations||[]);break;
                case 'apps':showApps(data);break;
                case 'cities':
                    if(data.request!==state.cityRequest||state.overlay!=='settings')break;
                    replaceContents($('cityResults'));
                    for(const city of data.items||[]) {
                        const name=[city.name,city.admin1,city.country].filter(Boolean).join(', ');
                        $('cityResults').append(resultButton(name,'Choisir',()=>{
                            const values={weatherCity:city.name,weatherLat:String(city.latitude),weatherLon:String(city.longitude)};
                            for(const [k,v] of Object.entries(values))$('settingsForm').elements[k].value=v;
                            saveSettings(values);replaceContents($('cityResults'));send('weatherRefresh');
                        }));
                    }
                    if(!$('cityResults').children.length)empty($('cityResults'),'Aucune ville trouvée.');break;
                case 'tuyaScenes':
                    replaceContents($('tuyaScenes'));
                    for(const scene of Array.isArray(data)?data:[])$('tuyaScenes').append(resultButton(scene.name,scene.id,()=>{
                        $('settingsForm').elements.tuyaScene.value=scene.id;notify('Scène sélectionnée. Enregistrez les réglages pour la conserver.');
                    }));
                    if(!$('tuyaScenes').children.length)empty($('tuyaScenes'),'Connexion réussie, aucune scène disponible dans ce Home ID.');
                    else notify('Connexion Tuya réussie. Aucune commande de portail n’a été envoyée.');break;
                case 'wazeState': state.waze=data||{};renderWaze();break;
                case 'diagnostics':
                    const panel=data.wazePanel||{};
                    const accessibilityGranted=typeof data.accessibilityGranted==='boolean'?data.accessibilityGranted:!!panel.accessibilityGranted;
                    $('diagnostics').textContent=[
                        `${data.device||''} — Android ${data.android||'?'} (API ${data.sdk||'?'})`,
                        `Écran : ${data.width||'?'} × ${data.height||'?'}`,
                        `Waze installé : ${data.wazeInstalled?'oui':'non'}`,
                        `Mode : ${panel.mode==='split'?'écran partagé':panel.mode||'écran partagé'}`,
                        `Assistant d’écran partagé : ${accessibilityGranted?'activé':'non activé'}`,
                        `Panneau actif : ${panel.active?'oui':'non'}`,
                        `Demande en cours : ${panel.requested?'oui':'non'}`,
                        `État : ${panel.message||'aucune demande en cours'}`,
                        'Position et largeur : gérées par Android',
                        `Accès aux sessions musicales : ${data.musicAccess?'autorisé':'non autorisé'}`,
                        `Service musical connecté : ${data.musicListenerConnected?'oui':'non'}`,
                        `Tuya / Smart Life installé : ${data.tuyaInstalled?'oui':'non'}`,
                        `Internet : ${data.online?'disponible':'non vérifié'}`
                    ].join('\n');break;
            }
        }
    };
    addEventListener('resize',layout);layout();clock();setInterval(clock,1000);setInterval(renderWeather,60000);
    $('previewBadge').hidden=native;fillSettings();renderMedia();renderWeather();
    if(panelSettings)openSettings('general');
    if(native)send('ready');
})();
