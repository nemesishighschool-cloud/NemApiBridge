# Mise à jour — refonte par sélecteurs

L’ancienne automatisation par calibration, coordonnées et copie du presse-papiers est remplacée par une exécution configurable via l’arbre Android d’accessibilité.

API Bridge importe des providers JSON contenant le package cible, les sélecteurs d’entrée/envoi/réponse et les délais. L’API HTTP locale conserve les routes OpenAI, utilise une clé Bearer chiffrée et traite les opérations UI en série. L’inspecteur accessible depuis la fenêtre flottante permet de relever les sélecteurs sur l’appareil.

Le format de provider et les limites sont décrits dans [`README.md`](README.md) et [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). L’exemple JSON est [`docs/provider-template.json`](docs/provider-template.json).

La compilation et les tests automatisés doivent être confirmés par GitHub Actions. L’inspection et l’automatisation d’un provider réel restent à essayer sur un téléphone, avec l’application cible ouverte et connectée manuellement.
