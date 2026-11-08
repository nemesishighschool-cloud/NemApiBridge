# Instructions pour les agents — API Bridge

## But du dépôt

API Bridge est une application Android Kotlin qui expose un serveur HTTP local compatible OpenAI et automatise une application d’IA installée sur le téléphone. La méthode cible lit l’arbre d’accessibilité Android, repère les éléments sémantiquement et agit sur leurs nœuds. La spécification de référence est [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Règles de travail

- Lire l’architecture et les fichiers concernés avant de modifier le code.
- Implémenter une fonctionnalité complète et testable; ne pas présenter une étape planifiée comme déjà opérationnelle.
- Garder le serveur HTTP, les contrats de réponse, l’interface d’administration, les statistiques, les fonctions de configuration, le traitement de fichiers et le parsing de tool calls compatibles avec leur comportement documenté.
- Ne pas utiliser de positions d’écran enregistrées ni de calibration. Privilégier les actions sémantiques des nœuds. Un provider peut explicitement autoriser, en dernier recours, un geste calculé depuis les limites courantes d’un nœud unique et visible ; ne pas utiliser ce mode par défaut.
- Ne pas coder en dur les sélecteurs d’un fournisseur dans l’exécuteur commun; les conserver dans un adaptateur JSON lorsque c’est possible.
- Traiter les sélecteurs absents ou ambigus, les délais, les erreurs réseau et les états de connexion comme des erreurs explicites. Ne pas retourner une réponse de succès de substitution.
- Traiter au plus une opération UI à la fois sur un appareil. Protéger l’état partagé et borner la file d’attente.
- Respecter les consentements Android : l’utilisateur active le service d’accessibilité et se connecte lui-même à l’application cible. Ne pas contourner les écrans de connexion, les défis de compte ou les validations.
- Limiter les observations à l’application cible pendant les requêtes. Ne pas écrire de prompts, réponses, données du presse-papiers, identifiants ou clés dans les logs.
- Garder l’accès réseau local. La protection Bearer de l’API est facultative et désactivée par défaut ; le chat et l’administration web sont ouverts sur le réseau local même quand elle est activée. Documenter clairement ce risque. Ne pas ajouter de secret dans le code ou les ressources versionnées.
- Éviter les dépendances nouvelles si les API Android et bibliothèques déjà présentes suffisent.
- Respecter les conventions Gradle, Kotlin, les noms et le formatage existants.
- Préserver les changements déjà présents dans l’arbre de travail; ne jamais réinitialiser ou supprimer les changements de quelqu’un d’autre.

## Automatisation d’interface

- Inspecter `rootInActiveWindow` et les événements d’accessibilité pertinents; filtrer par paquet cible.
- Préférer les IDs de ressources stables, puis descriptions, textes, classes et relations structurelles définis dans l’adaptateur.
- Vérifier l’unicité et l’état des éléments avant toute action.
- Utiliser en priorité les actions des nœuds pour renseigner un champ, cliquer ou lire le texte. Si un geste dynamique est explicitement activé, vérifier la fenêtre cible, l’unicité et la visibilité du nœud. Une action nécessitant une intervention humaine doit suspendre ou terminer proprement la requête.
- Détecter la fin de génération avec des conditions configurées et changements observables; ne pas dépendre de délais fixes seuls ou d’une boucle sans borne.
- Libérer les nœuds Android et borner temps, texte, historique et nombre d’opérations.
- Produire des erreurs d’état distinctes pour le service désactivé, la mauvaise application au premier plan, le sélecteur manquant, l’ambiguïté, la validation utilisateur et le timeout.

## Validation

Pour toute modification Kotlin/Android :

1. Exécuter les tests ciblés existants.
2. Compiler l’application avec `./gradlew assembleDebug`.
3. Ajouter ou adapter des tests pour tout nouveau comportement observable.
4. Rapporter les vérifications exécutées et les limitations non vérifiées.

Les documents doivent distinguer clairement la spécification cible, le code implémenté et les validations réalisées. Garder `README.md` cohérent avec l’architecture et utiliser `docs/ARCHITECTURE.md` pour les décisions détaillées.
