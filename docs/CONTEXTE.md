# Contexte technique — NemApiBridge

## Objectif et état

NemApiBridge est une application Android Kotlin/Gradle, exécutant un serveur HTTP local compatible avec une partie des API OpenAI et pilotant une application IA installée, avec l’accord de l’utilisateur. Le dépôt officiel est <https://github.com/nemesishighschool-cloud/NemApiBridge>. Le package Android `com.ialocalbridge` est conservé pour les mises à jour. Le README est la référence utilisateur ; `docs/ARCHITECTURE.md` est la référence d’architecture. Les notes de release et le journal d’essais décrivent les validations réelles et leurs limites.

## Carte du code

- `app/src/main/java/com/ialocalbridge/LocalApiServer.kt` : routes HTTP, intégrations web et API.
- `AutomationCoordinator.kt`, `ClickAccessibilityService.kt` et `ResponseIdentityPolicy.kt` : sérialisation UI, détection de réponse, presse-papiers et automatisation accessible.
- `BridgeImageProvider.kt`, `ImagePayload.kt` : validation, staging temporaire et exposition des images au sélecteur Android.
- `app/src/main/assets/nemapi-stage/` : interface web adaptée de NemApi. `providers/deepseek.json` : sélecteurs du profil DeepSeek.
- `app/src/test/`, `app/src/androidTest/` et `tests/` : tests unitaires, instrumentation et scénarios réels. Les scénarios Android réels modifient la conversation active ; lire leurs prérequis avant de les lancer.
- `signing/` : clé de publication volontairement publique à la demande du propriétaire ; elle est compromise et ne constitue pas une preuve d’identité.

## Invariants et risques

L’utilisateur active l’accessibilité et se connecte à l’application cible. Pas de calibration ou coordonnées mémorisées ; gestes calculés depuis un nœud unique uniquement si le provider les autorise. Échouer explicitement si l’identité, la complétude ou la cible ne sont pas vérifiables. Ne jamais écrire dans les logs prompts, réponses, octets d’image, presse-papiers, clés ou identifiants de compte. Protéger l’unicité des actions et la file bornée. Android avant 10 autorise potentiellement d’autres applications à lire le presse-papiers.

L’API est ouverte par défaut ; sa protection Bearer est facultative. L’administration web et son chat restent ouverts, même si la protection API est activée. HTTP n’est pas chiffré : usage en réseau privé fiable seulement. Les images envoyées depuis le navigateur sont décodées côté téléphone et mises temporairement dans le cache privé ; aucune copie manuelle préalable sur le téléphone n’est nécessaire. Ne pas télécharger silencieusement des URLs d’images externes.

## Build et validation

Utiliser JDK 17 et le SDK Android configuré, puis `./gradlew :app:testDebugUnitTest :app:assembleDebug`. L’instrumentation et les tests réels nécessitent un appareil configuré et peuvent arrêter ou réinstaller l’application : sauvegarder les données de test, ne jamais désinstaller sur un appareil réel sans accord. Les clés Android Keystore ne sont pas restaurables par simple copie de préférences après désinstallation. Les essais d’émulateur, d’USB/ADB ou de proxy ne prouvent pas le fonctionnement Wi-Fi sur un téléphone physique. Documenter précisément chaque résultat et chaque échec ; ne pas publier une nouvelle release comme complète sur la base d’essais partiels.

Les limites connues de la v1.1.0 incluent une génération qui peut continuer après arrêt de la requête et un blocage intermittent de l’identification de certaines réponses longues. SSE est simulé après lecture complète. Vérifier l’état courant du code et du dépôt avant toute conclusion : ce fichier n’est pas une garantie qu’une version ultérieure a corrigé ces limites.
