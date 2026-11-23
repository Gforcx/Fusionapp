from pathlib import Path
import hashlib,hmac,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
def oracle(token,method,path):
    canonical=method+'\n'+hashlib.sha256(b'').hexdigest()+'\n\n'+path
    return hmac.new(b'secret-test',('client-test'+token+'1700000000000'+'nonce'+canonical).encode(),hashlib.sha256).hexdigest().upper()
with tempfile.TemporaryDirectory() as build:
    subprocess.run(['javac','-encoding','UTF-8','-d',build,str(root/'app/src/main/java/fr/roaddeck/launcher/Core.java'),str(root/'tests/CoreTest.java')],check=True)
    subprocess.run(['java','-cp',build,'fr.roaddeck.launcher.CoreTest',oracle('','GET','/v1.0/token?grant_type=1'),oracle('token-test','POST','/v1.0/homes/123/scenes/abc/trigger')],check=True)
