# Feuille de route

Pistes envisagées, non planifiées. Rien ici n'est engagé tant que ce n'est pas décidé.

## 1. Vidéos de marche / course virtuelle

Afficher une vidéo de paysage pendant la séance, dont la lecture suit la vitesse du tapis.

- L'appli ne lit que des **fichiers vidéo locaux** importés par l'utilisateur (sélecteur Android). Aucun téléchargement ni lecture YouTube intégrés.
- **Vitesse de référence par vidéo** (km/h à laquelle elle a été filmée, 5 par défaut). Vitesse de lecture = vitesse du tapis / vitesse de référence. Pause quand le tapis s'arrête.
- Écran de séance : vidéo en fond, métriques en surimpression (vitesse, pente, cardio, durée, palier, calories).
- Mode optionnel, choisi au lancement d'une séance. Sans vidéo, l'écran reste inchangé.
- Technique : Media3 / ExoPlayer (changement de vitesse de lecture dès Android 6, donc compatible Android 7). Lecture en boucle si la séance dépasse la durée de la vidéo.
- Limite connue : en dessous de ×0,5 la lecture paraît au ralenti ; viser des vidéos filmées à 4–6 km/h.
- Plus tard : pente lue dans la vidéo (fichier GPX) pour piloter l'inclinaison ; diffusion sur TV.

## 2. Synchronisation multi-appareils via le NAS

Besoin : utiliser l'appli sur un appareil sans Health Connect (Android 7 à 8) sans perdre les données de santé.

- Health Connect est local à chaque téléphone et indisponible avant Android 9.
- Principe : un téléphone « principal » (avec Health Connect) publie un instantané quotidien (poids, composition corporelle, calories ingérées et dépensées, pas, déficit). Les autres appareils le lisent. Les séances faites sur un appareil sans Health Connect sont envoyées au stockage partagé, puis écrites dans Health Connect par le téléphone principal.
- Plans, programmes, calendrier, historique et profil sont synchronisés aussi.
- **Stockage retenu : NAS Synology DS418play, sans Docker.** Donc **WebDAV** (Synology WebDAV Server ou WebDAV via File Station), avec un utilisateur dédié. Un petit fichier JSON par séance, par plan et par jour de santé ; fusion côté appli (dernière modification gagnante).
- Prérequis technique : remplacer les identifiants locaux (Long auto-incrémentés) des plans, programmes et séances par des identifiants uniques, avec migration de la base Room.
- Réseau : synchronisation sur le Wi-Fi local. Hors de la maison, il faudrait QuickConnect ou Tailscale.
- Sécurité : préférer HTTPS avec le certificat du NAS ; HTTP simple seulement sur le réseau local (nécessite une configuration réseau explicite côté Android).
- Alternatives écartées pour l'instant : Firebase / Firestore, Google Drive (cloud tiers), serveur Docker (non supporté par le DS418play).

## 3. Économie de batterie de la montre

- Arrêter automatiquement la mesure quand le téléphone ne répond plus pendant quelques minutes.
- Laisser l'écran de la montre s'éteindre en dehors des séances.
- Constaté sur la montre (SM-R960) : avec l'option « s'éteint seul », le cardio n'arrive plus au téléphone écran éteint. L'écran reste donc « toujours allumé » par défaut. Pistes si on y revient : verrou de réveil partiel (WakeLock) dans HeartRateService, et `flushAsync()` de l'ExerciseClient pour forcer l'envoi des mesures mises en tampon.
- Fait : l'écran de la montre n'est maintenu allumé que pendant une séance (ou une mesure manuelle) ; il s'éteint normalement le reste du temps, ce qui supprime la décharge rapide de la batterie constatée avec l'écran toujours allumé.
