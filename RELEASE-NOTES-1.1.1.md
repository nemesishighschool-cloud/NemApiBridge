# NemApiBridge 1.1.1

Cette mise à jour réduit l’attente avant la copie et améliore le parcours d’image depuis le chat web.

- Surveillance de la génération également pendant le défilement ; pause du défilement continu lorsque la génération est active, pour éviter de masquer son état.
- Profil DeepSeek 2.7.5 anglais : période d’observation de 300 ms au lieu de 750 ms, avec maintien de trois vérifications de stabilité et des contrôles de copie.
- Prise en charge ciblée de l’écran Android de choix d’application : sélection d’un DocumentsUI système reconnu, uniquement si son libellé est unique et sans collision détectée ; aucun choix permanent. Passage sémantique en vue liste quand le sélecteur Android s’ouvre en grille.
- Clarification du transfert temporaire des images, des risques réseau et de la documentation de reprise.

## Mise à jour

1. Installez `NemApiBridge-v1.1.1.apk` par-dessus la version précédente, sans désinstaller l’application.
2. Arrêtez le serveur, puis importez `deepseek-v1.1.1.json` sous le nom `DeepSeek`. Les profils déjà enregistrés ne sont pas remplacés automatiquement par l’installation.
3. Redémarrez le serveur et laissez l’application cible au premier plan pendant les requêtes.

Dans le chat web, choisissez l’image depuis l’appareil qui utilise le navigateur. Le navigateur l’envoie au téléphone, où NemApiBridge la conserve temporairement en cache privé ; aucune copie manuelle préalable n’est nécessaire. PNG, JPEG et WebP sont acceptés dans les limites documentées dans le README. Si le sélecteur ou la cible est ambigu/non reconnu, l’application demande une intervention au lieu de choisir une application inconnue.

## Validation et limites

Les essais ont été réalisés sur émulateur Android 13 avec DeepSeek 2.7.5 en anglais, pas sur téléphone physique ni en Wi-Fi physique. Les temps entre première observation de réponse inactive et début de copie étaient d’environ 2,3 s avant et 0,948–0,956 s après sur quatre réponses comparables avec un build debug du code final ; le binaire signé final n’a pas été chronométré de la même manière. La fin réelle de génération peut précéder la première observation. La copie elle-même prend encore du temps. Les réponses longues ont été contrôlées par répétition ; ces essais ne garantissent pas l’absence de tout blocage ou de toute réponse incomplète. Le parcours web réel DeepSeek et un parcours complet avec choix d’application forcé dans une application de test ont été vérifiés séparément. Détails : [journal de validation](docs/1.1.1-VALIDATION.md).

Les autres versions et langues, les interfaces Android personnalisées et les autres applications IA ne sont pas garanties. La génération cible peut continuer après l’arrêt de la requête. Le SSE reste simulé. L’API est ouverte par défaut et l’administration web reste ouverte même avec une clé API : **réseau privé fiable uniquement**, HTTP non chiffré. Le presse-papiers est remplacé et peut être lisible par d’autres applications sur les anciennes versions d’Android.

Le propriétaire a demandé la publication du keystore de signature : cette clé est compromise et ne prouve pas l’identité du signataire. Voir [`signing/README.md`](signing/README.md).
