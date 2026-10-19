# Treadmill FTMS — pilotage de tapis de course Android

Application Android (**minSdk 24 = Android 7.0**, targetSdk 35) qui prend le contrôle d'un tapis de course
Bluetooth LE via le profil standard **FTMS** (Fitness Machine Service, `0x1826`).

## Fonctions

| | |
|---|---|
| Pilotage | vitesse, inclinaison, démarrage / pause / arrêt (Control Point `0x2AD9`) |
| Limites machine | lecture de `Supported Speed Range` (`0x2AD4`) et `Supported Inclination Range` (`0x2AD5`) à la connexion ; toutes les consignes sont bornées et alignées sur le pas du tapis |
| Cardio | champ FC du tapis, ou ceinture/bracelet BLE standard (`0x180D`), prioritaire |
| Durée / distance | chrono de séance, distance (tapis, sinon intégrée), allure, moyennes |
| Séances programmées | plans à paliers (durée / vitesse / pente), planification à une date, récurrence hebdomadaire, « séances du jour » |
| Robustesse | service au premier plan (écran éteint), reconnexion automatique, écran maintenu allumé |

Sans tapis connecté, une séance démarre en **mode simulé** : les consignes tiennent lieu de mesures (chrono, vitesse, distance).
Toucher une séance (du jour, du calendrier ou un plan) la lance directement.

## Écrans

1. **Aujourd'hui** — connexion tapis + cardio, limites lues, séances du jour, course libre.
2. **Entraînements** — plans (créateur à paliers avec profil graphique), calendrier, programmation.
3. **Historique** — séances réalisées et totaux.
4. **Séance** (épuré, plein écran noir) — vitesse, pente, FC, temps restant, profil avec palier courant, +/− direct, pause / stop / palier suivant.

## Architecture

Single-module, MVVM + flux unidirectionnel, 100 % Kotlin / Jetpack Compose, injection manuelle (`AppContainer`).

```
┌──────────────────────────── ui (Compose, Material 3) ────────────────────────────┐
│ HomeScreen  PlannerScreen  PlanEditorScreen  HistoryScreen   WorkoutScreen (épuré) │
│      │ ViewModels (StateFlow)  — HomeVM, PlannerVM, PlanEditorVM, HistoryVM        │
└──────┼───────────────────────────────────────────────────────────────┬────────────┘
       │                                                               │ collect(ui)
┌──────▼──────────── domain ─────────────┐   ┌──────── service ───────▼────────────┐
│ WorkoutEngine : machine à états        │   │ WorkoutService (foreground,         │
│  Idle→Running⇄Paused→Finished          │   │ connectedDevice) : garde le process │
│  déroule les paliers, borne les        │   │ et la liaison BLE en vie, notif.    │
│  consignes, agrège les stats           │   └─────────────────────────────────────┘
└──────┬───────────────────┬─────────────┘
       │                   │
┌──────▼─────── ble ───────┐   ┌──────────── data (Room) ────────────┐
│ FtmsClient  HeartRateClient│  │ plans · steps · schedule · sessions │
│ BleGatt (file d'opérations │  │ TrainingRepository (+ plans par     │
│  GATT sérialisées, suspend)│  │ défaut au premier lancement)        │
│ BleScanner  FtmsParser(pur)│  └─────────────────────────────────────┘
└────────────────────────────┘
```

Choix clés :
- **`AppContainer`** (dans `Application`) porte le client BLE et le moteur : la séance survit à la rotation et à la fermeture de l'écran ; les ViewModels n'observent que des `StateFlow`.
- **`BleGatt`** résout le point dur d'Android BLE (une opération GATT à la fois) avec un `Mutex` + `CompletableDeferred` par opération ; le code FTMS lit comme du code séquentiel.
- **`FtmsParser`** est du Kotlin pur, couvert par des tests JVM (`FtmsParserTest`).
- Le `WorkoutService` n'héberge pas la logique : il ne fait que maintenir le processus et la notification.
- Navigation : l'écran de séance s'ouvre/se ferme automatiquement selon l'état du moteur.

## Compiler / installer

Prérequis : JDK 17 et Android SDK (API 35).

```bash
./gradlew testDebugUnitTest      # tests du parseur FTMS
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Le workflow GitHub Actions (`.github/workflows/android.yml`) produit l'APK en artefact et le publie dans une Release GitHub à chaque push. Liens permanents vers la dernière version :
`https://github.com/feanor91/Training/releases/latest/download/treadmill-ftms.apk` (téléphone) et `.../treadmill-ftms-montre.apk` (montre).
Autorisations demandées au premier scan : Bluetooth (Android 12+) ou Localisation (Android 7–11, exigée par le scan BLE), notifications (Android 13+).

## Compatibilité tapis

Testé uniquement contre la spécification FTMS 1.0 (trames de référence dans les tests) — pas encore sur matériel réel.
Séquence utilisée : *Request Control → Start → Set Target Speed / Inclination*. Les tapis qui n'envoient pas d'indication au Control Point sont tolérés (timeout 3 s = succès).

## Pistes suivantes

Zones cardio et paliers pilotés par la FC, import de parcours (GPX / Kinomap), export FIT/TCX vers Strava, widget, Wear OS, réécriture en multi-module.
