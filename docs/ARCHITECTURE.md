# API Bridge — Architecture cible

**Version :** 3.0
**Statut :** spécification de la prochaine version
**Cible initiale :** application Android DeepSeek
**Plateforme d’exécution :** téléphone Android standard, non modifié

## 1. Objectif

API Bridge expose une application Android d’IA sous la forme d’un serveur HTTP compatible avec les requêtes de conversation OpenAI. Le serveur, l’automatisation et la lecture de la réponse s’exécutent sur le téléphone où l’application cible est installée.

Le pilotage repose sur l’inspection de l’arbre d’accessibilité Android et sur les actions sémantiques des éléments trouvés. L’automatisation repère le champ de saisie, les boutons et le contenu de réponse à partir de leurs identifiants de ressource, descriptions, libellés, classes et relations dans l’arbre. Elle ne requiert ni coordonnées enregistrées ni calibration visuelle.

La version initiale vise DeepSeek. Le format d’adaptateur permet d’ajouter d’autres applications sans coder leurs sélecteurs directement dans le serveur.

## 2. Principes et contraintes

| Sujet | Décision |
|---|---|
| Exécution | Sur le téléphone Android de l’utilisateur |
| Interface cible | Application installée normalement et ouverte sur le téléphone |
| Pilotage | `AccessibilityService`, arbre d’interface et actions sur les nœuds |
| Position à l’écran | Pas de coordonnées configurées ou calibrées |
| Intervention utilisateur | Activation du service d’accessibilité et connexion à l’application par l’utilisateur |
| Serveur | HTTP local compatible OpenAI |
| Accès réseau | Réseau local uniquement, avec authentification Bearer |
| Charge UI | Une opération à la fois par appareil |
| Streaming | SSE simulé après lecture de la réponse complète |
| Adaptation | Configuration JSON par application, DeepSeek en premier |
| Dépendance | Pas d’accès aux méthodes privées ou interfaces non documentées de l’application cible |

Un service d’accessibilité Android doit être activé volontairement par l’utilisateur dans les réglages du système. API Bridge ne tente ni d’activer ce service sans consentement, ni de contourner une authentification, un contrôle de compte ou un défi de sécurité.

## 3. Vue d’ensemble

```text
Client compatible OpenAI
        │ HTTP + Authorization: Bearer
        ▼
API Bridge sur le téléphone
┌───────────────────────────────────────────────┐
│ Serveur HTTP et interface d’administration    │
│ Authentification, endpoints, SSE, statistiques│
├───────────────────────────────────────────────┤
│ Couche de requête                             │
│ Validation, historique, file d’attente unique │
├───────────────────────────────────────────────┤
│ Registre d’adaptateurs + exécuteur UI         │
│ Sélecteurs sémantiques, attentes, lecture     │
├───────────────────────────────────────────────┤
│ AccessibilityService Android                 │
└───────────────────────┬───────────────────────┘
                        │ actions sur les nœuds accessibles
                        ▼
            Application d’IA au premier plan
```

## 4. Composants

### 4.1 Serveur HTTP et endpoints

Le serveur Android conserve l’API et les capacités générales d’API Bridge indépendamment de la méthode de pilotage de l’application :

| Méthode | Route | Responsabilité |
|---|---|---|
| `POST` | `/v1/chat/completions` | Requête de conversation compatible OpenAI |
| `GET` | `/v1/models` | Modèles disponibles dans les adaptateurs configurés |
| `GET` | `/health` | Disponibilité du serveur et état de l’automatisation |
| `GET` | `/status` | Service d’accessibilité, adaptateur sélectionné et état de l’app |
| `GET` | `/stats` | Requêtes, estimation des tokens et durée de fonctionnement |
| `GET/POST` | `/config` | Lecture et mise à jour des paramètres autorisés |
| `GET/POST` | `/ask` | Envoi simplifié d’une question |
| `POST` | `/upload` | Traitement d’un fichier selon les capacités configurées |
| `GET/POST` | `/stop` | Demande d’arrêt de l’opération active, si l’application cible le permet; `GET` reste accepté pour compatibilité |
| `GET` | `/` | Interface d’administration locale |

Toutes les routes de contrôle et de données sont protégées par `Authorization: Bearer <clé>`. `/health` peut être rendu accessible sans clé uniquement si cette option est explicitement activée. La clé est créée localement, conservée dans un stockage Android protégé et affichée uniquement dans les écrans de configuration autorisés.

Le serveur n’est pas destiné à être exposé directement sur Internet. L’écoute et les règles réseau doivent limiter l’accès au réseau local; le mode USB/ADB peut être conservé comme option de connexion locale. Les interfaces web d’administration doivent également exiger l’authentification.

### 4.2 Couche de requête et sessions

La requête OpenAI est validée avant toute action UI. Le serveur :

1. Vérifie le Bearer token, le modèle, le corps et la liste `messages`.
2. Refuse les contenus et formats non pris en charge avec une erreur JSON explicite.
3. Sérialise le travail dans une file à un seul consommateur.
4. Formate l’historique fourni en messages lisibles (`[Utilisateur]`, `[Assistant]`, etc.) lorsque l’adaptateur demande un rejeu.
5. Soumet une seule opération à l’adaptateur sélectionné.
6. Convertit le résultat lu dans l’interface en réponse OpenAI.

Le rejeu d’historique est limité en taille par une configuration. La troncature conserve les messages les plus récents et ne coupe pas silencieusement le message utilisateur courant. Le texte effectivement soumis est contrôlé par l’adaptateur : certains écrans peuvent ne pas accepter de très longues entrées.

Une file d’attente évite que plusieurs requêtes modifient simultanément la même conversation. Sa taille maximale et le délai d’attente sont configurables. Les requêtes en attente excédentaires reçoivent une erreur explicite plutôt qu’un traitement concurrent ou une perte silencieuse.

### 4.3 Registre d’adaptateurs

Un adaptateur décrit comment reconnaître les éléments et états nécessaires pour une application donnée. La logique commune reste dans le serveur et l’exécuteur; les sélecteurs DeepSeek sont conservés dans `app/src/main/assets/adapters/deepseek.json`.

Un adaptateur contient au minimum :

- le nom et la version de l’application cible;
- son identifiant de paquet Android;
- les sélecteurs du champ de saisie et du bouton d’envoi;
- la stratégie de reconnaissance des messages utilisateur et assistant;
- les indices permettant de détecter une génération active et terminée;
- les délais et limites de texte;
- les variantes de sélecteurs autorisées, ordonnées par priorité.

Exemple indicatif :

```json
{
  "provider": "deepseek",
  "version": "1.0",
  "package_name": "com.deepseek.chat",
  "selectors": {
    "input": [
      { "by": "resource_id", "value": "com.deepseek.chat:id/input" },
      { "by": "class_name", "value": "android.widget.EditText" }
    ],
    "send": [
      { "by": "content_description", "value": "Envoyer" },
      { "by": "text", "value": "Envoyer" }
    ],
    "assistant_message": [
      { "by": "resource_id", "value": "com.deepseek.chat:id/answer" }
    ]
  },
  "timeouts": {
    "element_ms": 10000,
    "response_ms": 180000
  },
  "max_history_chars": 8000
}
```

Les valeurs de cet exemple sont illustratives; elles doivent être déterminées en inspectant l’interface réellement installée. Les identifiants d’éléments et les libellés changent parfois selon la version, la langue et l’état de l’application.

### 4.4 Exécuteur d’interface

L’exécuteur reçoit une requête normalisée et déroule un scénario fini :

1. Vérifier que le service d’accessibilité est actif et que la fenêtre de l’application cible est au premier plan.
2. Lire l’arbre d’accessibilité courant et identifier les éléments selon l’ordre des sélecteurs.
3. Vérifier que chaque correspondance est unique et que l’état de l’écran est attendu.
4. Remplir le champ avec `ACTION_SET_TEXT` ou une action d’édition appropriée.
5. Actionner le bouton d’envoi avec `ACTION_CLICK`.
6. Observer les événements et les changements de l’arbre jusqu’à la fin de génération.
7. Lire le texte de la réponse depuis les nœuds accessibles du message assistant.
8. Vérifier que le texte appartient bien à la nouvelle réponse, puis le transmettre au serveur.

L’exécuteur privilégie les identifiants de ressource, puis les descriptions, textes, classes et relations parent/enfant selon l’adaptateur. Une correspondance ambiguë, absente ou inaccessible produit une erreur diagnostiquable. Il ne choisit pas arbitrairement un élément et ne simule pas de tap à une position d’écran comme solution de repli.

La détection de fin combine, selon les éléments accessibles dans l’application cible, les événements de contenu, les changements de texte, les indicateurs de chargement, l’apparition/disparition des commandes d’arrêt et la stabilité de l’arbre. Une simple absence momentanée d’événements n’est pas considérée à elle seule comme preuve qu’une réponse est terminée.

L’exécuteur limite l’inspection au paquet cible, ne journalise pas les textes de conversation en production et libère les objets d’accessibilité après usage. Il n’interfère pas avec une fenêtre de connexion ou de vérification : si l’application exige une action manuelle, l’opération se termine avec un état « intervention utilisateur requise ».

### 4.5 Service d’accessibilité

Le service déclare uniquement les capacités nécessaires pour lire les fenêtres pertinentes et actionner leurs nœuds accessibles. L’utilisateur l’active dans les paramètres Android. Le service transmet les événements utiles à l’exécuteur et expose un état clair de disponibilité.

La configuration doit être explicite, limitée et compréhensible pour l’utilisateur. Les journaux d’événements évitent de conserver le contenu des messages, identifiants personnels ou données sensibles.

### 4.6 Interface d’administration

L’interface locale doit permettre de :

- consulter l’adresse réseau, le port et l’état du service;
- activer le serveur et choisir un adaptateur;
- générer, consulter avec contrôle d’accès et renouveler la clé API;
- voir l’état de l’application cible et les erreurs d’automatisation;
- configurer le délai maximal, la limite d’historique et le streaming;
- consulter des statistiques agrégées sans contenu de conversation;
- arrêter une génération lorsqu’une action correspondante est disponible.

La configuration ne propose pas de zones de tap ou de calibration de coordonnées.

## 5. Contrat API OpenAI

### 5.1 Requête

`POST /v1/chat/completions` accepte les champs usuels `model`, `messages`, `stream` et, lorsque pris en charge, `tools` et `tool_choice`. Les rôles `system`, `developer`, `user` et `assistant` sont conservés dans le contexte rejoué. Les messages d’outils précédents sont intégrés comme contexte explicite.

Les contenus multimodaux, pièces jointes intégrées et champs non pris en charge sont rejetés clairement ou traités par le endpoint d’upload, selon les fonctionnalités effectivement disponibles; ils ne sont pas ignorés silencieusement.

### 5.2 Réponse non-streamée

La réponse suit la forme `chat.completion` et contient un identifiant, l’horodatage, le modèle, un choix assistant, le motif de fin et une estimation d’usage. L’estimation de tokens est annoncée comme telle et non présentée comme une mesure interne du modèle.

### 5.3 Streaming simulé

L’application cible ne fournit pas nécessairement les tokens au fur et à mesure. Si `stream: true`, API Bridge attend donc la réponse complète, puis envoie cette réponse en plusieurs événements SSE, terminés par `data: [DONE]`. Le client bénéficie de la forme de transport SSE, mais pas d’une latence token par token.

### 5.4 Tool calling

Les définitions de `tools` peuvent être incluses dans le contexte transmis à l’application. Si la réponse contient une représentation de tool call reconnue, le parseur retourne un champ `tool_calls` compatible. API Bridge ne prétend pas avoir exécuté l’outil du client : le client reçoit l’appel et doit renvoyer le résultat dans les messages suivants.

## 6. Erreurs et états

Le serveur retourne des erreurs structurées et distingue au minimum :

- authentification invalide ou absente;
- requête mal formée ou champ obligatoire manquant;
- service d’accessibilité désactivé;
- application cible non installée ou non au premier plan;
- connexion ou écran de validation nécessitant l’intervention de l’utilisateur;
- sélecteur manquant ou ambigu;
- délai d’attente dépassé;
- réponse vide ou non lisible dans l’arbre d’accessibilité;
- file d’attente pleine ou demande d’arrêt non prise en charge.

Aucun de ces cas ne doit être transformé en une réponse assistant ressemblant à un succès. Le panneau d’administration indique l’action de rétablissement utile sans révéler de contenu sensible.

## 7. Sécurité et confidentialité

- Exiger un Bearer token aléatoire sur les endpoints de données et d’administration.
- Stocker la clé et les paramètres sensibles dans un stockage Android adapté.
- Limiter l’écoute au réseau local et documenter les règles de pare-feu du téléphone.
- Éviter les journaux de prompts, réponses, contenu du presse-papiers et données de compte.
- Protéger les endpoints d’upload; limiter taille, type, durée de conservation et destinations externes configurées.
- Afficher clairement que l’accessibilité donne au service accès au contenu visible des fenêtres.
- N’inspecter que l’application cible pendant l’exécution d’une requête.
- Arrêter le traitement si une authentification ou action manuelle est requise.

## 8. Limites assumées

- La stabilité dépend de la qualité et de la disponibilité de l’arbre d’accessibilité de l’application cible.
- Les mises à jour, langues, variantes de compte et changements d’écran peuvent nécessiter une mise à jour de l’adaptateur.
- Certaines vues personnalisées exposent peu ou pas de texte accessible; ces états doivent échouer clairement plutôt que deviner.
- Le téléphone doit rester allumé et l’application cible accessible pendant l’opération.
- Une seule requête est active par appareil; le parallélisme exige des appareils distincts et n’est pas une capacité du worker unique.
- Les réponses longues, le réseau mobile et les limitations propres à l’application peuvent dépasser les délais configurés.
- L’extraction du texte d’interface ne garantit pas la conservation de la mise en forme ou de tous les éléments d’une réponse.

## 9. Arborescence cible

```text
api_bridge/
├── app/src/main/
│   ├── java/com/ialocalbridge/
│   │   ├── server/             # HTTP, auth, endpoints, SSE
│   │   ├── logic/              # requêtes, sessions, file, adaptateurs
│   │   ├── accessibility/      # inspection et actions sur les nœuds UI
│   │   ├── executor/           # états, délais, extraction de réponse
│   │   ├── ui/                 # configuration et état Android
│   │   └── utils/              # outils partagés, upload et parsing
│   ├── assets/adapters/
│   │   └── deepseek.json
│   └── res/
├── docs/
│   └── ARCHITECTURE.md
├── AGENTS.md
└── README.md
```

Cette arborescence est logique; elle peut être adaptée aux packages déjà établis si cela réduit le risque de régression.

## 10. Feuille de route

| Priorité | Travail | Critère d’acceptation |
|---|---|---|
| P0 | Inspecter l’arbre d’accessibilité de DeepSeek | Les écrans, champs, boutons et réponses nécessaires sont observables sans coordonnées |
| P1 | Créer l’adaptateur DeepSeek | Les sélecteurs sont déclaratifs, testés sur les états clés et échouent proprement en cas d’ambiguïté |
| P2 | Implémenter l’exécuteur UI | Envoi, attente de fin et lecture d’une réponse fonctionnent via actions de nœuds |
| P3 | Ajouter file d’attente et gestion d’états | Une seule requête s’exécute; les erreurs sont explicites et récupérables |
| P4 | Relier le serveur OpenAI à l’exécuteur | Requête locale authentifiée avec réponse JSON valide |
| P5 | Valider le streaming SSE simulé | Un client compatible reçoit des chunks et `[DONE]` |
| P6 | Valider le rejeu d’historique et tool calls | Plusieurs tours produisent un contexte cohérent; les tool calls sont structurés |
| P7 | Finaliser l’interface de configuration et sécurité | Activation, clé, état, logs expurgés et configuration sont utilisables sur téléphone |
| P8 | Valider uploads et autres adaptateurs | Fonctions existantes préservées et nouveaux adaptateurs ajoutables par configuration |

P0 est bloquant : si les éléments nécessaires ne sont pas exposés de manière accessible, le projet doit documenter précisément les limites avant d’élargir la portée ou de choisir une autre stratégie.
