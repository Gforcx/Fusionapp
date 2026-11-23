# Écran partagé natif — 0.5.0

`SplitScreenService` utilise l’action publique d’accessibilité
`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN`, disponible à partir d’Android 7.
La réussite de la commande système ne suffit pas : le service attend Waze au
premier plan, observe le séparateur et les dimensions réellement réduites,
puis lance `SidePanelActivity` dans la partie libre.

Le panneau a une affinité de tâche distincte du lanceur HOME. Le lancement
utilise `NEW_TASK` sans `LAUNCH_ADJACENT`. Les réglages utilisent
`PanelSettingsActivity` dans la même tâche que le panneau.

La machine d’états attend des observations stables et possède des délais bornés.
Si Android place la fenêtre principale à droite, une seule inversion contrôlée
est essayée. Le succès exige Waze à gauche, RoadDeck à droite, deux fenêtres
réduites et disjointes, et la confirmation multiwindow du panneau.

`SplitGeometry` refuse les fenêtres superposées, hors écran, trop petites,
verticalement incompatibles ou séparées par un grand vide. La seule fenêtre
d’accessibilité de RoadDeck remplit éventuellement le vide exact entre les deux
applications. Sa position réelle est contrôlée avant de la rendre visible.
Elle est non tactile. Aucune capture d’écran ni injection de gestes n’est utilisée.

La fermeture ne désassemble que la paire vérifiée, retire les tâches appartenant
au panneau RoadDeck, puis vérifie Waze en pleine largeur. Le service reste inactif
à sa connexion ; il ne déclenche aucune bascule de sa propre initiative.

La présence des fonctions multiwindow dépend du micrologiciel. L’application
signale un refus ou un délai dépassé et ne remplace jamais ce mode par un panneau
qui recouvre Waze. Les barres système du constructeur restent sous son contrôle.

Références techniques :
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
- https://source.android.com/docs/core/display/multi_display/activity-launch
