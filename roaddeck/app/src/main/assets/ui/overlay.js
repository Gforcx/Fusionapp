'use strict';
(() => {
    const $=id=>document.getElementById(id);
    function setText(id,value){const node=$(id),text=String(value);if(node.textContent!==text)node.textContent=text;}
    function replaceContents(parent,child){
        while(parent.firstChild)parent.removeChild(parent.firstChild);
        if(child)parent.appendChild(child);
    }
    const state={tab:'music',touched:false,music:{},radio:{},favorites:[],settings:{musicName:'Spotify',radioBuffer:15,splitFrame:true},weather:{},gateToken:'',timer:0,confirmTimer:0};
    const send=(type,data={})=>{if(window.Native&&typeof window.Native.post==='function')window.Native.post(JSON.stringify({type,data}));};
    function toast(text,error=false){clearTimeout(state.timer);$('message').textContent=String(text||'');$('message').classList.toggle('error',error);$('message').hidden=false;state.timer=setTimeout(()=>$('message').hidden=true,6500);}
    function clock(){const now=new Date();setText('clock',now.toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'}));setText('date',now.toLocaleDateString('fr-FR',{weekday:'short',day:'numeric',month:'short'}));}
    function duration(ms){const s=Math.floor(Math.max(0,Number(ms)||0)/1000);return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`;}
    function tab(value,user=true){state.tab=value;if(user)state.touched=true;render();}
    function render(){
        const radio=state.tab==='radio',m=radio?state.radio:state.music;
        $('musicTab').setAttribute('aria-selected',String(!radio));$('radioTab').setAttribute('aria-selected',String(radio));
        $('radioArt').hidden=!radio;
        const cover=!radio&&/^data:image\/(jpeg|png|webp);base64,/.test(m.art||'')?m.art:'cover.webp';
        if($('cover').getAttribute('src')!==cover)$('cover').setAttribute('src',cover);
        $('trackTitle').textContent=radio?m.name||'Choisir une radio':m.active?m.title||'Votre musique':`Ouvrir ${state.settings.musicName||'le lecteur'}`;
        $('trackArtist').textContent=radio?m.track||m.status||'Radio Internet':m.active?m.artist||m.app||'':m.authorized===false?'Autoriser les commandes dans Réglages':'Votre application musicale';
        const playing=radio?m.wanted||m.playing:m.playing;
        $('playButton').querySelector('use').setAttribute('href',playing?'#pause':'#play');$('playButton').setAttribute('aria-label',playing?'Pause':'Lecture');
        const buffer=Math.max(0,Number(m.buffered)||0),total=Number(m.initialBuffer)||state.settings.radioBuffer;
        const fraction=radio?buffer/total:m.duration>0?(m.position||0)/m.duration:0;
        $('progress').style.width=`${Math.max(0,Math.min(1,fraction||0))*100}%`;
        $('mediaStatus').textContent=radio?m.url?(m.loading?`Tampon ${buffer} / ${total} s`:`Réserve ${buffer} s`):'Internet':m.duration>0?`${duration(m.position)} / ${duration(m.duration)}`:'Session Android';
    }
    function weather(){
        const w=state.weather,valid=Number.isFinite(w.temperature);
        $('temperature').textContent=valid?`${Math.round(w.temperature)} °C`:'-- °C';
        $('weatherText').textContent=w.city||state.settings.weatherCity||'Choisir une ville';
        const stamp=valid?new Date(w.updatedAt).toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'}):'';
        const stale=valid&&(Date.now()-w.updatedAt>1800000||w.error);
        $('weatherStamp').textContent=valid?`${stale?'Ancienne donnée · ':''}${stamp} · Open-Meteo`:'Open-Meteo';
    }
    function cancelConfirm(notify=true){clearTimeout(state.confirmTimer);state.gateToken='';$('confirm').hidden=true;if(notify)send('gateCancel');}
    function stations(){
        send('favorites');const list=$('stationList');replaceContents(list);
        for(const item of state.favorites){const b=document.createElement('button');b.textContent=item.name||'Radio';b.addEventListener('click',()=>{send('radioPlay',item);$('stations').hidden=true;tab('radio');});list.append(b);}
        if(!list.children.length){const p=document.createElement('p');p.textContent='Ajoutez vos radios favorites dans les réglages.';list.append(p);}
        $('stations').hidden=false;
    }
    const actions={
        music:()=>tab('music'),radio:()=>tab('radio'),
        source:()=>state.tab==='radio'?stations():send('openMusic'),
        previous:()=>send(state.tab==='radio'?'radioStep':'media',state.tab==='radio'?{direction:-1}:{action:'previous'}),
        next:()=>send(state.tab==='radio'?'radioStep':'media',state.tab==='radio'?{direction:1}:{action:'next'}),
        play:()=>send(state.tab==='radio'?'radioControl':'media',{action:'toggle'}),
        gate:()=>send('gate'),weather:()=>send('settings',{page:'weather'}),
        settings:()=>send('settings',{page:'waze'}),
        collapse:()=>{cancelConfirm(false);$('stations').hidden=true;send('collapse');},
        systemBars:()=>send('systemBars'),stop:()=>send('stop')
    };
    document.addEventListener('click',e=>{const b=e.target.closest('[data-act]');if(b&&actions[b.dataset.act]){e.preventDefault();actions[b.dataset.act]();}});
    $('cancelGate').addEventListener('click',()=>cancelConfirm());
    $('confirmGate').addEventListener('click',()=>{const token=state.gateToken;cancelConfirm(false);if(token)send('gateConfirm',{token});});
    $('closeStations').addEventListener('click',()=>$('stations').hidden=true);
    $('searchRadio').addEventListener('click',()=>send('settings',{page:'radioDirectory'}));
    window.RoadDeckOverlay={receive:message=>{
        const d=message.data||{};
        switch(message.type){
            case 'settings':Object.assign(state.settings,d);document.body.classList.toggle('unified-frame',state.settings.splitFrame!==false);render();weather();break;
            case 'wazeState':
                const frameText=typeof d.frameStatus==='string'?d.frameStatus.trim():'';
                const emptyCurrentFrame=d.frameGeometryAuthoritative===true&&typeof d.frameDrawnRevision==='number'
                    &&d.frameDrawnRevision>=0&&d.frameDrawnRevision===d.frameRevision&&d.frameNavigationPaintedPixels===0;
                const blockedFrame=d.decorationObstructed===true||d.framePlacementFallback===true||emptyCurrentFrame;
                $('panelFrameNotice').hidden=!(d.active===true&&d.frameEnabled===true&&d.frameVisible===false&&blockedFrame&&frameText);
                setText('panelFrameStatus',frameText);$('panelFrameStatus').setAttribute('title',frameText);break;
            case 'state':state.music=d.music||{};state.radio=d.radio||{};if(state.radio.wanted&&!state.touched)state.tab='radio';render();break;
            case 'favorites':state.favorites=Array.isArray(d)?d:[];break;
            case 'weather':state.weather=d;weather();break;
            case 'notice':toast(d.message);break;
            case 'error':toast(d.message,true);break;
            case 'gateConfirm':cancelConfirm(false);state.gateToken=d.token||'';$('confirm').hidden=false;state.confirmTimer=setTimeout(()=>cancelConfirm(),15000);break;
            case 'cancelConfirm':cancelConfirm(false);break;
            case 'gateState':$('gateButton').disabled=!!d.busy;$('gateButton').textContent=d.busy?'Envoi…':'Ouvrir';if(!d.busy)toast(d.message,!!d.error);break;
        }
    }};
    clock();setInterval(clock,1000);setInterval(weather,60000);render();weather();send('ready');
})();
