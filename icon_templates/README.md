# Icon Templates — Jellyfin Broadcast

Gabarits pour créer les icônes de l'application. Chaque fichier contient des guides visuels (cercle, croix de centrage).

## Icônes d'application (smartphone)
| Fichier | Taille | Densité Android |
|---------|--------|-----------------|
| `ic_launcher_mdpi_48x48.png` | 48×48 | mdpi |
| `ic_launcher_hdpi_72x72.png` | 72×72 | hdpi |
| `ic_launcher_xhdpi_96x96.png` | 96×96 | xhdpi |
| `ic_launcher_xxhdpi_144x144.png` | 144×144 | xxhdpi |
| `ic_launcher_xxxhdpi_192x192.png` | 192×192 | xxxhdpi |

## Icône Play Store
| Fichier | Taille | Usage |
|---------|--------|-------|
| `ic_launcher_playstore_512x512.png` | 512×512 | Google Play Store |

## Icône Adaptive (Android 8+)
| Fichier | Taille | Usage |
|---------|--------|-------|
| `adaptive_icon_foreground_432x432.png` | 432×432 | Premier plan (le carré bleu = zone de sécurité 66%) |

> Le contenu important de l'icône doit rester dans la zone de sécurité centrale (288×288 pixels).

## Banner Android TV
| Fichier | Taille | Usage |
|---------|--------|-------|
| `tv_banner_320x180.png` | 320×180 | Banner affiché sur l'écran d'accueil Android TV |

## Comment les utiliser
1. Ouvrez le gabarit correspondant dans un éditeur d'image
2. Dessinez votre icône en respectant les guides
3. Exportez en PNG
4. Placez les fichiers dans les dossiers correspondants :
   - `app/src/main/res/mipmap-mdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-hdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
   - `app/src/main/res/drawable/tv_banner.png`
