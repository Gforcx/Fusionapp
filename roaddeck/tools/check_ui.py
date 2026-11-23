from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
html=(root/'APERCU.html').read_text()
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    page=browser.new_page(viewport={'width':1648,'height':928},device_scale_factor=1,timezone_id='Europe/Paris')
    errors=[]
    page.on('pageerror',lambda e: errors.append(str(e)))
    page.set_content(html);page.wait_for_timeout(400)
    page.screenshot(path=str(root/'docs/interface-initiale.png'))
    print('errors:',errors)
    print('stage:',page.locator('#stage').bounding_box())
    print('clock:',page.locator('#clock').inner_text())
    page.locator('.top-settings').click();page.screenshot(path=str(root/'docs/reglages.png'))
    print('settings open:',page.locator('#overlay').is_visible())
    browser.close()
