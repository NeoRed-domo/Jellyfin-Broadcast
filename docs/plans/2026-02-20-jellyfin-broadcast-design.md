# Jellyfin Broadcast — Design Document

**Date:** 2026-02-20
**Stack:** Kotlin natif Android, API 24+, Media3/ExoPlayer, Ktor, Jellyfin SDK

---

## Vue d'ensemble

Application Android ultra-légère faisant office de récepteur Jellyfin. Elle affiche un écran noir ou lit un média qu'un client Jellyfin lui envoie à distance. Un seul APK couvre AndroidTV et smartphone/tablette, avec un comportement adapté au type d'appareil détecté au runtime.

---

## Architecture générale

Un APK unique détecte le mode au démarrage via `UiModeManager.currentModeType` :
- `UI_MODE_TYPE_TELEVISION` → mode TV
- Sinon → mode phone/tablette

### Mode TV (AndroidTV)
- Affiche un QR code au premier lancement (ou si non configuré)
- Lit les médias envoyés par n'importe quel client Jellyfin
- Répond aux commandes de la télécommande
- Expose un serveur Ktor local pour recevoir la configuration

### Mode Phone/Tablette
- Affiche également un QR code (l'appareil peut aussi être récepteur)
- Long press sur le QR code → menu :
  - **Configurer cet appareil** : ouvre le formulaire de config local
  - **Scanner un QRcode** : lance la caméra pour scanner une TV et la configurer

### Structure des modules
```
jellyfin-broadcast/
├── app/
│   ├── src/main/java/com/jellyfinbroadcast/
│   │   ├── core/          # SDK Jellyfin, session, player
│   │   ├── tv/            # UI AndroidTV (QR, player, remote)
│   │   ├── phone/         # UI phone (scanner, formulaire config)
│   │   ├── server/        # Ktor local server
│   │   └── discovery/     # NsdManager mDNS
│   └── res/values/colors.xml   # Couleurs Jellyfin
└── docs/plans/
```

---

## Flux de configuration

### QR code
- Contenu : `http://192.168.x.x:8765` (IP locale + port fixe, défaut 8765)
- Généré avec `zxing-android-embedded` au démarrage du serveur Ktor
- Si le port 8765 est occupé : essai 8766, 8767... jusqu'à 8775, QR code mis à jour

### Découverte automatique du serveur Jellyfin
- Au démarrage (mode TV non configuré), `NsdManager` cherche un serveur Jellyfin sur le réseau local
- Timeout : 5 secondes, non bloquant
- Si trouvé : pré-remplit le champ IP/host dans le formulaire de config
- Si non trouvé : champs vides, saisie manuelle

### Séquence
1. Téléphone scanne le QR code de la TV
2. Téléphone affiche le formulaire (IP, port, utilisateur, mot de passe)
3. Téléphone envoie `POST /configure` au serveur Ktor de la TV
4. TV valide les credentials via Jellyfin SDK
5. Si valide : TV s'enregistre comme session distante Jellyfin → écran noir en attente
6. Si invalide : erreur 401 renvoyée au téléphone

---

## Session Jellyfin & lecture

### Enregistrement de session
- La TV s'enregistre via `jellyfin-sdk-kotlin` avec `DeviceInfo` (nom : `"Jellyfin Broadcast - [nom TV]"`)
- Connexion WebSocket permanente vers le serveur Jellyfin
- Commandes écoutées : `Play`, `Pause`, `Seek`, `Stop`, `PlayNext`, `PlayPrevious`

### Reporting temps réel
- `PlaybackProgressInfo` envoyé toutes les **10 secondes**
- Envoi immédiat sur chaque événement (play, pause, seek, stop)

### Lecture avec Media3/ExoPlayer
- `DefaultMediaSourceFactory` — supporte HLS, DASH, MP4, MKV, etc.
- `DeviceProfile` déclaré avec les capacités réelles (détection via `MediaCodecList`)
- Si codec non supporté → transcodage demandé automatiquement au serveur Jellyfin
- Objectif : démarrage du stream rapide et flux stable

### Télécommande AndroidTV

| Bouton | Action |
|--------|--------|
| `KEYCODE_MEDIA_PLAY_PAUSE` | Toggle play/pause |
| `KEYCODE_BACK` | Quitter l'application |
| Long press `KEYCODE_DPAD_CENTER` (si idle) | Afficher le QR code |

---

## Gestion des états

```
[INIT] → [DISCOVERY] → [QR_CODE] → [CONFIGURED] → [PLAYING]
              │                          │               │
              ▼ (timeout 5s)             │          [PAUSED] / [BUFFERING] / [STOPPED]
          [QR_CODE]        long press ◄──┘
                           télécommande
                               │
                           [QR_CODE]
```

---

## Gestion d'erreurs

| Situation | Comportement |
|-----------|-------------|
| Perte réseau pendant lecture | Pause auto + reconnexion silencieuse (3 tentatives), puis message discret |
| Serveur Jellyfin inaccessible au démarrage | QR code affiché quand même, configuration possible |
| Credentials invalides (401) | Erreur renvoyée au téléphone via réponse Ktor |
| Codec non supporté | Transcodage demandé automatiquement, aucun message utilisateur |
| Port 8765 occupé | Essai ports suivants jusqu'à 8775, QR code mis à jour |
| Perte WebSocket Jellyfin | Reconnexion automatique exponentielle (1s, 2s, 4s... max 30s) |

---

## Dépendances principales

```kotlin
// Jellyfin
implementation("org.jellyfin.sdk:jellyfin-core:1.5.x")
// Player
implementation("androidx.media3:media3-exoplayer:1.x")
implementation("androidx.media3:media3-ui:1.x")
// Serveur local
implementation("io.ktor:ktor-server-android:2.x")
// QR code
implementation("com.journeyapps:zxing-android-embedded:4.x")
// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.x")
```

---

## Tests

- **Unitaires** : logique de session Jellyfin, parsing config, machine à états
- **Instrumentés** : détection mode TV/phone, serveur Ktor (démarrage, fallback de port)
- Pas de tests UI complexes — l'application est intentionnellement minimaliste

---

## Publication Play Store

- `minifyEnabled = false` (intégrité du code requise)
- APK unique ciblant TV et phone/tablette
- Couleurs : charte graphique Jellyfin (`#00A4DC`, `#AA5CC3`, fond sombre)
