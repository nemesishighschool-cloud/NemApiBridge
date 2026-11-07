# API Bridge — notes de projet

API Bridge est une application Android Kotlin qui sert une API locale compatible OpenAI. Elle observe l’application d’IA sélectionnée via le service d’accessibilité Android et utilise des sélecteurs importés depuis un fichier JSON; aucune coordonnée n’est calibrée ou codée en dur.

- **Architecture et limites :** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- **Installation, API et configuration :** [`README.md`](README.md)
- **Modèle de provider :** [`docs/provider-template.json`](docs/provider-template.json)

L’utilisateur active lui-même l’accessibilité et se connecte à l’application cible. Les providers doivent être configurés pour la version et la langue réellement installées. La réussite du build GitHub Actions et les essais réels doivent être rapportés séparément; le code seul ne prouve pas qu’un sélecteur fonctionne sur une app donnée.
