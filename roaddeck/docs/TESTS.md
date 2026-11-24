# Vérification RoadDeck 0.5.3 — cadre visible autour de Waze

## Résultat et changement

La version conserve le partage natif Waze à gauche / RoadDeck à droite. Une fenêtre d’accessibilité passive ajoute quatre marges sombres de 12 dp par défaut, une ouverture arrondie de rayon 18 dp et un accent violet de 2 dp. Le raccord central rejoint le panneau droit. Les marges recouvrent les pixels périphériques de Waze ; sa fenêtre n’est pas redimensionnée par cette décoration.

Réglages → Waze → Habillage autour de Waze permet d’activer le cadre et de régler chaque marge de 0 à 40 dp. « Tester l’habillage (5 s) » ajoute un repère temporaire explicite. Aucune nouvelle autorisation ni application tierce n’est ajoutée.

## Essais exécutés

- Compilation APK versionName 0.5.3 / versionCode 9.
- 7 944 assertions de géométrie et tests JUnit de filtrage des diagnostics réussis.
- 133 assertions JavaScript réussies et syntaxe ES2019 vérifiée. Les commandes de décoration ne lancent ni ne redimensionnent Waze.
- Android 10/API29 : cinq tests instrumentés réussis. Quatre vérifient les pixels du cadre, l’ouverture transparente, une exclusion locale, une exclusion totale, le placement hors des barres système et le repère temporaire.
- Le parcours Android réel de la cinquième épreuve utilise deux activités séparées : lancement par toucher sur le bouton visible, redimensionnement natif, clics aux quatre coins sous l’overlay, quatre marges visibles, cadre désactivé/réactivé sans déplacement, expiration du repère, réglages dans la tâche droite et retour au plein écran avec retrait de l’habillage.
- Les captures émulateur avec et sans cadre sont comparées. Huit pixels de largeur sur chacun des quatre côtés présentent le fond sombre ; le centre de la surface gauche reste inchangé. Les captures ont aussi été examinées visuellement.
- Android 16/API36 : connexion du service, ouverture de l’activité, passage par les réglages Android puis retour. PID stable et aucune ANR, exception fatale Java ou marqueur de crash natif observé pendant ce parcours court. Ce test ne valide pas le partage sur Android 16.
- Signature APK v2 et alignement vérifiés. Certificat identique à la livraison précédente.

Les résultats, captures sans retouche, empreintes et détails des jobs sont dans docs/verification-0.5.3/ et BUILD_VERIFIE.json.

Le repère temporaire est vérifié par les rendus bitmap et par son état natif avec expiration. La capture split-frame-preview.png, prise immédiatement après son activation, ne montre pas le repère et ne confirme donc pas visuellement le badge. Elle est conservée comme capture brute, sans être utilisée comme preuve de son affichage. Les captures avec/sans cadre et leur comparaison sont les preuves du contour visible.

## Limites exactes

Les émulateurs installent la variante debug issue du commit de sources indiqué dans BUILD_VERIFIE.json. L’APK release du même commit est signée et contrôlée séparément ; ce binaire signé n’a pas été installé pendant ces essais.

L’application de navigation utilisée pour les tests est une fixture Android distincte, explicitement marquée testOnly. Ce n’est pas Waze ; elle n’est jamais incluse dans l’APK livrée. Waze réel et l’autoradio du client ne sont pas disponibles ici. Le client a confirmé le partage de la version précédente sur son Android 10. Le nouveau cadre reste à observer sur cet appareil.

La cause exacte de l’absence de l’ancien contour sur sa photo n’a pas été établie. La version corrige plusieurs causes possibles : exclusions de fenêtres situées derrière la paire et suppression globale à cause de petites superpositions. Les bandes système observées restent protégées. Une grande fenêtre système au-dessus des applications dont la nature n’est pas déterminable conserve un blocage expliqué dans le diagnostic.

Le statut « Habillage dessiné » exige un dessin de la géométrie courante avec une région peinte positive autour de Waze. Il ne constitue pas, seul, une preuve du résultat final du compositeur. Les captures couvrent cette vérification sur l’émulateur.

Aucun identifiant Tuya n’est intégré au code ou à l’APK. Le portail physique, les comptes musicaux et la radio en déplacement n’ont pas été testés dans cette livraison.

## Documentation Android consultée

- [Ordre des couches des fenêtres](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getLayer()).
- [Overlay d’accessibilité et passage des touchers](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE).
- [Limites stables des barres système](https://developer.android.com/reference/android/view/WindowInsets#getStableInsetTop()).

## Observations pendant la CI

Premier passage (45434843149, commit ad3b7abb) : le test visuel a révélé que les rectangles des applications incluaient les barres Android. Les marges haute et basse étaient donc effacées. Le dessin a été corrigé pour entourer la surface disponible entre ces bandes, sans changer les dimensions des tâches.

Deuxième passage (45436602276, commit 28842f90) : le parcours natif Android 10 complet a réussi. Le seul échec Android 10 concernait une comparaison bitmap trop stricte : 56 pixels d’arrondis variaient d’au plus 1 niveau alpha et 2 niveaux RGB prémultipliés. Aucun intérieur uniforme ne changeait. Le test final tolère uniquement ce lissage sur des voisinages non uniformes, avec silhouette inchangée ; il rejette toujours les pixels ajoutés au centre et les déplacements.

Le deuxième passage Android 16 a observé une ANR réelle de MainActivity, dans WebView / OpenGL émulé, avec CPU à 99 % et forte pression mémoire. Waze et le cadre n’étaient pas lancés. Le symptôme avait déjà été conservé dans les preuves 0.5.1. L’émulateur final a reçu 4 Gio de mémoire après vérification de la capacité disponible du runner, en gardant le démarrage à froid, les délais et les contrôles ANR/PID. Le dernier contrôle de 37,322 secondes a réussi ; il ne démontre pas la disparition de ce problème sur tous les appareils.
