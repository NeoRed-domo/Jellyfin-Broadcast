# Jellyfin Broadcast 📺📱

**Jellyfin Broadcast** est un client Jellyfin ultra-léger conçu pour la diffusion de contenu (Digital Signage, TV d'ambiance, démonstration) sur Android TV, tablettes et smartphones.

L'objectif est simple : **afficher du contenu sans friction**. Pas de navigation complexe, pas de métadonnées, juste de la lecture vidéo pilotée à distance.

---

## ✨ Fonctionnalités

- **🚀 Configuration Zéro-Saisie (TV)** : Plus besoin de taper des adresses IP et mots de passe avec une télécommande. Scannez simplement le QR Code affiché sur la TV avec votre application Jellyfin Broadcast sur smartphone !
- **📱 Contrôle à distance (Cast)** : L'application apparaît comme un appareil "Jellyfin Broadcast" dans votre serveur. Lancez la lecture depuis n'importe quel autre client Jellyfin (Web, Mobile, PLugin Jellyfin sur Jeedom).
- **🎨 Interface Invisible** : Pendant la lecture, aucune interface ne gêne la vue. Idéal pour les boutiques, salles d'attente ou cadres photo numériques.
- **🔄 Auto-Sizing** : Interface adaptative (TV paysage, Téléphone portrait/paysage).
- **⚡ Démarrage rapide** : L'application se reconnecte et est prête à recevoir du contenu en quelques secondes.

## 🛠️ Installation

### Via Play Store
*(Lien à venir)*

### Via APK (Sideload)
Téléchargez le dernier fichier `.apk` depuis l'onglet [Releases](https://github.com/votre-compte/JellyfinBroadcast/releases) et installez-le sur votre appareil.

## 📖 Comment ça marche ?

### 1. Sur Android TV 📺
1. Lancez l'application.
2. Un **QR Code** s'affiche.
3. Ne touchez à rien, la TV est en attente de configuration.

![QR Code TV](screenshots/tv_qr.png)
*(Conseil : Appuyez longtemps sur le bouton central pour réafficher le QR Code si besoin)*

### 2. Sur Smartphone / Tablette 📱
1. Lancez l'application.
2. Si c'est votre première fois, un écran de configuration s'affiche.
3. Remplissez l'adresse de votre serveur Jellyfin ou...
4. **Scannez le QR Code de la TV** (bouton "Scanner un QR Code") pour configurer la TV instantanément !

![Configuration Smartphone](screenshots/mobile_config.png)

### 3. Diffusion 🎬
1. Ouvrez votre application Jellyfin habituelle (ou l'interface web).
2. Cliquez sur l'icône **"Cast" / "Play on"**.
3. Sélectionnez **"Jellyfin Broadcast - [Nom de votre appareil]"**.
4. Lancez une vidéo : elle démarre immédiatement sur l'écran cible !

## 🏗️ Architecture Technique

- **Langage** : Kotlin
- **UI** : Jetpack Compose
- **Lecteur** : Media3 / ExoPlayer
- **QR Scan** : CameraX + ML Kit
- **Communication** : API Jellyfin (WebSocket pour le contrôle à distance)

## 🛡️ Confidentialité

Les identifiants sont stockés localement (EncryptedSharedPreferences). Aucune donnée n'est envoyée à des tiers. L'application communique uniquement avec **votre** serveur Jellyfin.

---

*Développé avec ❤️ pour la communauté Jellyfin.*
