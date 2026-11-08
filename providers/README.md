# Provider DeepSeek expérimental

`deepseek.json` cible `com.deepseek.chat` version 2.7.5 avec interface en anglais, relevée sur émulateur Android 13. Importer le fichier dans API Bridge, activer volontairement le service d’accessibilité et ouvrir DeepSeek sur une conversation. Le provider est compatible avec le moteur modifié de ce dépôt ; l’ancien APK ne comprend pas ces options.

## Comportement et vérifications

- Le champ actif `android.widget.EditText` accepte `ACTION_SET_TEXT`. Le bouton `Send` apparaît ensuite ; son parent cliquable est actionné sémantiquement.
- Le champ au repos est un `TextView` sans clic accessible. Le JSON active explicitement `allow_input_gesture` : si le clic sémantique échoue, API Bridge calcule un toucher depuis les limites du nœud unique visible. Les positions ne sont pas enregistrées.
- `Copy` ancre chaque réponse : le lecteur remonte deux parents et lit les textes de ce conteneur sans utiliser le presse-papiers.
- Les longues réponses peuvent être virtualisées. Après génération, le lecteur fait défiler la conversation, conserve l’identité du conteneur et aligne les fragments ordonnés. Le provider active de courts gestes calculés depuis la zone défilante pour créer un chevauchement. Un chevauchement absent, une identité perdue ou un ordre ambigu retournent `response_incomplete`.
- Tests sur émulateur : trois requêtes courtes successives réussies ; deux requêtes consécutives retournant la même réponse, vérifiées également dans l’interface ; une réponse longue mono-nœud avec 20 repères dans l’ordre ; une réponse longue multi-blocs avec 20 numéros et descriptions exacts. Ces tests ne prouvent pas la fiabilité de toutes les mises en forme ou de tous les cas de virtualisation.
- Les versions ultérieures, autres langues, appareils physiques, pièces jointes et le contrôle `Stop` ne sont pas validés. Une réponse identique ne peut être reconnue que si le nouveau prompt et son ancre de réponse sont tous deux accessibles ; sinon la requête peut expirer.

## Extensions JSON génériques

- `input_activation` : sélecteurs du contrôle qui ouvre le champ ; `allow_input_gesture` autorise un geste calculé uniquement si l’action de clic accessible échoue.
- `detect_identical_responses` : tente d’identifier une nouvelle réponse identique au texte précédent en vérifiant l’ordre et l’identité des nœuds du nouveau prompt et de la réponse.
- `ancestor_levels` : remonte de 0 à 8 parents à partir de l’ancre de réponse.
- `text_only` : exclut les descriptions des boutons du texte extrait.
- `scroll_to_end` : sélecteur unique du conteneur défilant ; `by: scrollable, value: true` identifie un nœud défilant.
- `reject_clipped` et `reconstruct_clipped` : détectent un début hors écran et tentent une reconstruction contrôlée. `allow_reconstruction_gesture` autorise des défilements courts calculés depuis les limites courantes.

Les gestes sont désactivés par défaut pour les autres providers. La reconnaissance se fonde sur les éléments visibles de l’app cible et leurs limites actuelles, pas sur une calibration. Les libellés observés ne sont pas des identifiants stables : toute mise à jour ou autre langue peut rendre l’adaptateur invalide.
