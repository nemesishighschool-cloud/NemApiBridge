# API Bridge

API Bridge transforme un téléphone Android en serveur HTTP local compatible avec l’API de conversation OpenAI. Il transmet les requêtes à une application d’IA installée sur le téléphone en observant son interface accessible et en agissant sur les éléments identifiés.

## Principe

L’application Android héberge le serveur, l’interface d’administration et un service Android d’accessibilité activé explicitement par l’utilisateur. Pour une requête, le service inspecte l’arbre d’interface de l’application cible, identifie le champ de saisie et les commandes nécessaires, puis les actionne par leurs propriétés sémantiques. Il lit ensuite le texte de la réponse depuis l’interface.

Le fonctionnement ne dépend pas de positions enregistrées à l’écran. Les requêtes d’un même appareil sont traitées une à la fois afin d’éviter les interactions concurrentes avec une interface unique. Le serveur et le traitement restent sur le téléphone; les clients du réseau local peuvent s’y connecter.

L’architecture détaillée, les contrats d’API, les sélecteurs, la sécurité, les limites et la feuille de route sont décrits dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Fonctionnalités

- API HTTP locale compatible OpenAI : `POST /v1/chat/completions`, `GET /v1/models`.
- Authentification Bearer; la clé est chiffrée avec Android Keystore et peut être renouvelée depuis l’application.
- Providers configurables par fichier JSON; les sélecteurs ne sont pas codés en dur dans l’exécuteur.
- Envoi, détection de fin et lecture de réponse via l’arbre Android d’accessibilité, sans coordonnées ni calibration.
- Inspection volontaire de l’interface cible depuis la fenêtre flottante, avec copie des sélecteurs JSON.
- Streaming SSE simulé après lecture complète de la réponse.
- Rejeu borné de l’historique texte et extraction des tool calls au format OpenAI.
- Dashboard web local avec authentification, état et statistiques agrégées.
- Une seule opération d’automatisation active et une en attente au maximum (15 s); la surcharge est signalée explicitement.

La compilation et les tests automatisés valident le code, mais l’accès réel à une application d’IA dépend des éléments qu’elle expose dans l’arbre d’accessibilité. Les sélecteurs de chaque application doivent être inspectés sur l’appareil et importés comme provider.

## Conditions Android

L’utilisateur doit installer l’application cible, se connecter lui-même et activer le service d’accessibilité API Bridge dans les réglages Android. Android ne permet pas à API Bridge d’activer ce service à la place de l’utilisateur. Les autorisations nécessaires au serveur ou à l’interface flottante doivent également être accordées explicitement.

## Providers JSON

L’application fournit un provider de démonstration à compléter : [`docs/provider-template.json`](docs/provider-template.json). Remplace le nom de package et les sélecteurs par ceux de l’application installée; la fenêtre flottante permet d’inspecter les éléments accessibles et de copier leurs sélecteurs. Le provider importé doit définir `input`, `send` et `assistant_message`; `generating` et `stop` sont facultatifs.

Les sélecteurs acceptés sont `resource_id`, `content_description`, `text` et `class_name`, avec les modes `exact`, `contains` ou `starts_with`. Les délais d’attente, intervalle de lecture et limite d’historique sont configurables dans le JSON. Une correspondance manquante ou ambiguë renvoie une erreur au lieu de choisir un élément au hasard.

## API

Après démarrage, copie la clé Bearer depuis l’application Android :

```bash
curl http://<IP_DU_TELEPHONE>:8080/v1/models \
  -H "Authorization: Bearer <CLE_API>"
```

```bash
curl http://<IP_DU_TELEPHONE>:8080/v1/chat/completions \
  -H "Authorization: Bearer <CLE_API>" \
  -H "Content-Type: application/json" \
  -d '{"model":"Example AI","messages":[{"role":"user","content":"Bonjour"}]}'
```

Le nom du provider importé est l’identifiant du modèle. Le serveur n’accepte que les connexions provenant d’une interface locale (loopback, réseau privé ou lien local) et exige la clé sur les endpoints de données et d’administration. `/health` ne révèle que l’état HTTP et l’uptime. Le dashboard `/` sert l’interface de connexion; ses données nécessitent également la clé.

| Méthode | Route | Fonction |
|---|---|---|
| `POST` | `/v1/chat/completions` | Conversation OpenAI, JSON ou SSE simulé |
| `GET` | `/v1/models` | Providers disponibles comme modèles |
| `GET` | `/health` | État HTTP minimal (sans clé) |
| `GET` | `/status`, `/stats` | Accessibilité, app cible et statistiques |
| `GET/POST` | `/config` | Provider actif et autorisation du streaming |
| `GET/POST` | `/ask` | Envoi simplifié avec le provider actif |
| `GET/POST` | `/stop` | Annulation et clic sur le sélecteur stop s’il existe |
| `POST` | `/upload` | Upload authentifié, taille maximale 20 Mo |
| `GET` | `/` | Tableau de bord web; saisir la clé dans Config |

Toutes les routes de données et de contrôle sauf `GET /health` nécessitent `Authorization: Bearer <CLE_API>`. `/` ne sert que la coquille du dashboard; ses données et actions sont authentifiées.

Le transport est HTTP sans TLS : utilise un réseau de confiance ou ADB reverse, et ne publie jamais le port du téléphone sur Internet. La clé protège l’accès, mais ne chiffre pas le trafic sur le réseau local.

## Développement

Le projet est une application Android Kotlin/Gradle. Pour compiler l’APK de débogage :

```bash
./gradlew assembleDebug
```

Le résultat est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

La CI GitHub exécute les tests unitaires et compile l’APK sur `main`, `master` et les pull requests. Aucun émulateur Android n’est requis pour le build.

Avant de modifier le comportement, lire [`AGENTS.md`](AGENTS.md) et la documentation d’architecture.
