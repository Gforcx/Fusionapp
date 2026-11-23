# Navigation de test — fixture CI uniquement

Ce module fournit une application Android native factice, clairement étiquetée
« Navigation de test ». Son package `com.waze` permet de tester le placement de
deux véritables fenêtres Android sans installer Waze. Elle ne contient aucune
carte, navigation, donnée ou code de Waze. Ses quatre boutons de coin possèdent
des compteurs indépendants pour vérifier le redimensionnement et les touchers.

Le module est exclu des builds ordinaires. Il faut l'activer explicitement :

```sh
./gradlew -ProaddeckFixtures=true :fixture:assembleDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t fixture/build/outputs/apk/debug/fixture-debug.apk
```

Le manifeste porte `android:testOnly="true"`. Installer cette fixture uniquement
dans l'émulateur de test, jamais sur l'autoradio ni sur un appareil contenant
Waze. Ne jamais distribuer son APK avec RoadDeck.

Le test `fr.roaddeck.launcher.SplitScreenAndroid10Test` nécessite Android 10/API29
en paysage et le service `fr.roaddeck.launcher/.SplitScreenService` activé par la
CI avant l'instrumentation. Il conserve les services d'accessibilité pendant
UiAutomator, vérifie les bounds des fenêtres, clique les quatre coins, ouvre et
ferme les réglages dans la tâche du panneau, puis vérifie le retour plein écran.

Les captures `split-android10.png` et `split-settings.png` se trouvent dans le
répertoire externe de fichiers de RoadDeck. `split-test-state.json` conserve le
dernier snapshot du moteur et `split-failure.png` aide au diagnostic en cas
d'échec. Ces tests de fixture ne démontrent pas la compatibilité du vrai Waze
ni celle du firmware d'un autoradio donné.
