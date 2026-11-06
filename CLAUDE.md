# Focus Launcher — contexte projet

Launcher Android sur mesure (« CarPlay maison ») pour l'autoradio de la **Ford Focus 2008** de Nassim.
Langue de travail : **français**. Style : professionnel, factuel, sans emphase.

## Matériel cible (vérifié sur l'écran « À propos »)

| Élément | Valeur |
|---|---|
| Autoradio | Android chinois 7" aftermarket, façade Ford, lecteur DVD, marque **Eonon** (réf. vue sur la façade : 5161) |
| Plateforme | **MTCB** — MCU `MTCB-ZD-V2.79` (Sep 10 2015) |
| SoC | Rockchip **RK3188**, ARM Cortex-A9 x4 @1,6 GHz |
| RAM | **1 Go** |
| Android | **5.1.1** (API 22), build `rk3188-userdebug 26122016`, noyau 3.0.101+ |
| Écran | **800×480**, mdpi (1 dp = 1 px) |
| Root | **oui** (userdebug, confirmé par l'utilisateur) |
| Applis d'origine | ApkInstaller, AV en, Bluetooth (MTC), Car Launcher, DisableServ, DVD, Explorateur, Headunit (Reloaded), Key Study, mtcxp, Musique, Navigateur, Navigation, Paramètres, Radio, Vidéo |

Téléphone de l'utilisateur : **iPhone**, musique sur **Apple Music** (pas Spotify).

## Contraintes et décisions

- **Performance d'abord** (RK3188 / 1 Go) : aucune dépendance (pas d'AndroidX, `android.useAndroidX=false`),
  vues Android natives, aplats sans ombre ni flou, pas de transition entre pages, données à 1 Hz,
  `GaugeView` sans allocation dans `onDraw`.
- `minSdk 21`, **`targetSdk 22`** volontairement (pas de permissions à l'exécution, `file://` autorisé pour l'auto-update).
- Design validé par l'utilisateur : maquette HTML 800×480 (accueil / musique / voiture / applis, thème nuit rouge
  assorti au tableau de bord + thème jour). Ne pas changer la direction visuelle sans le lui demander.
- **Apple Music** : pas d'API « en cours de lecture » → la pochette n'est pas récupérable via l'écran.
  Solution retenue pour la musique riche : **adaptateur CarPlay sans fil USB** (Carlinkit/Autokit/ZLink) ;
  le bouton CarPlay cherche ces applis par nom.
- **Spotify** ne tourne pas sur l'écran (exige Android 7+).
- Applis de l'autoradio retrouvées **par libellé** (`AppResolver`), car les noms de paquets MTCB varient.
- **OBD : lecture seule** (mode 01 + `ATRV`), aucune commande d'écriture vers le véhicule. Ne jamais en ajouter.
  Niveau d'huile / lave-glace : non disponibles en OBD sur cette voiture.
- Installation et mises à jour **sans clé USB** : GitHub Actions compile → Release → `Updater` lit `version.json`.

## Dépôt et compilation

- Dépôt public : https://github.com/NassimRachdi/focus-launcher (branche `main`)
- `.github/workflows/build.yml` : à chaque push sur `main`, compile `assembleRelease` (Gradle 8.9, JDK 17,
  AGP 8.5.2, Kotlin 1.9.24), signe, publie une Release `v<run_number>` avec `focus-launcher.apk` + `version.json`.
  `versionCode` = numéro d'exécution du workflow.
- Signature : secrets GitHub `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (=focus), `KEY_PASSWORD`.
  Le keystore et ses mots de passe sont dans `focus-launcher-secrets.txt`, **hors dépôt**. Ne jamais les committer.
  **Garder toujours la même clé**, sinon Android refuse la mise à jour.
- Pas de SDK Android local nécessaire : la CI est la source de vérité pour la compilation.
  Si un SDK est disponible localement : `gradle :app:assembleDebug`.

### Erreurs déjà rencontrées et corrigées
1. `resource style/T not found` → un style `T.Label` hérite implicitement de `T` ; corrigé avec `parent=""`.
   Toute nouvelle déclaration de style avec un point dans le nom doit avoir un `parent` explicite.
2. `Keystore was tampered with, or password was incorrect` → espaces collés dans les secrets ;
   corrigé par `.trim()` sur les variables d'environnement dans `app/build.gradle.kts`.

## Architecture

```
app/src/main/java/fr/nassim/focuslauncher/
  MainActivity.kt   launcher (HOME), navigation 4 pages, liaison UI, thème jour/nuit (recreate)
  MediaListener.kt  NotificationListenerService → MediaSessionManager (titre, artiste, pochette, position)
  NowPlaying.kt     état de lecture partagé (thread UI) + écouteurs
  ObdClient.kt      client ELM327 Bluetooth (SPP) ou Wi-Fi (192.168.0.10:35000), thread dédié, reconnexion 5 s
  AppResolver.kt    applis installées par libellé, renommage, masquage (DisableServ, mtcxp, Car Launcher)
  GaugeView.kt      jauge demi-cercle
  Updater.kt        version.json → téléchargement APK → installeur
app/src/main/res/layout/activity_main.xml   tout l'écran (800×480)
```

Repli média : sans `MediaController`, les boutons envoient des touches média système (`dispatchMediaKeyEvent`).

## État actuel (v4, installée et fonctionnelle dans la voiture)

✅ Compilation, signature, publication OK. Installé, défini comme launcher par défaut. Rendu conforme à la maquette.

### 🔴 Bug prioritaire : Bluetooth audio
Depuis le passage au nouveau launcher : **l'iPhone apparaît « Connecté » à l'autoradio, mais aucun son ne sort.**
L'écran Musique affiche « Aucune lecture ».

Hypothèses, par ordre de probabilité :
1. **Source audio MTCB non basculée sur Bluetooth.** Sur MTCB, le canal audio est commuté par le système MTC
   (bouton MODE, appli Bluetooth d'origine, ancien launcher). Notre launcher envoie des touches média mais ne
   change jamais la source. Test : ouvrir l'appli « Bluetooth » d'origine → onglet musique, ou appuyer sur MODE.
2. **Conflit de `ObdClient`** avec le module Bluetooth MTC : il appelle `BluetoothAdapter` toutes les 5 s
   (`bondedDevices`, `cancelDiscovery`), alors qu'aucun dongle n'est appairé.
3. Effet de bord du partage de connexion iPhone utilisé pour l'installation (peu probable).

Plan de diagnostic (l'appareil est rooté, ADB possible en Wi-Fi ou USB) :
- `adb shell pm list packages | grep -i -E "microntek|mtc|bt|bluetooth"` → identifier les paquets MTC.
- `adb shell dumpsys media_session` → voir si l'appli Bluetooth MTC publie une MediaSession.
- `adb logcat | grep -i -E "mtc|microntek|bluetooth|a2dp|focuslauncher"` pendant une lecture depuis l'iPhone.
- Test d'isolement : remettre l'ancien launcher (Paramètres → Applications → Focus Launcher → Effacer les valeurs
  par défaut) et vérifier si le son revient.
- Ne pas deviner d'intents/broadcasts MTC privés : les confirmer d'abord dans logcat ou en décompilant
  l'appli MTC présente sur l'appareil.

### À faire ensuite
- [ ] Corriger le bug audio Bluetooth (ci-dessus).
- [ ] **OBD désactivé par défaut** (option à activer quand le dongle sera acheté) — aucun dongle pour l'instant.
- [ ] Accueil : la **date sous l'horloge est rognée** (horloge 64sp trop haute pour la carte) et les **durées
      0:00 du lecteur de l'accueil sont tronquées** en bas de la carte. Réduire les tailles ou les marges.
- [ ] Si l'appli Bluetooth MTC ne publie pas de MediaSession : récupérer titre/artiste par ses broadcasts (via root/logcat).
- [ ] Ajouter Focus Launcher à la liste blanche MTCB (applis non tuées à la coupure du contact).
- [ ] Tester l'adaptateur CarPlay (non encore acheté).

## Méthode de travail avec l'utilisateur
- Il modifie le dépôt via l'interface web GitHub ou en local, puis vérifie l'onglet **Actions**.
- Donner des étapes courtes et numérotées ; une erreur à la fois ; demander le bloc « What went wrong » du log.
- Toujours vérifier dans le code avant d'affirmer ; signaler clairement ce qui n'a pas pu être testé.
