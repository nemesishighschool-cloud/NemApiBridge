# NemApiBridge v1.1.0 — adaptation Android de NemApi

Cette release apporte le tableau de bord NemApi adapté à Android, le chat de test, les statistiques estimées, la gestion de clés API et les profils d’intégration Qwen Code, MonkeyCode et NEMESIS. Un seul provider est importé à la fois. L’ancienne interface reste accessible sur `/legacy`. Chat Completions et un sous-ensemble de Responses prennent en charge JSON, SSE simulé et certains appels de fonctions. Les réponses DeepSeek peuvent être copiées entièrement via le bouton Copier, avec écrasement du presse-papiers.

## Installation ou mise à jour

Installez `NemApiBridge-v1.1.0.apk`, puis arrêtez le serveur et réimportez `deepseek-v1.1.0.json` depuis l’application Android pour bénéficier du profil DeepSeek actualisé. Le profil cible DeepSeek 2.7.5 en anglais. L’APK utilise le même certificat que v1.0.2. Réactivez l’accessibilité et la superposition si Android le demande, puis démarrez le serveur. Port par défaut : **8090**. Depuis un PC en USB : `adb forward tcp:8090 tcp:8090`, puis `http://127.0.0.1:8090/` ; en Wi-Fi utilisez l’IPv4 locale du téléphone.

**Réseau fiable uniquement :** le tableau de bord et le chat web restent ouverts sans clé, même si l’authentification API est activée. Ils peuvent afficher et gérer les clés. HTTP n’est pas chiffré. La clé de signature du projet est publique à la demande du propriétaire : elle ne protège pas contre des APK signés par des tiers.

## Limitations et problèmes connus

- **Stop :** le bridge demande l’annulation de ses requêtes active et en attente, mais DeepSeek peut continuer à générer. Une action Stop envoyée n’est pas une preuve d’arrêt ; attendre la fin ou arrêter manuellement. Les nouvelles requêtes peuvent être refusées pendant ce temps, et Stop peut être lent. Une erreur après ouverture d’un flux SSE est transmise dans ce flux.
- Il s’agit d’une **adaptation partielle**, non d’une parité complète avec NemApi : autoconfiguration, création automatique d’une nouvelle discussion à chaque requête et Markdown premium sont indisponibles. Plusieurs formats d’outils et options Responses ne sont pas pris en charge.
- `max_tokens`, `max_output_tokens`, `temperature` et `top_p` ne contrôlent pas le modèle Android. L’en-tête `X-NemApi-Ignored-Parameters` n’est pas exhaustif. Les tokens sont estimés et les coûts non évalués.
- Le contexte incrémental des agents repose sur un repère visuel et peut revenir à l’historique complet ; réinitialiser après changement manuel de discussion. Seuls des scénarios ciblés de Qwen et NEMESIS, et le composant LLM de MonkeyCode, ont été testés.
- Un délai dépassé sur une réponse de 20 lignes et une réponse erronée à un JPEG bleu ont été observés sans cause établie ; des répétitions ont ensuite réussi. La fiabilité n’est pas garantie. Images : PNG/JPEG/WebP en data URL, huit maximum, 4 Mio dans le chat web et corps HTTP de 6 Mio maximum ; les URL distantes et `file_id` sont refusés.
- Essais sur émulateur Android 13 avec DeepSeek 2.7.5 en anglais. Android 8.1 physique, Wi-Fi physique, autres fournisseurs et claviers n’ont pas été validés. Sur Android avant 10, d’autres applications en arrière-plan peuvent lire ou modifier le presse-papiers.

Les détails et limites supplémentaires figurent dans le [README](https://github.com/nemesishighschool-cloud/NemApiBridge/blob/main/README.md) et le journal d’essais du dépôt.

## Vérification des fichiers

- APK SHA-256 : `afc555e947f4f2253c629b1a0ef34c8f6d9401d39128039ece4efe5017acb97c`
- Profil JSON SHA-256 : `76b33729d48dae01ee51beff40cc73e43ca0679904ce12ea32060b60a2ca6fb1`
- Certificat APK SHA-256 : `A0:59:4B:96:40:97:C2:4D:F0:30:68:8E:2F:BB:C2:8D:EE:9C:C0:67:F0:AE:FC:7C:18:F5:82:90:6B:06:4A:3A`

Mise à jour depuis v1.0.2, interface web, chat réel, copie de 25 blocs, SDK OpenAI Responses, lecture Qwen et NEMESIS, JPEG et WebP ont réussi dans des essais ciblés sur l’APK release et l’émulateur indiqué. Ces tests ne couvrent pas tous les cas.
