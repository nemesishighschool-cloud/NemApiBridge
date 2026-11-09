# NemApiBridge

Le client officiel NemApiBridge.

NemApiBridge transforme un **téléphone Android** en passerelle HTTP locale compatible avec l’API de conversation OpenAI. Le serveur et l’automatisation fonctionnent sur le téléphone ; votre ordinateur s’y connecte en Wi-Fi ou par USB. Une interface web offre un tableau de bord et un chat de test sans clé.

> **Réseau privé uniquement.** Par défaut, l’API, le tableau de bord et le chat web sont ouverts sans authentification. Même si vous activez la clé pour l’API, le tableau de bord et son chat restent ouverts et permettent d’utiliser l’automatisation. Tout appareil capable de joindre le téléphone peut donc s’en servir. Le trafic HTTP n’est pas chiffré : ne publiez jamais le serveur sur Internet et ne l’utilisez pas sur un réseau non fiable.

## Mise en route sur le téléphone

1. Installez l’APK de la [release GitHub](https://github.com/nemesishighschool-cloud/NemBridgeApi/releases), ainsi que l’application IA cible.
2. Connectez-vous vous-même dans cette application. Pour DeepSeek, utilisez la version Android et la langue mentionnées dans [`providers/README.md`](providers/README.md).
3. Ouvrez NemApiBridge → **Providers** → **Importer JSON**, puis choisissez `deepseek.json` ou un autre provider compatible. Son nom devient l’identifiant du modèle.
4. Dans **Réglages**, accordez volontairement l’accès d’accessibilité et la permission de superposition. Choisissez le port : **8090 par défaut** (un port déjà enregistré est conservé après mise à jour).
5. Sur l’accueil, appuyez sur **Démarrer et ouvrir**. L’application cible doit rester au premier plan pendant chaque requête ; déverrouillez l’écran et évitez d’interagir pendant l’automatisation.
6. Ouvrez le tableau de bord web sur votre PC, puis **Chat de test**. Choisissez un provider et envoyez un message. Si vous choisissez un autre provider, ouvrez son application sur le téléphone : la page web ne peut pas garantir sa mise au premier plan.

Aucun point fixe ni calibration d’écran n’est enregistré. Certains providers autorisent explicitement un geste de secours calculé à partir des limites courantes d’un nœud accessible unique et visible.

## Connecter le PC

### Wi-Fi local

Mettez les deux appareils sur le même réseau Wi-Fi. Dans les réglages NemApiBridge, relevez l’IPv4 locale du téléphone ; remplacez `IP_DU_TELEPHONE` ci-dessous :

- Tableau de bord et chat : `http://IP_DU_TELEPHONE:8090/`
- URL de base compatible OpenAI : `http://IP_DU_TELEPHONE:8090/v1`

Les réseaux isolant les clients Wi-Fi, les VPN et certains pare-feu peuvent bloquer la connexion. L’IP peut changer ; utilisez celle affichée par l’application. Le serveur écoute en IPv4 et limite les connexions à loopback, aux réseaux privés et aux adresses lien local.

### USB avec ADB

Installez [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) sur le PC, activez les options développeur et le débogage USB sur le téléphone, puis acceptez la demande de confiance sur le téléphone. Dans un terminal **sur le PC** :

```bash
adb devices
adb forward tcp:8090 tcp:8090
```

Si plusieurs appareils sont connectés, précisez `adb -s NUMERO_DE_SERIE forward tcp:8090 tcp:8090`. Adaptez les deux numéros si le port Android est modifié. Sur le PC :

- Tableau de bord et chat : <http://127.0.0.1:8090/>
- URL de base compatible OpenAI : <http://127.0.0.1:8090/v1>

Pour retirer la redirection : `adb forward --remove tcp:8090`. Il s’agit de **forward**, pas `adb reverse`. La redirection doit être refaite si nécessaire après déconnexion ou redémarrage d’ADB.

> Les adresses `127.0.0.1` désignent l’appareil sur lequel elles sont utilisées ; sans `adb forward`, le localhost du PC n’est pas celui du téléphone. Sur un émulateur, l’IPv4 affichée est celle de son réseau virtuel, pas nécessairement joignable directement depuis le PC : utilisez la redirection ADB.

## Interface web

La racine `/` ou `/admin` affiche l’état du serveur, l’accessibilité, l’application au premier plan, le provider actif et les statistiques depuis le dernier démarrage. On peut choisir le provider par défaut, activer ou désactiver le streaming simulé et demander l’arrêt d’une génération si le provider le permet.

La page `/chat` permet de choisir un provider importé et de converser sans clé. Les messages de la page restent dans l’onglet jusqu’à son rechargement ou leur effacement ; ils sont transmis à l’application IA choisie. Les réponses ne sont pas générées par NemApiBridge lui-même.

Le port et l’option **Exiger une clé pour l’API** sont réglés sur Android. Le chat web reste sans clé dans tous les cas : l’option ne sécurise donc pas le téléphone contre l’utilisation de l’interface web par d’autres appareils du réseau.

## API

Le nom du provider est l’identifiant du modèle. Par défaut, aucune clé n’est nécessaire :

```bash
curl http://127.0.0.1:8090/v1/models
curl http://127.0.0.1:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"DeepSeek","messages":[{"role":"user","content":"Bonjour"}]}'
```

Les exemples supposent une redirection USB. Pour le Wi-Fi, remplacez `127.0.0.1` par l’IP du téléphone. Certains clients OpenAI exigent une valeur non vide pour leur champ clé même si le serveur ne la vérifie pas : une valeur quelconque suffit lorsque la protection est désactivée.

Pour activer le contrôle Bearer, utilisez **Réglages → Clé API → Exiger une clé pour l’API** ; copiez alors la clé depuis le téléphone et ajoutez `Authorization: Bearer <CLE_API>` à vos requêtes. La clé est chiffrée avec Android Keystore et peut être renouvelée ; le changement s’applique sans redémarrer le serveur. Le chat et l’administration web restent volontairement sans clé.

| Méthode | Route | Fonction |
|---|---|---|
| `GET` | `/v1/models` | Providers disponibles comme modèles |
| `POST` | `/v1/chat/completions` | Conversation OpenAI, JSON ou SSE simulé |
| `GET` | `/health` | Disponibilité minimale, toujours ouverte |
| `GET` | `/status`, `/stats` | Accessibilité, application cible et statistiques |
| `GET/POST` | `/config` | Provider actif et autorisation du streaming |
| `GET/POST` | `/ask`, `/stop` | Question simplifiée et demande d’arrêt |
| `POST` | `/upload` | Upload, taille maximale 20 Mo |
| `GET` | `/`, `/admin`, `/chat` | Tableau de bord et chat navigateur, sans clé |
| `GET/POST` | `/web/*` | Routes utilisées par l’interface web, sans clé |

Les routes API hors `/health` nécessitent la clé uniquement si l’option Android est activée. Les routes `/web/*` sont toujours ouvertes aux clients locaux.

## Comportement et limites

- Une opération d’automatisation à la fois et une en attente au maximum. Les erreurs de cible, de sélecteur, de délai ou de surcharge sont explicites.
- Le streaming SSE est **simulé** : la réponse complète est d’abord lue dans l’application, puis découpée en événements SSE ; ce n’est pas un flux de tokens natif.
- Le provider DeepSeek fourni a été testé avec DeepSeek 2.7.5 en anglais sur émulateur Android 13, y compris des réponses longues et le chat web. Autres versions, langues et téléphones physiques restent à valider. Voir [`providers/README.md`](providers/README.md).
- Le service d’accessibilité, la connexion au compte et les permissions sont activés par l’utilisateur. Les restrictions Android, la mise en veille et les optimisations batterie peuvent interrompre le fonctionnement.
- Le provider DeepSeek v1.0.2 peut copier la réponse entière dans le presse-papiers Android ; l’ancien contenu est remplacé et la réponse y reste accessible. Des applications en arrière-plan peuvent lire le presse-papiers sur Android avant 10. La copie et la fidélité dépendent de DeepSeek et d’Android ; si la copie ne peut pas être vérifiée, une erreur est renvoyée. Arrêtez le serveur, puis réimportez le JSON de la v1.0.2 sous le nom `DeepSeek` pour activer ce mode.

## Développement et signature

```bash
./gradlew testDebugUnitTest assembleDebug
```

Un test navigateur facultatif se trouve dans `tests/web-e2e.cjs`. Après installation de Playwright et Chromium dans un répertoire temporaire (par exemple `cd /tmp/nem-browser-test && npm install playwright && npx playwright install --with-deps chromium`), lancez depuis le dépôt :

```bash
NODE_PATH=/tmp/nem-browser-test/node_modules LIVE_CHAT=1 node tests/web-e2e.cjs
```

Le serveur doit être joignable sur `127.0.0.1:8090`, le provider DeepSeek configuré, et son application ouverte au premier plan. `LIVE_CHAT=1` envoie une vraie question ; sans cette variable, seuls l’interface et la navigation sont testées. `NEM_WEB_URL` et `NEM_PROVIDER` permettent de choisir une autre URL ou un autre provider.

Pour une régression DeepSeek plus approfondie sur un appareil connecté à ADB : `python3 tests/deepseek-regression.py --long`. Le script ouvre une **nouvelle conversation**, puis envoie plusieurs vraies requêtes et vérifie une réponse longue. Ne l’exécutez pas si vous voulez conserver l’écran de conversation courant ; vos conversations enregistrées ne sont pas supprimées. `--adb`, `--url` et `--provider` adaptent le chemin ADB et le serveur.

L’APK debug se trouve dans `app/build/outputs/apk/debug/`. Pour compiler un APK release signé avec votre clé privée, définissez `NEM_SIGNING_STORE_FILE` (chemin du fichier PKCS12, alias `nemapibridge`) et `NEM_SIGNING_PASSWORD` dans l’environnement, puis lancez `./gradlew assembleRelease`. Sans ces variables, le build release est non signé. À la demande explicite du propriétaire, le keystore `signing/nemapibridge-release.p12` et son mot de passe `signing/password.txt` sont présents dans ce dépôt public. **Cette clé est donc publique et ne protège pas contre les APK malveillants signés par des tiers ; rendre le dépôt privé ensuite ne révoque pas les copies déjà faites.** Pour continuer à mettre à jour les installations existantes sans perdre leurs données, il faut conserver le même certificat. Pour retrouver une signature privée, il faudrait adopter une nouvelle clé et, sans mécanisme de rotation prévu, désinstaller l’ancienne application avant d’installer la nouvelle.

Voir [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) et [`AGENTS.md`](AGENTS.md) pour les contrats et contraintes du projet.


Pour compiler avec la clé publiée volontairement dans ce dépôt :

```bash
export NEM_SIGNING_STORE_FILE="$(pwd)/signing/nemapibridge-release.p12"
export NEM_SIGNING_PASSWORD="$(cat signing/password.txt)"
./gradlew assembleRelease
```
