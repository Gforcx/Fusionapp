# RoadDeck 0.5.3 — cadre autour de Waze

Application Android paysage, package `fr.roaddeck.launcher`, versionCode 9,
versionName `0.5.3`, Android 8 minimum. Cible de validation : Android 10.

## Changement

Waze occupe une vraie fenêtre Android réduite à gauche. Une activité RoadDeck
occupe la partie droite. Le grand panneau qui couvrait Waze dans la version 0.4
est retiré du manifeste. L’autorisation « afficher par-dessus » n’est plus demandée.

Un assistant d’accessibilité intégré demande le partage au système et vérifie les
dimensions des deux applications avant d’annoncer que le partage fonctionne.
Il lit les noms d’application et les limites des fenêtres ; il ne lit pas les
messages et ne capture pas l’écran. Une surface non tactile dessine un cadre autour de Waze et remplit
l’espace vide mesuré du séparateur. Elle ne redimensionne pas Waze.

Le micrologiciel de l’autoradio et Waze doivent accepter le partage natif.
RoadDeck ne peut pas imposer ce support, ni garantir la disparition de toutes les
barres système du constructeur. Aucun Shizuku, root ou ordinateur n’est nécessaire
pour activer le service sur un appareil qui accepte le partage.

## Installation

Installer l’APK signée comme mise à jour de RoadDeck, sans désinstaller.
Ouvrir **Réglages → Waze → Activer l’assistant d’écran partagé**.
Activer **RoadDeck — écran partagé** dans les réglages d’accessibilité Android.
Revenir dans RoadDeck et appuyer sur **Afficher Waze à gauche**.
Le bouton **Waze en grand** ferme la paire et rend toute la largeur à Waze.

## Fonctions conservées

- Un seul panneau musique/radio. Commandes de sessions multimédias Android
  pour Spotify, Deezer ou le lecteur choisi, après autorisation des notifications.
- Radio internet Media3, annuaire Radio Browser, favoris et tampon réglable.
- Météo Open-Meteo, ville configurable, cache local horodaté.
- Scène Tuya Cloud configurée sur l’appareil, secret chiffré et confirmation.

## Compilation et essais

JDK 17, Gradle 8.9, Android SDK 35, Android Gradle Plugin 8.7.3.
`gradle :app:assembleRelease` produit une APK non signée quand la clé privée est
absente. Ne jamais installer cette APK non signée ; la livraison est signée séparément.
Ne jamais publier `signing/`, les fichiers keystore ou une archive privée du projet.

`node tests/test_split_ui.cjs` vérifie la compatibilité ES2019 et les réglages compacts.
`gradle :app:testDebugUnitTest` vérifie la géométrie du partage.

Le module optionnel `fixture` et les tests instrumentés sont activés par
`-ProaddeckFixtures=true`. La fixture est une **application de navigation de test**,
isolée, qui utilise le nom de paquet attendu par le coordinateur. Elle n’est pas
Waze, n’est pas intégrée à RoadDeck et n’est jamais livrée à l’utilisateur.
Les essais émulateur sont prévus pour vérifier le redimensionnement, les quatre
coins tactiles, les réglages à droite et le retour au plein écran. Leur résultat
n’est pas une preuve du fonctionnement de Waze réel sur l’autoradio du client.

## Diagnostic du service

Après un échec, ouvrir Réglages → Informations → Copier le diagnostic et coller le rapport dans la conversation. Aucun envoi automatique. La version0.5.1 corrige la distinction accord/connexion et réduit le travail de rendu des réglages ; elle ne revendique pas une panne HyperOS résolue.

### ANR observée pendant les essais API36

Le premier essai 0.5.1 (run CI 45164302888, commit 4c341ec6) a été interrompu par un arrêt du processus pour ANR de MainActivity. La cause interne reste à établir. Un passage ultérieur réussi sur l’émulateur ne prouve pas que cet arrêt a disparu sur Xiaomi/HyperOS. Voir docs/TESTS.md pour la portée des essais.

## Habillage commun

Activé par défaut dans Réglages → Waze → Habillage autour de Waze. Quatre marges de 12 dp, réglables de 0 à 40, entourent une ouverture arrondie transparente. Le fond sombre se raccorde au panneau droit. Les marges recouvrent les pixels périphériques de Waze ; la géométrie de sa fenêtre reste contrôlée par Android. Le bouton « Tester l’habillage (5 s) » affiche un repère temporaire dans la partie gauche. Voir docs/CHANGELOG-0.5.3.md et docs/TESTS.md.
