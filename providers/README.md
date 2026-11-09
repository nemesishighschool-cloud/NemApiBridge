# Provider DeepSeek expérimental

`deepseek.json` cible `com.deepseek.chat` version 2.7.5 avec interface en anglais, relevée sur émulateur Android 13. Importer le fichier dans API Bridge, activer volontairement le service d’accessibilité et ouvrir DeepSeek sur une conversation. Le provider est compatible avec le moteur modifié de ce dépôt ; l’ancien APK ne comprend pas ces options.

## Comportement et vérifications

- Le champ actif `android.widget.EditText` accepte `ACTION_SET_TEXT`. Le bouton `Send` apparaît ensuite ; son parent cliquable est actionné sémantiquement.
- Le champ au repos est un `TextView` sans clic accessible. Le JSON active explicitement `allow_input_gesture` : si le clic sémantique échoue, API Bridge calcule un toucher depuis les limites du nœud unique visible. Les positions ne sont pas enregistrées.
- En mode `copy_response`, le bouton accessible de la **réponse entière** (`content_description: Copy`) est actionné après la génération. Le texte du presse-papiers est renvoyé tel quel, sans recoller les fragments ; les boutons « Copy » à l’intérieur des blocs de code ne sont pas visés.
- Une fenêtre d’accessibilité temporaire donne le focus à NemApiBridge pour la lecture sur Android 10 et plus ; elle peut apparaître brièvement. Un marqueur aléatoire est écrit et vérifié avant la copie : si rien ne change, la requête échoue au lieu de renvoyer un ancien presse-papiers.
- **Attention : le presse-papiers existant est remplacé**, et la réponse copiée reste dans le presse-papiers après la requête. Sur Android avant 10, des applications en arrière-plan peuvent lire ou modifier ce contenu ; sur Android 13+, une prévisualisation ou notification système peut apparaître. Évitez les données sensibles. Une autre application pourrait modifier le presse-papiers au même moment : Android ne garantit pas l’origine du contenu lu.
- Une conversation nouvelle ou courte peut ne pas avoir de conteneur défilant. Le moteur accepte ce cas uniquement avec vérification du nouveau prompt et de la réponse ; il rejette une réponse dont les bornes accessibles paraissent tronquées ou ne peuvent pas être vérifiées ; cette heuristique ne garantit pas tous les cas de virtualisation. Les petites barres horizontales défilantes ne sont pas prises pour la conversation.
- Tests sur émulateur : trois requêtes courtes successives réussies ; deux requêtes consécutives retournant la même réponse, vérifiées également dans l’interface ; une réponse longue mono-nœud avec 20 repères dans l’ordre ; une réponse longue multi-blocs avec 20 numéros et descriptions exacts. Ces tests ne prouvent pas la fiabilité de toutes les mises en forme ou de tous les cas de virtualisation.
- Tests presse-papiers sur émulateur Android 13 : 25 et 100 blocs Python répétés, repères, indentation et délimiteurs Markdown exacts ; échec contrôlé si le contrôle actionné ne copie rien. Le test 100 blocs comprend 10 598 caractères et 899 lignes. Régression facultative : `python3 tests/deepseek-clipboard-regression.py --blocks 100` (envoie une vraie requête et remplace le presse-papiers).
- La v1.0.2, les autres langues, les pièces jointes et le contrôle `Stop` ne sont pas validés sur téléphone physique. Une réponse identique ne peut être reconnue que si les indices de génération et de réponse sont accessibles ; sinon la requête peut expirer.

## Extensions JSON génériques

- `copy_response` : sélecteur opt-in du bouton qui copie la réponse entière. Sans ce champ, le moteur utilise l’ancien lecteur d’accessibilité et ne modifie pas le presse-papiers.
- `input_activation` : sélecteurs du contrôle qui ouvre le champ ; `allow_input_gesture` autorise un geste calculé uniquement si l’action de clic accessible échoue.
- `detect_identical_responses` : tente d’identifier une nouvelle réponse identique au texte précédent en vérifiant l’ordre et l’identité des nœuds du nouveau prompt et de la réponse.
- `ancestor_levels` : remonte de 0 à 8 parents à partir de l’ancre de réponse.
- `text_only` : exclut les descriptions des boutons du texte extrait.
- `scroll_to_end` : sélecteur unique du conteneur défilant ; `by: scrollable, value: true` identifie un nœud défilant.
- `reject_clipped` et `reconstruct_clipped` : détectent un début hors écran et tentent une reconstruction contrôlée. `allow_reconstruction_gesture` autorise des défilements courts calculés depuis les limites courantes.

Les gestes sont désactivés par défaut pour les autres providers. La reconnaissance se fonde sur les éléments visibles de l’app cible et leurs limites actuelles, pas sur une calibration. Les libellés observés ne sont pas des identifiants stables : toute mise à jour ou autre langue peut rendre l’adaptateur invalide.

### Mise à jour et limites

La configuration DeepSeek 2.7.5 anglaise de la v1.1.0 active `copy_response` et utilise un délai de génération de 10 minutes. Après une mise à jour depuis une version antérieure, **arrêtez le serveur, puis réimportez le `deepseek.json` de cette release** sous le nom `DeepSeek` : l’installation de l’APK ne remplace pas les providers enregistrés. Le délai de votre client HTTP doit aussi être suffisant.

La réponse copiée est limitée à 500 000 caractères par NemApiBridge, et Android ou DeepSeek peuvent imposer des limites inférieures. Les mises en forme et la complétude dépendent de ce que le bouton DeepSeek copie ; il n’y a pas de garantie générale pour toutes les versions, langues, pièces jointes ou tailles. Si la copie ne peut pas être vérifiée, la requête échoue. Le réseau web reste ouvert sans clé ; utilisez-le seulement sur un réseau fiable.
