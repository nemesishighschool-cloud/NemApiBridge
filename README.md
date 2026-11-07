# API Bridge

API Bridge transforme un téléphone Android en serveur HTTP local compatible avec l’API de conversation OpenAI. Il transmet les requêtes à une application d’IA installée sur le téléphone en observant son interface accessible et en agissant sur les éléments identifiés.

## Principe

L’application Android héberge le serveur, l’interface d’administration et un service Android d’accessibilité activé explicitement par l’utilisateur. Pour une requête, le service inspecte l’arbre d’interface de l’application cible, identifie le champ de saisie et les commandes nécessaires, puis les actionne par leurs propriétés sémantiques. Il lit ensuite le texte de la réponse depuis l’interface.

Le fonctionnement ne dépend pas de positions enregistrées à l’écran. Les requêtes d’un même appareil sont traitées une à la fois afin d’éviter les interactions concurrentes avec une interface unique. Le serveur et le traitement restent sur le téléphone; les clients du réseau local peuvent s’y connecter.

L’architecture détaillée, les contrats d’API, les sélecteurs, la sécurité, les limites et la feuille de route sont décrits dans [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Capacités prévues

- `POST /v1/chat/completions` et `GET /v1/models`, avec réponses JSON compatibles OpenAI.
- Streaming SSE simulé après lecture de la réponse complète dans l’application.
- Rejeu du contexte de conversation fourni par le client.
- Interprétation des tool calls émis dans la réponse, dans les limites de l’application cible.
- Interface web locale de configuration et d’état, statistiques, arrêt d’une génération et traitement de fichiers selon le contrat documenté.
- Adaptateurs JSON pour décrire les sélecteurs et les états d’interface par application.

Ces capacités décrivent la version cible. Leur présence dans ce document ne signifie pas que la nouvelle automatisation par arbre d’interface est déjà implémentée ou validée.

## Conditions Android

L’utilisateur doit installer l’application cible, se connecter lui-même et activer le service d’accessibilité API Bridge dans les réglages Android. Android ne permet pas à API Bridge d’activer ce service à la place de l’utilisateur. Les autorisations nécessaires au serveur ou à l’interface flottante doivent également être accordées explicitement.

## Développement

Le projet est une application Android Kotlin/Gradle. Pour compiler l’APK de débogage :

```bash
./gradlew assembleDebug
```

Le résultat est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

Avant de modifier le comportement, lire [`AGENTS.md`](AGENTS.md) et la documentation d’architecture.
