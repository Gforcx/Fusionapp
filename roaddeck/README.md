# RoadDeck 0.5.4 — démarrage animé et habillage des barres Android

Application Android paysage, package `fr.roaddeck.launcher`, versionCode **10**,
versionName **0.5.4**, Android 8 minimum. Appareil cible : autoradio Android 10.

## Fonctionnement

Waze reste une application dans une vraie fenêtre Android à gauche. Le panneau
RoadDeck occupe la fenêtre de droite. L’assistant d’accessibilité intégré place
les deux fenêtres et vérifie leurs dimensions. Il utilise leurs noms et leurs
limites, sans lire les messages ni capturer l’écran.

À l’ouverture normale de RoadDeck, le démarrage automatique prépare cette paire.
Un écran natif animé, avec une route violette et les étapes de préparation, couvre
la mise en place. Il se retire lorsque la disposition est confirmée et que le
panneau droit a terminé son premier rendu. **Annuler** permet d’interrompre la
préparation. Un échec ou un délai dépassé rend la main avec un message.
Cette confirmation porte sur les fenêtres ; elle ne prouve pas que Waze a chargé
ses cartes ou trouvé un itinéraire.

L’habillage peut recouvrir les bandes Android mesurées pour raccorder visuellement
les deux parties. **Toucher une bande masquée** ou le bouton **Android** réaffiche
les commandes pendant huit secondes. Le masquage est retiré lorsque la paire
n’est plus active ou que le contexte ne permet plus de l’afficher.

Android conserve ses barres et l’espace qu’elles occupent. Ce masque visuel
n’agrandit pas Waze et ne supprime pas les fenêtres système. Le micrologiciel de
l’autoradio et Waze doivent accepter le partage natif. Aucun root, Shizuku ou
ordinateur n’est nécessaire sur un appareil compatible.

## Installation et réglages

Installer l’APK signée comme mise à jour, sans désinstaller RoadDeck.
L’assistant doit être activé dans **Réglages → Waze → Activer l’assistant d’écran
partagé**, puis **RoadDeck — écran partagé** dans l’accessibilité Android.

Dans **Réglages → Waze**, deux options sont activées par défaut :

- **Ouvrir Waze au démarrage** prépare la paire à l’ouverture de RoadDeck.
- **Masquer visuellement les barres Android** active les bandes de raccord.

Le réglage **Habillage autour de Waze** reste indépendant. Ses quatre marges de
12 dp sont réglables de 0 à 40 dp. Elles recouvrent les bords de la carte sans
modifier la taille de la fenêtre. **Waze en grand** ferme la paire.

Pour l’ouverture au démarrage de l’autoradio, choisir RoadDeck comme application
d’accueil dans les réglages Android, accessibles depuis **Réglages → Général**.
Le lancement dépend alors de l’accueil du système. L’application n’ajoute pas de
récepteur de démarrage `BOOT_COMPLETED`.

## Fonctions conservées

- Un panneau unique musique/radio, avec les sessions multimédias Android pour
  Spotify, Deezer ou le lecteur choisi, après autorisation des notifications.
- Radio internet Media3, annuaire Radio Browser, favoris et tampon réglable.
- Météo Open-Meteo, ville configurable et cache local horodaté.
- Scène Tuya Cloud configurée sur l’appareil, secret chiffré et confirmation.

## Compilation et portée des essais

JDK 17, Gradle 8.9, Android SDK 35, Android Gradle Plugin 8.7.3.
`gradle :app:assembleRelease` compile l’application ; la distribution installable
doit être signée. `node tests/test_split_ui.cjs` vérifie l’interface et
`gradle :app:testDebugUnitTest` vérifie la géométrie.

Le module optionnel `fixture` et les essais instrumentés s’activent avec
`-ProaddeckFixtures=true`. La fixture est une application de navigation de test
séparée. Elle remplace Waze uniquement dans ces essais et n’est jamais livrée dans
l’APK RoadDeck. Un succès sur émulateur ne démontre pas le fonctionnement de Waze
réel sur le micrologiciel de l’autoradio.

Les résultats applicables sont ceux de la version et du commit explicitement
indiqués dans `BUILD_VERIFIE.json` et [docs/TESTS.md](docs/TESTS.md). Les preuves
conservées dans `docs/verification-0.5.3/` concernent **la version précédente**.
Les ANR observées durant des essais anciens restent des faits historiques ; elles
ne sont pas annoncées comme résolues sur Xiaomi/HyperOS par cette mise à jour.

Après un échec : **Réglages → Informations → Copier le diagnostic**. Aucun rapport
n’est envoyé automatiquement. Voir [les changements 0.5.4](docs/CHANGELOG-0.5.4.md)
pour les limites du masquage et les sources Android.
