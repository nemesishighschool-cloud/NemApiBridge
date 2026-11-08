#!/usr/bin/env python3
"""Live regression; opens a NEW DeepSeek chat and sends real messages.

Run only on a signed-in, unlocked Android device with DeepSeek in the foreground,
NemApiBridge running and its accessibility service enabled. The command changes
the current chat. Never run uiautomator during a request.
"""
import argparse
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8090')
parser.add_argument('--provider', default='DeepSeek')
parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
parser.add_argument('--long', action='store_true', help='also test a 20-line response')
args = parser.parse_args()

def adb(*command):
    return subprocess.check_output([args.adb, *command], text=True)

def new_chat():
    adb('shell', 'uiautomator', 'dump', '/sdcard/nem-regression-ui.xml')
    root = ET.fromstring(adb('shell', 'cat', '/sdcard/nem-regression-ui.xml'))
    matches = [n for n in root.iter('node') if n.get('content-desc') == 'New chat']
    if len(matches) != 1:
        raise RuntimeError('Impossible de trouver un bouton New chat unique sur DeepSeek.')
    bounds = list(map(int, re.findall(r'\d+', matches[0].get('bounds', ''))))
    if len(bounds) != 4:
        raise RuntimeError('Bornes invalides pour le bouton New chat.')
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    time.sleep(3)
    adb('shell', 'uiautomator', 'dump', '/sdcard/nem-regression-ui.xml')
    root = ET.fromstring(adb('shell', 'cat', '/sdcard/nem-regression-ui.xml'))
    if any(n.get('content-desc') == 'Copy' for n in root.iter('node')):
        raise RuntimeError('La conversation semble déjà contenir une réponse.')
    # uiautomator may temporarily rebind accessibility; wait before sending.
    time.sleep(2)
    adb('shell', 'rm', '-f', '/sdcard/nem-regression-ui.xml')

def chat(prompt):
    body = json.dumps({'model': args.provider, 'messages': [{'role': 'user', 'content': prompt}]}).encode()
    req = urllib.request.Request(args.url.rstrip('/') + '/web/chat', data=body, headers={'Content-Type':'application/json'})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=210) as response:
            result = json.load(response)['choices'][0]['message']['content']
    except urllib.error.HTTPError as error:
        info = json.load(error).get('error', {})
        raise RuntimeError('HTTP %s %s: %s' % (error.code, info.get('code'), info.get('message'))) from error
    return result.strip(), round(time.monotonic() - start, 1)

new_chat()
for i, token in enumerate(('NEMEMPTYTEST', 'NEMREPEATTEST', 'NEMREPEATTEST'), 1):
    answer, seconds = chat('Réponds uniquement : ' + token)
    if answer != token:
        raise RuntimeError('La réponse %d ne correspond pas au repère attendu.' % i)
    print('Requête %d : %s en %.1f s' % (i, token, seconds), flush=True)
if args.long:
    lines = [f'REPER{n:02d} : La description de contrôle numéro {n:02d} doit rester lisible et complète.' for n in range(1, 21)]
    prompt = 'Recopie exactement ces 20 lignes dans le même ordre, sans introduction ni conclusion, une ligne par repère :\n' + '\n'.join(lines)
    answer, seconds = chat(prompt)
    positions = [answer.find(line) for line in lines]
    if min(positions) < 0 or positions != sorted(positions):
        raise RuntimeError('Réponse longue incomplète, modifiée ou réordonnée.')
    print('Réponse longue : 20 lignes exactes dans l’ordre en %.1f s' % seconds, flush=True)
print('Régression DeepSeek réussie.')
