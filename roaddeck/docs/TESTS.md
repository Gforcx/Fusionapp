# Vérification RoadDeck 0.5.4

## Résultat et portée

La compilation et les deux jobs du commit indiqué dans `BUILD_VERIFIE.json` ont
réussi. L’APK release est signée avec le même certificat que les versions
précédentes. Signature v2, alignement, version 0.5.4/code10 et absence de fixture
dans l’APK vérifiés.

Les essais installent la variante debug issue du même commit. Le binaire release
signé n’est pas installé dans l’émulateur. La navigation est une application
Android de test séparée, explicitement `testOnly` ; Waze réel et l’autoradio
ne sont pas disponibles ici.

## Android 10

Neuf tests instrumentés réussis : quatre tests de rendu du cadre, deux tests de
rendu du chargement et trois parcours avec les véritables fenêtres Android.

- Une entrée normale `MAIN`/`LAUNCHER` ouvre la paire sans appuyer sur Ouvrir Waze.
- Le chargement est capturé dans l’écran composé, sur les quatre bords physiques.
  Le bouton Annuler est actionné par un vrai toucher ; retour aux réglages et
  absence de relance automatique vérifiés.
- Une nouvelle entrée démarre le partage ; le chargement se retire après la
  confirmation des fenêtres et du premier rendu WebView du panneau.
- La fixture de navigation indisponible laisse un message visible et l’interface
  utilisable. Elle est réactivée à la fin de ce test.
- Les bandes supérieure et inférieure sont mesurées indépendamment depuis le
  contenu natif de la navigation. Leur couverture complète et leurs pixels sont
  comparés au fond sombre attendu. Un appui
  réaffiche les commandes pendant huit secondes. Les masques reviennent ensuite,
  sans modifier les rectangles des applications.
- Les quatre côtés du cadre, les commandes aux coins de la navigation, les
  réglages dans la tâche droite et le retour en plein écran restent vérifiés.
- Le rendu logiciel du chargement est opaque sur trois formats ; deux phases
  d’animation produisent des pixels différents et gardent le bouton immobile.
  Deux dessins de la même phase sont identiques pixel pour pixel.

Les captures ont également été examinées visuellement. Elles ne sont pas
retouchées. La paire native et les bandes sont contrôlées par géométrie et par
captures ; un booléen du service ne suffit pas à valider le résultat visuel.

## Autres contrôles

146 assertions JavaScript et syntaxe ES2019. Tests unitaires Android de géométrie
(7 944 assertions) et de diagnostic. Six tests du parseur de connexion.

Les deux émulateurs reçoivent quatre cœurs si l’hôte en fournit au moins quatre.
Le retour aux réglages conserve une vérification des pixels réellement composés,
avec une attente maximale de quinze secondes ; le temps observé est enregistré.

Android 16 : préparation de l’émulateur avant toute installation RoadDeck, puis trois
échantillons CPU consécutifs inférieurs à 85 %, et application lancée
à froid. Aucun préchargement de RoadDeck ou WebView, aucune suppression des
contrôles ANR/PID. Connexion du service et aller-retour entre l’application et les
réglages, PID stable, aucune ANR ou erreur fatale observée pendant ce parcours.
Ce contrôle court ne teste ni le partage Android 16 ni tous les micrologiciels.

## Limites

Android conserve les barres natives et l’espace réservé. L’habillage les masque
visuellement ; Waze n’est pas redimensionné pour récupérer cet espace. Un appui
sur la bande ou le bouton Android rend les commandes accessibles.

Le démarrage après mise sous tension dépend de la sélection de RoadDeck comme
accueil et du comportement du constructeur. Le test automatise une entrée du
lanceur ; aucun démarrage physique d’autoradio n’a été effectué.

La présence des deux fenêtres et le rendu du panneau ne prouvent pas que Waze
a fini son téléchargement de cartes, son identification ou le calcul d’un trajet.
Les comptes musicaux, le portail Tuya réel et les réseaux en conduite ne sont
pas testés par cette mise à jour. Aucun identifiant Tuya n’est intégré.

Les preuves des versions précédentes restent dans leurs répertoires nommés.
Les éventuelles tentatives échouées de cette version sont consignées dans
`BUILD_VERIFIE.json`, sans être présentées comme des succès.
