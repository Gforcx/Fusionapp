# RoadDeck 0.5.3 — cadre visible autour de Waze

La paire native Waze à gauche / RoadDeck à droite reste en place. La version ajoute un habillage sombre visible, une ouverture arrondie et un contour violet. Le panneau droit conserve son apparence.

- Marges gauche, haute, droite et basse : 12 dp par défaut, réglables de 0 à 40 dans Réglages → Waze. Rayon intérieur de 18 dp et accent de 2 dp.
- Les marges recouvrent les bords de Waze. Elles ne redimensionnent pas son contenu et ne transforment pas Waze en vue intégrée à RoadDeck.
- Fenêtre d’accessibilité passive : aucun toucher intercepté, aucun focus pris, aucune nouvelle autorisation.
- Les petites fenêtres superposées excluent uniquement la partie recouverte. Les fenêtres situées derrière la paire ne suppriment plus tout l’habillage. Les bandes système observées restent protégées. Une grande fenêtre système non identifiable au-dessus de la paire conserve un blocage explicite.
- Le statut natif exige une région peinte non vide et un dessin de la géométrie courante. Les diagnostics indiquent les exclusions, la raison d’un masquage et les positions demandées et obtenues.
- Le test explicite de cinq secondes affiche « Habillage RoadDeck » dans la partie gauche puis retire ce repère. Il ne lance pas Waze et ne déplace pas les fenêtres.
- L’habillage est retiré à la fermeture de la paire et lors de la déconnexion du service.

La photo de l’autoradio confirme le partage natif, pas la cause précise de l’habillage absent. Les captures comparatives de l’émulateur et les résultats d’exécution sont consignés dans BUILD_VERIFIE.json après les essais. La navigation de test est une application factice séparée ; elle n’est jamais livrée dans l’APK.
