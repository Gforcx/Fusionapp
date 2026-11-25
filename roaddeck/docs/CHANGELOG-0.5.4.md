# RoadDeck 0.5.4 — lancement automatique et chargement animé

VersionName **0.5.4**, versionCode **10**. La paire native Waze à gauche / RoadDeck
à droite et les fonctions musique, radio, portail et météo sont conservées.

## Démarrage

- `splitAutoStart`, activé par défaut, prépare le partage lors d’une ouverture
  normale de RoadDeck par le lanceur ou comme application d’accueil.
- Un écran natif animé affiche une route violette et les étapes réelles de
  préparation avant le lancement de Waze. Il couvre la mise en place jusqu’à
  confirmation des deux fenêtres et du premier rendu du panneau droit.
- **Annuler**, les erreurs et un délai maximal permettent de sortir de la
  préparation. Le chargement n’attend pas indéfiniment un partage impossible.
- Les rappels d’un écran déjà fermé sont ignorés, notamment après une annulation
  suivie d’une nouvelle ouverture.
- Choisir RoadDeck comme accueil permet au système de le lancer au démarrage de
  l’autoradio. Aucun récepteur `BOOT_COMPLETED` n’est ajouté.

## Barres Android

`splitCoverBars`, activé par défaut, recouvre visuellement les bandes système
mesurées avec le fond sombre RoadDeck. Il est indépendant de `splitFrame`, qui
continue de régler l’habillage autour de Waze.

Chaque bande est une petite fenêtre d’accessibilité tactile. Sa position réelle
est vérifiée avant affichage. Un appui est consommé par la bande : il réaffiche
les commandes Android pendant huit secondes, sans cliquer sur un bouton caché.
Le bouton **Android** du panneau offre le même accès. Les masques sont retirés
lorsque la paire n’est plus confirmée ou qu’un écran système doit rester accessible.

Il s’agit d’un **masque visuel**. Les barres système existent toujours et l’espace
réservé par Android n’est pas récupéré par Waze. En écran partagé, les flags de
plein écran ne permettent pas à RoadDeck de supprimer ces barres. Le résultat
peut aussi dépendre du micrologiciel du constructeur.

L’intégration conserve les API publiques d’accessibilité. Elle ne demande pas
`SYSTEM_ALERT_WINDOW`, ni accès root, ni Shizuku, ni autorisation supplémentaire.

## Vérification et historique

La validation de cette version doit être lue dans `BUILD_VERIFIE.json` et
[TESTS.md](TESTS.md), avec le numéro de version et le commit correspondants.
Ce journal décrit les changements ; il n’annonce aucun résultat d’essai à venir.
Les captures et rapports de `verification-0.5.3/` restent les preuves historiques
de **0.5.3**, pas une validation de 0.5.4. Une application factice de navigation
sur émulateur ne remplace pas un essai de Waze réel sur l’autoradio.

## Sources Android

- [Prise en charge du multi-window — fonctions désactivées](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode):
  la documentation précise qu’une application en multi-window ne peut pas masquer
  la barre de statut.
- [Politique des barres dans AOSP Android 10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-c2f2-s2-release/services/core/java/com/android/server/wm/DisplayPolicy.java):
  `updateSystemBarsLw` force l’affichage des barres quand le partage natif est actif.
- [Fenêtre d’accessibilité `TYPE_ACCESSIBILITY_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY):
  un service connecté peut superposer une fenêtre tactile tout en conservant
  l’inspection des fenêtres sous-jacentes nécessaire au placement.
