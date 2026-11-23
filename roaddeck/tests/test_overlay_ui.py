"""Execute the shipped overlay HTML/CSS/JS. Android bridge is mocked, no live accounts."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import mimetypes, re, base64
P=Path(__file__).resolve().parents[1]
A=P/'app/src/main/assets/ui'
ORIGIN='https://appassets.androidplatform.net/assets/ui/'
checks=0

def check(value,message):
    global checks
    checks+=1
    assert value,message

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    def page_for(w,h):
        page=browser.new_page(viewport={'width':w,'height':h},timezone_id='Europe/Paris')
        errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        # Inline the shipped assets for an offline DOM harness (no external navigation).
        # CSP is omitted ONLY in this test document, because it intentionally uses inline assets.
        # The APK retains its restrictive policy and locally intercepted HTTPS origin.
        page.evaluate('window.calls=[]; window.Native={post:s=>calls.push(JSON.parse(s))};')
        html=(A/'overlay.html').read_text()
        html=re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]*>','',html)
        html=html.replace('<link rel="stylesheet" href="overlay.css">','<style>'+(A/'overlay.css').read_text()+'</style>')
        js=(A/'overlay.js').read_text().replace("'cover.webp'",repr('data:image/webp;base64,'+base64.b64encode((A/'cover.webp').read_bytes()).decode()))
        html=html.replace('<script src="overlay.js"></script>','<script>'+js+'</script>')
        html=html.replace('src="cover.webp"','src="data:image/webp;base64,'+base64.b64encode((A/'cover.webp').read_bytes()).decode()+'"')
        page.set_content(html)
        return page,errors
    for width,height in [(348,600),(435,800),(653,1080),(307,407),(240,480),(200,360),(280,320)]:
        page,errors=page_for(width,height)
        check(page.evaluate('calls[0].type')=='ready','Initial native handshake')
        check(page.locator('.media').count()==1,'Only one media tile')
        check(not errors,f'No JS errors {width}x{height}')
        for selector in ['#settings','#musicTab','#radioTab','#playButton','#gateButton','.weather','#hideButton','#closeButton']:
            b=page.locator(selector).bounding_box()
            check(b is not None and b['x']>=-1 and b['y']>=-1 and b['x']+b['width']<=width+1 and b['y']+b['height']<=height+1,f'{selector} within {width}x{height}: {b}')
        page.locator('#radioTab').click()
        check(page.locator('#trackTitle').inner_text()=='Choisir une radio','No fabricated playback')
        page.locator('#musicTab').click()
        page.locator('#hideButton').click()
        check(page.evaluate('calls.at(-1).type')=='collapse','Collapse action is reachable')
        page.locator('#closeButton').click()
        check(page.evaluate('calls.at(-1).type')=='stop','Stop action is reachable')
        if width==348:
            page.screenshot(path=str(P/'docs/overlay-348x600.png'))
        page.close()
    page,errors=page_for(348,600)
    page.evaluate('RoadDeckOverlay.receive({type:"settings",data:{musicName:"Deezer",radioBuffer:30,weatherCity:"Poitiers"}})')
    check(page.locator('#trackTitle').inner_text()=='Ouvrir Deezer','Saved music provider')
    malicious='<img src=x onerror="window.injected=true">'
    page.evaluate('(title)=>RoadDeckOverlay.receive({type:"state",data:{music:{active:true,title,artist:"Test",playing:true,duration:100000,position:40000},radio:{}}})',malicious)
    check(page.locator('#trackTitle').inner_text()==malicious,'Untrusted metadata rendered as text')
    check(page.evaluate('window.injected===undefined'),'No metadata script execution')
    check(page.locator('#progress').get_attribute('style')=='width: 40%;','Actual media progress')
    page.locator('#playButton').click()
    check(page.evaluate('calls.at(-1).type')=='media','Music transport uses native media bridge')
    check(page.evaluate('calls.at(-1).data.action')=='toggle','Music play/pause request')
    page.locator('#radioTab').click()
    page.evaluate('RoadDeckOverlay.receive({type:"state",data:{music:{},radio:{url:"https://example.org/radio",name:"Test radio",wanted:true,buffered:12,initialBuffer:30,loading:true}}})')
    check(page.locator('#mediaStatus').inner_text()=='Tampon 12 / 30 s','Real buffer from native state')
    check(page.locator('#progress').get_attribute('style')=='width: 40%;','Buffer percentage')
    page.locator('#playButton').click()
    check(page.evaluate('calls.at(-1).type')=='radioControl','Radio transport separate from music')
    page.locator('[data-act="next"]').click()
    check(page.evaluate('calls.at(-1).type')=='radioStep','Next favourite radio action')
    page.evaluate('RoadDeckOverlay.receive({type:"favorites",data:[{name:"Station test",url:"https://example.org/audio.mp3"}]})')
    page.locator('#sources').click()
    page.locator('#stationList button').click()
    check(page.evaluate('calls.at(-1).type')=='radioPlay','Favourite selection')
    check(page.evaluate('calls.at(-1).data.url')=='https://example.org/audio.mp3','Correct favourite URL')
    check(not page.locator('#stations').is_visible(),'Favourite picker closes')
    page.locator('#gateButton').click()
    check(page.evaluate('calls.at(-1).type')=='gate','Gate button requests confirmation, not hardcoded cloud command')
    page.evaluate('RoadDeckOverlay.receive({type:"gateConfirm",data:{token:"test-confirmation-token"}})')
    check(page.locator('#confirm').is_visible(),'Gate confirmation shown')
    check(not page.evaluate('calls.some(x=>x.type==="gateConfirm")'),'Displaying confirmation never sends a gate command')
    page.locator('#cancelGate').click()
    check(page.evaluate('calls.at(-1).type')=='gateCancel','Cancel command')
    page.evaluate('RoadDeckOverlay.receive({type:"gateConfirm",data:{token:"token-2"}})')
    page.locator('#confirmGate').click()
    check(page.evaluate('calls.at(-1).type')=='gateConfirm','Explicit user confirmation sent')
    check(page.evaluate('calls.at(-1).data.token')=='token-2','Short-lived confirmation token returned')
    check(not page.locator('#confirm').is_visible(),'Confirmation closes after send')
    page.evaluate('RoadDeckOverlay.receive({type:"gateState",data:{busy:true,message:"Envoi"}})')
    check(page.locator('#gateButton').is_disabled(),'Gate disabled during send')
    page.evaluate('RoadDeckOverlay.receive({type:"gateState",data:{busy:false,message:"Commande acceptée, position non vérifiée."}})')
    check(page.locator('#message').inner_text()=='Commande acceptée, position non vérifiée.','No invented gate status')
    page.evaluate('RoadDeckOverlay.receive({type:"weather",data:{temperature:12,city:"Poitiers",updatedAt:Date.now()}})')
    check(page.locator('#temperature').inner_text()=='12 °C','Real supplied weather data')
    page.locator('#settings').click()
    check(page.evaluate('calls.at(-1).data.page')=='waze','Settings return to proper page')
    check(not errors,'No errors through complete overlay workflow')
    browser.close()
print(f'PASS: {checks} overlay UI assertions (Android bridge mocked)')
