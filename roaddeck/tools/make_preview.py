from pathlib import Path
import base64
root=Path(__file__).resolve().parents[1]
ui=root/'app/src/main/assets/ui'
text=(ui/'index.html').read_text()
css=(ui/'style.css').read_text()
js=(ui/'app.js').read_text()
text=text.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
text=text.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>')
text=text.replace('<script src="app.js"></script>','<script>'+js+'</script>')
for name in ['shell.webp','cover.webp','waze.webp']:
    uri='data:image/webp;base64,'+base64.b64encode((ui/name).read_bytes()).decode()
    text=text.replace(name,uri)
(root/'APERCU.html').write_text(text)
print('APERCU.html:',len(text),'characters')
