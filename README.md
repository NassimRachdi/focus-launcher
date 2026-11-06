# Focus Launcher

Interface d'accueil sur mesure pour l'autoradio Android de la Ford Focus
(plateforme MTCB · Rockchip RK3188 · 1 Go · Android 5.1.1 · 800×480).

- **Accueil** : horloge, lecture en cours, 4 indicateurs OBD, raccourcis.
- **Musique** : titre, artiste, pochette (si la source l'envoie), progression, commandes.
- **Voiture** : température moteur, régime, tension batterie, charge, codes défaut — **lecture seule**.
- **Applis** : les applis déjà installées, renommées et triées ; appui long = infos de l'appli.
- Thème **nuit** (rouge, assorti au tableau de bord) et **jour**.
- **Mise à jour automatique** sans clé USB.

Optimisé pour le RK3188 : aucune dépendance (pas d'AndroidX), vues Android natives, aplats sans
ombre ni flou, aucune animation hors pression des boutons, données OBD à 1 Hz sur un thread séparé.

---

## 1. Compiler l'APK sur GitHub (une seule fois)

1. Crée un dépôt **public** sur GitHub, par exemple `focus-launcher`.
   (Public, car l'autoradio télécharge les mises à jour sans identifiant.)
2. Envoie ce dossier dans le dépôt :
   ```bash
   cd focus-launcher
   git init && git add . && git commit -m "Focus Launcher"
   git branch -M main
   git remote add origin https://github.com/TON_PSEUDO/focus-launcher.git
   git push -u origin main
   ```
3. Dans le dépôt : **Settings → Secrets and variables → Actions → New repository secret**,
   crée les 4 secrets indiqués dans `secrets.txt` (fourni à part, à ne **jamais** mettre dans le dépôt) :
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Onglet **Actions → Compiler l'APK → Run workflow**.
   En 3-4 minutes, une **Release** apparaît avec `focus-launcher.apk` et `version.json`.

Chaque `git push` sur `main` produit ensuite une nouvelle version, que l'écran détecte tout seul.

## 2. Installer sur l'autoradio (sans clé USB)

1. Active le **partage de connexion** de l'iPhone et connecte l'autoradio en Wi-Fi.
2. Ouvre **Navigateur** sur l'écran et va sur :
   `https://github.com/TON_PSEUDO/focus-launcher/releases/latest/download/focus-launcher.apk`
3. Ouvre le fichier téléchargé (ou passe par **ApkInstaller**) et installe.
   Si Android bloque : Paramètres → Sécurité → **Sources inconnues**.
4. Appuie sur le bouton **maison** physique → choisis **Focus Launcher** → **Toujours**.

> Si le navigateur refuse la connexion HTTPS (certificats anciens d'Android 5.1), télécharge l'APK
> sur l'iPhone puis utilise ADB en Wi-Fi depuis un PC (section 5) pour cette toute première fois.

## 3. Afficher la musique en cours

Sur l'accueil, appuie sur **« Activer l'affichage musique »** puis coche **Focus Launcher**.
Si le réglage n'existe pas sur ce firmware, depuis un PC :
```bash
adb shell settings put secure enabled_notification_listeners \
  fr.nassim.focuslauncher/fr.nassim.focuslauncher.MediaListener
```

Ce qui remonte dépend de la source :
| Source | Titre / artiste | Progression | Pochette |
|---|---|---|---|
| Lecteur Musique SD/USB | oui | oui | oui si intégrée au fichier |
| iPhone en Bluetooth | à tester (dépend du module BT MTCB) | à tester | non (limite de l'autoradio) |
| CarPlay (adaptateur) | dans CarPlay | dans CarPlay | oui, dans CarPlay |

Les boutons ⏮ ⏯ ⏭ fonctionnent dans tous les cas (touches média système en repli).

**Son Bluetooth de l'iPhone** : sur cet autoradio, c'est l'appli Bluetooth d'origine qui bascule la
source audio sur Bluetooth. Appuie sur **Musique BT** (dock de l'accueil), sur « Musique Bluetooth »
(page Musique) ou sur « Aucune lecture » : l'appli Bluetooth s'ouvre, le son arrive, puis le bouton
Home ramène au launcher sans couper la musique.

## 4. Données voiture (OBD-II)

L'OBD est **désactivé par défaut**. Appui long sur la carte « Liaison OBD » (page Voiture) :
Désactivé → Bluetooth → Wi-Fi → Désactivé.

1. Branche un dongle **ELM327** (idéalement vLinker, OBDLink ou Vgate) sur la prise OBD sous le volant.
2. **Bluetooth** : appaire-le dans les paramètres Bluetooth Android de l'écran.
   Le nom doit contenir OBD, ELM, V-LINK, OBDLINK ou VGATE.
3. **Wi-Fi** : appui long sur la carte « Liaison OBD » jusqu'au mode Wi-Fi (192.168.0.10:35000).

⚠️ Sur les MTCB, le Bluetooth est souvent géré par un module à part et pas par Android :
teste d'abord avec **Torque Lite**. Si Torque ne voit pas le dongle en Bluetooth, prends un
dongle Wi-Fi (attention : il occupe alors le Wi-Fi, donc plus de partage de connexion en même temps).

L'appli n'envoie **aucune commande d'écriture** au véhicule : uniquement des requêtes de lecture (mode 01).
Niveau d'huile et lave-glace : non disponibles en OBD sur cette Focus.

## 5. Développer en direct (optionnel)

```bash
adb connect IP_DE_L_ECRAN:5555      # si ADB réseau est actif sur le build userdebug
adb install -r focus-launcher.apk
adb logcat | grep -i focus
```
Si l'écran n'est pas en 1 dp = 1 px : `adb shell wm density` doit afficher 160.

## Structure

```
app/src/main/java/fr/nassim/focuslauncher/
  MainActivity.kt   écran, navigation, liaison des données
  MediaListener.kt  lecture de la session média active
  NowPlaying.kt     état de la lecture
  ObdClient.kt      client ELM327 (Bluetooth / Wi-Fi), lecture seule
  AppResolver.kt    recherche des applis de l'autoradio par leur nom
  GaugeView.kt      jauge demi-cercle
  Updater.kt        mise à jour via version.json
.github/workflows/build.yml   compilation + publication automatique
```
