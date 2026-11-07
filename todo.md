# Suivi — refonte API Bridge

## Implémenté dans le code
- [x] Validation et import de providers JSON personnalisables
- [x] Inspection à la demande de l’arbre d’accessibilité et copie des sélecteurs
- [x] Envoi, détection de réponse et lecture par actions de nœuds, sans coordonnées
- [x] API OpenAI, modèles configurés, SSE simulé et parsing des tool calls
- [x] Authentification Bearer locale, clé chiffrée via Android Keystore
- [x] Interface Android de gestion et contrôles flottants redessinés
- [x] Dashboard web authentifié et statistiques sans contenu de conversation
- [x] Workflow GitHub Actions consolidé pour tests et APK

## À valider
- [x] Compilation et tests unitaires dans GitHub Actions (run 42380116782, commit `d215246`)
- [ ] Installation et contrôle des permissions sur un téléphone Android
- [ ] Relevé des sélecteurs de l’application IA cible et essai de bout en bout
- [ ] Vérification d’un client OpenAI en streaming et sans streaming
