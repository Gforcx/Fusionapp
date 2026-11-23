# RoadDeck 0.5.2 — habillage commun

Le partage natif Android existant est conservé. Le réglage « Habillage commun », activé par défaut, ajoute un habillage graphique passif au-dessus de la paire Waze/RoadDeck mesurée.

- Contour fin de 2 dp et petits coins de 6 dp au maximum. L’habillage ne redimensionne pas la surface de Waze et ne la transforme pas en vue intégrée au launcher.
- Le dessin reste dans les rectangles mesurés des applications et leur raccord ; les surfaces système sont exclues. Clavier, autre application ou fenêtre superposée masquent l’habillage.
- L’overlay d’accessibilité ne reçoit ni les touches ni le focus. Aucun nouveau droit, aucune nouvelle application ni service externe.
- L’habillage disparaît avec la paire et peut être désactivé dans Réglages → Waze.
- Le panneau droit emploie les mêmes couleurs et contours. L’horloge évite les réécritures de texte inchangé.
- Aucun identifiant ou secret Tuya n’est ajouté au code ou à l’APK.

Validation : résultats d’exécution consignés dans BUILD_VERIFIE.json après la compilation et les essais. Les tests de navigation utilisent une application factice séparée, jamais incluse dans l’APK livrée. Le fonctionnement du partage de la version précédente sur l’autoradio Android 10 est un retour utilisateur ; l’habillage de cette version reste à vérifier sur cet autoradio.
