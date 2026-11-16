# RoadDeck

## Livraison du 16 novembre 2026

La version **0.2.0-dev**, code de version **2**, a été compilée à partir du projet original complet, avec son interface HTML/CSS/JS locale et ses services Android, puis enregistrée dans le Google Drive du propriétaire sous **RoadDeck.apk**.

- Application : `fr.roaddeck.launcher`.
- Android minimum : API 26 ; cible et compilation : API 35.
- Taille APK : 4 099 443 octets.
- SHA-256 : `35a7373664d3c3ccdd904948c8a1da3fd4272ec382623241d3e13c4b4f87f531`.
- Compilation Gradle 8.9 / Android Gradle Plugin 8.7.3 réussie.
- Signature APK v2 vérifiée.
- 66 assertions de logique et 101 assertions d'interface hors ligne réussies ; pont Android simulé dans les tests d'interface. Aucun test sur l'autoradio du propriétaire.

Le projet complet exact et sa clé de signature de développement sont conservés **privatement dans le Drive du propriétaire**, dans `RoadDeck-projet-prive.zip`. Ce fichier n'est pas partagé publiquement. Ne jamais publier son dossier `signing` ni une clé de signature dans ce dépôt.

## Important : contenu de ce sous-dossier

Les fichiers Android présents actuellement dans ce sous-dossier sont le **prototype de compilation 0.1**, pas le code complet de l'APK 0.2.0 livrée. La release GitHub `roaddeck-latest`, si elle est encore présente, correspond à ce prototype et n'est pas l'APK livrée sur Drive.

Pour modifier la version livrée, repartir de l'archive privée complète, conserver la signature et augmenter `versionCode`. Remplacer le fichier Drive existant au lieu d'en créer un autre.

La compilation complète a été réalisée hors ligne après récupération des outils Android et des dépendances avec le workflow `roaddeck-toolchain.yml`.

## Fonctions de la version livrée

- Interface reprenant les éléments graphiques de la maquette, un seul panneau Musique/Radio.
- Sessions multimédias Android pour les applications musicales compatibles ; autorisation d'accès aux notifications à activer.
- Radio Media3/ExoPlayer, recherche Radio Browser, favoris, tampon et reconnexion.
- Météo Open-Meteo avec ville configurable et cache de la dernière réponse.
- Scène Tuya Cloud configurable, secret stocké via Android Keystore ; aucun compte ni identifiant de portail prérempli.
- Ouverture du véritable Waze en mode plein écran ou demande de fenêtre native positionnée à gauche. L'affichage fenêtré dépend du système Android ; ce n'est pas un SDK Waze incorporé ni une garantie de fonctionnement sur tous les autoradios.
