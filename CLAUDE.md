# Focus Launcher — contexte projet

Launcher Android sur mesure pour l'autoradio de la **Ford Focus 2008** de Nassim.
Langue de travail : **français**. Style : professionnel, factuel, sans emphase. Étapes courtes et numérotées.
Toujours vérifier dans le code avant d'affirmer ; dire clairement ce qui n'a pas pu être testé.

## Objectif (reformulé par l'utilisateur)
Une interface plus belle que le Car Launcher d'origine, **sans rien perdre de ce qui marchait**, en
particulier la **musique Bluetooth de l'iPhone**. CarPlay n'est plus un objectif (un adaptateur USB
Carlinkit resterait possible plus tard ; DiPlay/CarLink exigent Android 7+ et sont exclus).

## Matériel (vérifié sur l'écran « À propos » et par le diagnostic)

| Élément | Valeur |
|---|---|
| Autoradio | Eonon 5161 (façade Ford), modèle « S07 », plateforme **MTCB**, MCU `MTCB-ZD-V2.79` (Sep 10 2015) |
| SoC / RAM | Rockchip **RK3188** Cortex-A9 x4 @1,6 GHz, **1 Go** |
| Android | **5.1.1** (API 22), build `rk3188-userdebug 26122016`, noyau 3.0.101+ |
| Écran | **800×480** ; densité annoncée > 160 (le launcher force 160 dans `attachBaseContext`) |
| Root | **oui** (SuperSU installé, `su -c id` → uid=0) |
| Bluetooth | **module MTC séparé** : le Bluetooth Android est éteint (`BluetoothAdapter STATE_OFF`) |
| Appli Bluetooth d'origine | `com.microntek.bluetooth/.BlueToothActivity` (seule activité ouvrable ; la musique est un onglet interne `MusicFragment`, `cfg_bt=6`) |
| Autres paquets MTC | `android.microntek.service` (MicrontekServer), `android.microntek.mtcser` (service BT), `canbus`, `radio`, `music`, `dvd`, … |
| Modifs déjà présentes | Xposed Installer + modules **mvgv70** (`xposed_mtc_bt`, `_manager`, `_music`, `_radio`, `_poweramp`, `_statusbar`), `com.sevenfloor.mtcsound` (**MTCSound**, HAL audio alternatif), `com.petrows.mtcservice`, `com.dsa.mtcxp`, Titanium Backup, Root Explorer, Quick Reboot |
| Façade | boutons PWR/VOL, HOME, **MODE**, MUTE, NAVI, mini-USB ; à droite : TUNE, éjection, lecture, piste ± |

Téléphone : **iPhone**, musique sur **Apple Music**. PC de l'utilisateur : **Windows** (`C:\Users\nassi_hs6y4t9\Downloads\platform-tools`).

## Son Bluetooth : ce qui est établi
- Le son de l'iPhone arrive sur une entrée du processeur audio ; c'est le **pilote noyau MTC** qui
  sélectionne la source. Commande : `AudioManager.setParameters("av_channel_enter=gsm_bt")`
  (retour à Android : `av_channel_enter=sys`). Jetons confirmés dans le pilote rétro-documenté
  `amper128/mtc_drivers` (`car.c`, printk « --mtc enter %s ») et dans des applis MTC publiques.
- **Permission obligatoire : `MODIFY_AUDIO_SETTINGS`** (sans elle AudioFlinger renvoie -1 sans exception). Ajoutée en v10.
- `AudioManager.getParameters("av_channel")` renvoie vide sur ce firmware (lecture du canal non disponible).
- **Vérifié dans la voiture (v11)** : le bouton « Essai : son Bluetooth » de l'écran Diagnostic **donne le son**.
  L'utilisateur rapporte que le bouton « Musique BT » de l'accueil, qui appelle la même fonction
  `MtcAudio.enterBluetooth`, **ne le donne pas** : à confirmer et à expliquer (voir `docs/DIAGNOSTIC-ADB.md`).
- Ouvrir l'appli Bluetooth d'origine → onglet musique donne aussi le son, qui continue après Home.
- Le bouton MODE de la façade change de source (radio, etc.).

## Contraintes et décisions
- **Performance d'abord** : aucune dépendance (pas d'AndroidX, `android.useAndroidX=false`), vues
  natives, pas d'animation, données à 1 Hz, `GaugeView` sans allocation dans `onDraw`.
- `minSdk 21`, **`targetSdk 22`** volontairement (permissions à l'installation, `file://` autorisé).
- Design validé par l'utilisateur (maquette 800×480, thème nuit rouge + thème jour). Ne pas changer sans lui demander.
- **OBD : lecture seule** (mode 01 + `ATRV`), jamais d'écriture vers le véhicule. OBD **désactivé par défaut** (pas de dongle).
- Ne rien modifier dans `/system` de l'autoradio ; ne pas toucher aux modules Xposed existants ; n'envoyer
  que des paramètres audio documentés (`av_channel_enter/exit`).
- Applis de l'autoradio retrouvées **par libellé** (`AppResolver`).

## Dépôt, compilation, mise à jour
- Dépôt public : https://github.com/NassimRachdi/focus-launcher (branche `main`).
- `.github/workflows/build.yml` : chaque push sur `main` compile `assembleRelease` (Gradle 8.9, JDK 17,
  AGP 8.5.2, Kotlin 1.9.24), signe, publie une Release `v<run_number>` avec `focus-launcher.apk` + `version.json`.
  `versionCode` = numéro d'exécution. Pas de SDK Android local : **la CI est la source de vérité**.
- Signature : secrets GitHub `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (=focus), `KEY_PASSWORD`.
  Keystore et mots de passe dans `focus-launcher-secrets.txt`, **hors dépôt**. **Toujours la même clé.**
- Mise à jour depuis l'autoradio : page Applis → tuile « Mises à jour » → téléchargement puis **installation
  silencieuse par root** (`pm install -r`), repli sur l'installeur Android. Fonctionne depuis la v9
  (certificats racines récents embarqués dans `res/raw/extra_roots.pem`, voir `Tls.kt`).
- Méthode de travail : branche `claude/…` → pull request → squash merge dans `main` → attendre la coche verte.

### Erreurs déjà rencontrées et corrigées
1. `resource style/T not found` → style `T.Label` sans parent explicite ; tout style avec un point doit avoir `parent=""`.
2. `Keystore was tampered with` → espaces dans les secrets ; `.trim()` dans `app/build.gradle.kts`.
3. `Trust anchor for certification path not found` → magasin de certificats de 2016 ; racines embarquées (v9).
4. Bascule audio sans effet → `MODIFY_AUDIO_SETTINGS` manquante (v10).

## Architecture
```
app/src/main/java/fr/nassim/focuslauncher/
  MainActivity.kt   launcher (HOME), 4 pages, dock (« Musique BT » = MtcAudio.enterBluetooth, appui long = DiagActivity),
                    thème jour/nuit, densité forcée à 160
  MtcAudio.kt       bascule de la source audio MTC (av_channel_enter=gsm_bt / sys), code retour AudioFlinger
  DiagActivity.kt   écran Diagnostic : essais gsm_bt/sys, journal noyau (dmesg), capture logcat root,
                    activation ADB Wi-Fi (port 5555), IP, paquets MTC, rapport dans Download/
  MediaListener.kt  NotificationListenerService → MediaSessionManager (titre, artiste, pochette)
  NowPlaying.kt     état de lecture partagé
  ObdClient.kt      client ELM327 Bluetooth/Wi-Fi, lecture seule
  AppResolver.kt    applis par libellé, renommage, masquage
  GaugeView.kt      jauge demi-cercle
  Updater.kt        version.json → APK → pm install -r (root) ou installeur
  Tls.kt            magasin de confiance = racines Android + res/raw/extra_roots.pem
app/src/main/res/layout/activity_main.xml   tout l'écran (800×480)
docs/DIAGNOSTIC-ADB.md                      procédure ADB Wi-Fi et analyse en cours
```

## Historique des versions
- v4 : première version installée (interface conforme à la maquette).
- v5 (#1) : « Musique BT » à la place de CarPlay, OBD désactivé par défaut, densité 160.
- v6 (#2) : mise à jour par root, erreurs explicites.
- v7 (#3) : écran Diagnostic.
- v8 (#4) : bascule directe `av_channel_enter=gsm_bt`.
- v9 (#5) : certificats racines → la mise à jour depuis l'autoradio fonctionne.
- v10 (#6) : permission `MODIFY_AUDIO_SETTINGS` → la bascule fonctionne (bouton d'essai).
- v11 (#7) : activation ADB Wi-Fi depuis le Diagnostic.

## À faire ensuite
- [ ] Comprendre pourquoi « Musique BT » (accueil) ne donne pas le son alors que « Essai : son Bluetooth » le donne
      (même appel). Pistes : vérifier avec `adb logcat` que le clic envoie bien la commande ; regarder si
      MicrontekServer ou un module Xposed rebascule le canal quand le launcher est au premier plan ; comparer
      avec ce que fait `MusicFragment` de `com.microntek.bluetooth` (décompiler l'APK, voir docs/DIAGNOSTIC-ADB.md).
- [ ] Une fois la bascule fiable : la faire automatiquement quand une lecture Bluetooth commence, et revenir
      sur `sys` quand un lecteur Android joue (ne pas casser la radio ni MODE).
- [ ] Titre/artiste de la musique Bluetooth : l'appli MTC ne publie sans doute pas de MediaSession ;
      voir ses broadcasts (`com.microntek.bt.report` ?) dans le logcat.
- [ ] Liste blanche MTCB (appli non tuée à la coupure du contact).
- [ ] Vérifier le rendu de l'accueil avec la densité forcée (date, boutons du lecteur).
