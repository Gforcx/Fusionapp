"""Offline UI checks. The Android bridge is mocked; no account, stream or gate is contacted."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import json
root=Path(__file__).resolve().parents[1]
html=(root/'APERCU.html').read_text()
assertions=0

def check(value,message):
    global assertions
    assertions+=1
    assert value,message

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    for w,h in [(1648,928),(1280,800),(1024,600),(1920,1080),(904,407)]:
        page=browser.new_page(viewport={'width':w,'height':h},timezone_id='Europe/Paris')
        errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.set_content(html)
        check(not errors,f'No JavaScript errors at {w}x{h}')
        box=page.locator('#stage').bounding_box()
        check(box['x']>=-0.1 and box['y']>=-0.1 and box['x']+box['width']<=w+0.1 and box['y']+box['height']<=h+0.1,'Viewport fit')
        check(page.locator('.media').count()==1,'Exactly one audio panel')
        check(page.locator('#previewBadge').is_visible(),'Web preview clearly labelled')
        page.locator('#radioTab').click()
        check(page.locator('#radioTab').get_attribute('aria-selected')=='true','Radio tab selected')
        check(page.locator('#trackTitle').inner_text()=='Choisir une radio','No fake radio playback')
        page.locator('#musicTab').click()
        check(page.locator('#musicTab').get_attribute('aria-selected')=='true','Music tab selected')
        page.locator('.top-settings').click()
        for section in ['general','waze','music','radio','gate','weather','about']:
            page.locator(f'[data-section="{section}"]').click()
            check(page.locator(f'[data-page="{section}"]').is_visible(),f'Settings category: {section}')
        page.locator('[data-act="close"]').click()
        check(not page.locator('#overlay').is_visible(),'Close settings')
        if w==1280:page.screenshot(path=str(root/'docs/interface-1280x800.png'))
        page.close()
    page=browser.new_page(viewport={'width':1280,'height':800},timezone_id='Europe/Paris')
    # Browser executes the same JS with an explicit mock that records bridge commands.
    page.evaluate("window.calls=[];window.Native={post:s=>window.calls.push(JSON.parse(s))};")
    errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    page.set_content(html)
    check(page.evaluate('calls.length')==1,'Exactly one initial bridge call')
    check(page.evaluate('calls[0].type')=='ready','Initial ready handshake')
    check(not page.locator('#previewBadge').is_visible(),'Native mode does not display web-only badge')
    page.locator('#launchWaze').click()
    command=page.evaluate('calls[calls.length-1]')
    check(command['type']=='openWaze','Native Waze request')
    d=command['data']
    check(d['left']>=0 and d['top']>=0 and d['left']+d['width']<=1280 and d['top']+d['height']<=800,'Waze rectangle inside screen')
    hostile='<img src=x onerror="window.__injected=1">'
    data={'music':{'active':True,'authorized':True,'playing':False,'title':hostile,'artist':'Artiste de test','duration':100000,'position':50000,'seekable':True},'radio':{},'online':True}
    page.evaluate('(d)=>RoadDeck.receive({type:"state",data:d})',data)
    check(page.locator('#trackTitle').inner_text()==hostile,'Metadata inserted as text, not HTML')
    check(page.evaluate('window.__injected===undefined'),'Metadata script did not execute')
    check(page.locator('#progress').get_attribute('style')=='width: 50%;','50 percent actual media position')
    page.locator('#playButton').click()
    check(page.evaluate('calls[calls.length-1].type')=='media','Music play routes to session bridge')
    check(page.evaluate('calls[calls.length-1].data.action')=='toggle','Music play/pause command')
    page.locator('.top-settings').click();page.locator('[data-section="radio"]').click()
    page.locator('[name="radioBuffer"]').select_option('60')
    page.locator('button[type="submit"]').click()
    command=page.evaluate('calls[calls.length-1]')
    check(command['type']=='saveSettings' and command['data']['radioBuffer']==60,'Radio buffer saved as numeric seconds')
    check('tuyaSecret' not in command['data'],'Blank secret does not overwrite stored secret')
    page.locator('[data-section="gate"]').click()
    page.locator('[data-act="tuyaTest"]').click()
    calls=page.evaluate('calls')
    check(calls[-1]['type']=='tuyaTest' and calls[-2]['type']=='saveSettings','Tuya test follows settings save')
    check(not any(c['type']=='gate' for c in calls),'Tuya configuration/testing never commands the gate')
    page.evaluate('RoadDeck.receive({type:"tuyaScenes",data:[{id:"test-scene",name:"Scène test"}]})')
    page.locator('#tuyaScenes button').click()
    check(page.locator('[name="tuyaScene"]').input_value()=='test-scene','Scene selection updates configuration')
    check(not any(c['type']=='gate' for c in page.evaluate('calls')),'Scene selection does not trigger gate')
    page.locator('[data-act="close"]').click();page.locator('#gateButton').click()
    check(page.evaluate('calls[calls.length-1].type')=='gate','Only gate button requests gate command')
    page.evaluate('RoadDeck.receive({type:"gateState",data:{busy:true,message:"Envoi de test"}})')
    check(page.locator('#gateButton').is_disabled(),'Gate button disabled while command is pending')
    page.evaluate('RoadDeck.receive({type:"gateState",data:{busy:false,message:"Commande acceptée. Position inconnue."}})')
    check(not page.locator('#gateButton').is_disabled(),'Gate button re-enabled after response')
    page.evaluate('document.getElementById("toast").hidden=true')
    page.locator('#radioTab').click();page.locator('#coverButton').click()
    page.locator('#radioQuery').fill('FIP');page.locator('[data-act="searchRadio"]').click()
    command=page.evaluate('calls[calls.length-1]')
    check(command['type']=='radioSearch' and command['data']['query']=='FIP','Radio search routed to directory')
    request=command['data']['request']
    page.evaluate('(n)=>RoadDeck.receive({type:"radioResults",data:{request:n,stations:[{name:"Station de test",url:"https://example.org/live.mp3",codec:"MP3",bitrate:128}]}})',request)
    page.locator('#pickerResults button').click()
    command=page.evaluate('calls[calls.length-1]')
    check(command['type']=='radioPlay' and command['data']['url']=='https://example.org/live.mp3','Selecting station requests native radio playback')
    check(not page.locator('#overlay').is_visible(),'Radio picker closes after selection')
    page.evaluate('RoadDeck.receive({type:"state",data:{music:{},radio:{name:"Station de test",url:"https://example.org/live.mp3",playing:true,wanted:true,buffered:18,initialBuffer:30,status:"En lecture"},online:true}})')
    check(page.locator('#mediaTime').inner_text()=='Réserve 18 s','Displayed buffer comes from native player')
    check(page.locator('#progress').get_attribute('style')=='width: 60%;','Buffer bar reflects actual reserve')
    page.locator('#favoriteButton').click()
    check(page.evaluate('calls[calls.length-1].type')=='favorite','Favorite button routes to local favorites')
    check(not errors,'No JavaScript errors in native-bridge test flow')
    browser.close()
print(f'PASS: {assertions} offline UI assertions')
