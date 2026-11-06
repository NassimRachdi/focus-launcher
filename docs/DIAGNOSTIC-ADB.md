# Diagnostic ADB — son Bluetooth

Objectif : comprendre pourquoi le bouton **Musique BT** de l'accueil ne donne pas le son alors que le
bouton **Essai : son Bluetooth** de l'écran Diagnostic (même appel `MtcAudio.enterBluetooth`) le donne.

Règles : **lecture seule sur l'autoradio** (pas de modification de `/system`, pas de flash, pas de
suppression d'applis). Les seules commandes qui agissent sont les paramètres audio documentés
`av_channel_enter=gsm_bt` / `av_channel_enter=sys`.

## 1. Connexion ADB par Wi-Fi

PC Windows, outils dans `C:\Users\nassi_hs6y4t9\Downloads\platform-tools` (`adb.exe`).
PC et autoradio sur le **même Wi-Fi** (partage de connexion de l'iPhone).

Sur l'autoradio : appui long sur **Musique BT** → Diagnostic → **Activer ADB Wi-Fi (5555)**.
L'adresse IP est affichée en haut de l'écran (ligne « IP »). Dernière adresse vue : `10.235.131.195`
(elle change selon le réseau).

```
adb connect <IP>:5555
adb devices          → doit afficher <IP>:5555  device
```

Si SuperSU demande l'accès root pour « Shell », accepter avec « Toujours autoriser ».

## 2. Informations à collecter

```
adb shell getprop > props.txt
adb shell ps > ps.txt
adb shell dumpsys audio > audio.txt
adb shell dumpsys media_session > media_session.txt
adb shell su -c dmesg > dmesg.txt
```

Applis d'origine (à décompiler avec jadx, lecture seule) :

```
adb shell "mkdir -p /sdcard/diag; for p in com.microntek.bluetooth android.microntek.mtcser android.microntek.service com.sevenfloor.mtcsound com.mvgv70.xposed_mtc_bt; do f=$(pm path $p); f=${f#package:}; cat $f > /sdcard/diag/$p.apk; done; ls -l /sdcard/diag"
adb pull /sdcard/diag diag
adb pull /system/lib/hw hw          (HAL audio : audio.primary.*.so)
```

## 3. Journal pendant un essai réel

```
adb logcat -c
adb logcat -v time > logcat.txt
```

Sur l'autoradio, 10 s entre chaque étape, noter l'heure et « son oui/non » :
1. Musique lancée sur l'iPhone.
2. **Musique BT** sur l'accueil.
3. Appui long sur Musique BT → **Essai : son Bluetooth**.
4. Appli Bluetooth → onglet musique.
5. Home.
6. MODE (radio).

Ctrl+C, puis `adb shell su -c dmesg > dmesg2.txt` (le pilote écrit « --mtc enter <canal> » à chaque bascule).

## 4. Ce qu'il faut chercher

- Dans `logcat.txt`, à l'heure de l'étape 2 : la ligne `AudioFlinger: Request requires
  android.permission.MODIFY_AUDIO_SETTINGS` (permission refusée) ou toute ligne `av_channel`.
  Le launcher (`fr.nassim.focuslauncher`) écrit ses toasts ; vérifier que `openBtMusic` est bien appelé
  (ajouter un `Log.i("FocusBT", …)` si nécessaire).
- Dans `dmesg2.txt` : une ligne `--mtc enter gsm_bt` doit apparaître à chaque bascule (étapes 2, 3, 4).
  Si elle manque à l'étape 2 mais existe à l'étape 3, la commande n'arrive pas au pilote depuis l'accueil.
  Si elle existe aux deux mais sans son à l'étape 2, quelque chose rebascule juste après : chercher dans
  le logcat un `av_channel_enter=sys` / `av_channel_exit` émis par `android.microntek.service`,
  `com.microntek.*` ou un module Xposed (`mvgv70`) quand le launcher est au premier plan.
- Dans l'APK `com.microntek.bluetooth` (jadx) : `MusicFragment` et tout ce qui entoure `setParameters(` ou
  `av_channel` ; noter l'ordre exact des appels (par ex. `av_channel_exit=…` puis `av_channel_enter=gsm_bt`,
  ou commandes vers `android.microntek.mtcser` comme `avPlay`).
- Dans `com.sevenfloor.mtcsound` : MTCSound remplace le HAL audio ; vérifier qu'il accepte bien
  `av_channel_enter=gsm_bt` et s'il attend d'autres clés.

## 5. Correction attendue

Reproduire dans `MtcAudio.enterBluetooth` la séquence exacte de `MusicFragment`, puis tester : Musique BT
→ son ; Home → son conservé ; MODE → radio ; retour Musique BT → son. Publier via pull request,
squash merge sur `main`, attendre la coche verte, mettre à jour depuis la tuile « Mises à jour ».
