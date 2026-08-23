# VesperaHelper

L'app mantiene una richiesta Wi-Fi generica e chiede al daemon root `tools/vespera-netd.sh` di associare la rete Vespera come connessione system-wide. Il daemon instrada `10.0.0.0/24` su `wlan0`; Ethernet resta attiva per Internet, ADB e RustDesk. Non viene usata alcuna VPN.

## Versioning

`versionName` segue le revisioni dell'app; `versionCode` resta monotono in `app/build.gradle`.

| Versione | versionCode | Note |
|----------|-------------|------|
| 0.2.0 | 2 | Label versione in UI; default probe su 8082; scan porte include 21/8082/8083 |
| 0.2.1 | 3 | A connessione: scan porte automatico, preferisce 8082/8083 e verifica TCP |
| 0.2.2 | 4 | Auto-discovery ritardata + attesa DHCP + retry se porte ancora chiuse |
| 0.2.3 | 5 | Bridge VPN 10.0.0.0/24 per rendere il Vespera raggiungibile da Singularity |
| 0.3.0 | 6 | Configurazione multi-strumento: scan/selezione/salvataggio Vespera I, II, Pro |
| 0.3.1 | 7 | Pulisce status «raggiungibile» se connessione persa; default SSID/BSSID proprietario |
| 0.3.8 | 14 | Marcatori ● salvato / ✓ connesso |
| 0.4.0 | 15 | Selettore lingua IT/EN/ES in alto a destra; testi UI localizzati |
| 0.5 | 41 | Connessione Vespera system-wide e route diretta; rimossi VPN e fallback locali non funzionanti |
| 0.5.2 | 43 | Watchdog Singularity (solo in primo piano): controllo ogni 30 s e manuale via daemon `check-singularity`; su failure refresh route + riavvio Singularity |
| 0.6.0 | 47 | Tab Wi‑Fi + Foto: montaggio HD USB, sync diurna FTP `/USER`, server FTP in sola lettura (porta 2121) per Tailscale |
| 0.6.1 | 48 | Tab Foto: **Smonta HD** visibile solo dopo il montaggio |
| 0.6.2 | 49 | Tab Foto: **Scollega HD** opzionale, anche senza Smonta |
| 0.6.4 | 51 | Rilevamento HD USB: `blkid` NTFS non blocca più il daemon; richieste disco su `disk.req` |
| 0.6.5 | 52 | Montaggio HD **NTFS** via FUSE (`tools/ntfs`, ntfs-3g) |
| 0.6.6 | 53 | Auto-mount HD all'avvio app (retry se il daemon tarda) e rimontaggio dal daemon |
| 0.6.7 | 54 | Se già connesso non ricarica la rilevazione in `onResume`; riavvio automatico Singularity dopo 1 min solo in primo piano |
| 0.6.8 | 55 | Niente riavvio automatico di Singularity: check in primo piano e pulsante **Riavvia Singularity** |
| 0.6.9 | 56 | Pulsanti in rilievo 3D; barre di stato incassate, distinte dai bottoni |
| 0.6.10 | 57 | Palette pulsanti unificata (slate / verde / rosa / terracotta), 3D più morbido |
| 0.6.11 | 58 | Sync FTP Apache Commons Net; popup indipendente (progresso + ETA) anche a Helper chiuso; probe porte via daemon su wlan0 |
| 0.6.12 | 59 | Sync riprende dopo chiusura, force-stop o crash: check stato (file già copiati / .part) e completamento |
| 0.6.13 | 60 | Finestra sync selezionabile: Nascondi / Chiudi; **Continua** in Helper; verifica porte 8083/8082 senza avviare Singularity |
| 0.6.14 | 61 | Finestra sync con titolo e cornice; frequenza diurna (default 2 h); bind HD se il disco è già montato nel kernel |
| 0.6.15 | 62 | Sync FTP: se Android rifiuta `bindSocket` (`EPERM`) usa la route `10.0.0.0/24`; `requestNetwork` accetta anche Wi‑Fi con INTERNET |
| 0.6.16 | 63 | Cartella foto remota: accetta `/user` e `/USER` (FTP Vespera case-sensitive) |
| 0.6.17 | 64 | Popup sync: Nascondi / Chiudi+pausa cliccabili durante il trasferimento; copia tutti, poi verifica, poi cancella |
| 0.6.18 | 65 | Due porte FTP: probe Vespera (telescopio) + server HD; proxy Tailscale sulla porta telescopio |
| 0.6.19 | 66 | A sync conclusa: riepilogo Tutto OK / errori, cartelle, file e dimensioni (finestra resta aperta) |
| 0.6.20 | 67 | Al riavvio app: ripresa automatica dei trasferimenti sospesi/in pausa |
| 0.6.21 | 68 | Un solo daemon (init, niente secondo wrapper); restart senza smontare l’HD; bind ripristinato se manca; sync foto automatica 24h ogni 2 h |
| 0.6.22 | 69 | Tab **Stato**: infrastruttura (Wi‑Fi, API, FTP, Singularity) + lettura REST `/v1`/`/v2/app/status` con aggiornamento ogni 15 s |
| 0.6.25 | 72 | Tab **Telescopio** + inventario porte fase 1; auto-refresh stato solo con tab Telescopio attiva |
| 0.6.70 | 117 | HD spento all’avvio se Vespera offline e allo spegnimento telescopio; **Attiva HD** / Monta a mano; rimontaggio auto quando online |
| 0.6.71 | 118 | Spegnimento HD reale: umount fuse ghost + USB `authorized=0`; stati Attiva/Montato coerenti |
| 0.6.72 | 119 | All’avvio non riaccende più l’HD via Aggiorna/list: wake solo su Attiva/Monta |
| 0.6.73 | 120 | All’avvio spegne sempre l’HD; rimonta solo se API/FTP Vespera rispondono (non basta il Wi‑Fi) |
| 0.6.74 | 121 | Spegnimento HD una sola volta per processo; Attiva HD non viene più annullata dai BOOTSTRAP successivi |
| 0.6.75 | 122 | Apertura MainActivity spegne sempre l’HD se telescopio silenzioso (anche con FGS già attivo) |
| 0.6.76 | 123 | singleTask: anche onNewIntent (riapertura da launcher) riesegue la policy spegnimento HD |
| 0.6.79 | 126 | Riprendi osservazione multi-night via `captureStore/startObservationFromStoredCapture` + storeId |
| 0.6.80 | 127 | Riprendi fa auto-init se serve; Inizializza disabilitato se già init o in osservazione/ripresa |
| 0.6.81 | 128 | Inizializza disabilitato se già inizializzato o in tracking |
| 0.6.82 | 129 | Tab Foto: **Smonta HD** nascosto (basta **Spegni HD**) |
| 0.6.83 | 130 | Meno sync foto: ignora i tick Wi‑Fi CONNECTED e rispetta l’intervallo notturno |
| 0.6.84 | 131 | Dopo il sync FTP ricalcola lo spazio occupato e lo aggiorna nello stato Telescopio |
| 0.6.85 | 132 | Opzione in Sistema: a GENERAL_SUN_TOO_HIGH spegne il Pi dopo l’HD (daemon `shutdown-pi`) |
| 0.6.86 | 133 | Se lo spegnimento a GENERAL_SUN_TOO_HIGH fallisce, ritenta lo stesso giorno (non aspetta domani) |
| 0.6.87 | 134 | Spegnimento sun-too-high: non resta bloccato sulla sync; ogni 10 min aggiunge stop/park e logga l’errore HTTP |
| 0.6.88 | 135 | Prima di spegnere aspetta che park/stop/motori siano idle (altrimenti ritenta tra 10 min) |
| 0.6.89 | 136 | Dopo sync: PARK, poi poll ogni minuto fino a idle, poi spegnimento telescopio |
| 0.6.90 | 137 | Hub eventi telescopio e notifiche Telegram |
| 0.6.91 | 138 | Niente ciclo spegnimento/rimontaggio HD mentre il Wi‑Fi Vespera è su (API ancora in avvio) |
| 0.6.92 | 139 | Spegnimento HD solo dopo perdita Wi‑Fi reale, non all’apertura app né in REQUESTING |
| 0.6.93 | 140 | Controllo mattutino: spegne senza richiedere GENERAL_SUN_TOO_HIGH |

Il daemon deve essere avviato come root sul Pi dopo il boot; l'app comunica con esso tramite `net.req` / `disk.req` nella propria directory esterna.

## Foto / HD USB

Nella tab **Foto / HD**:

1. Aggiorna dischi, seleziona l'HD, **Monta**. Dopo il primo montaggio l'app lo rimonta da sola all'avvio. Sono supportati **exFAT**, **FAT32** e **NTFS** (helper FUSE in `tools/ntfs`).
   A **reboot/spegnimento** ordinato Android esegue `sync` + smontaggio dell'HD (stato salvato → rimontaggio al boot successivo). Uno spegnimento brusco (stacco corrente) non garantisce lo smontaggio pulito.
2. Tutto il giorno, ogni 2 h (intervallo modificabile), copia la cartella `USER` del Vespera (`ftp://10.0.0.1/USER`) sull'HD, verifica numero e dimensione, poi cancella i file verificati sullo strumento. **Sincronizza ora** parte subito. Il progresso è una **finestra indipendente** (selezionabile): **Nascondi** lascia il trasferimento attivo, **Chiudi** lo mette in pausa. In Helper, **Continua** riapre la finestra e riprende.
3. Due porte FTP (anonymous, sola lettura), visibili nella tab Foto:
   - **Telescopio** (default **2122**): proxy verso l’FTP del Vespera (probe su 21/2121/2221/8021).
   - **Hard disk** (default **2121**): file sull’HD USB montato.
   Da remoto: `ftp://<IP-Tailscale>:2122` e `ftp://<IP-Tailscale>:2121`.

Sulla **Home di Android** (non nelle tab Helper) c’è l’icona **Foto HD**: apre l’elenco delle cartelle/file sull’HD montato. Tocca una foto per scorrerle a schermo intero (swipe). Al primo avvio di Helper il sistema può chiedere di fissare la scorciatoia sulla Home.

Serve il daemon aggiornato (`list-disks` / `mount-disk`) e, per NTFS, i binari in `tools/ntfs`. Dopo il deploy:

```
adb push tools/vespera-netd.sh /data/local/tmp/
adb push tools/ntfs /data/local/tmp/ntfs
adb shell sh /data/local/tmp/boot-vespera-netd.sh
```

Su questa immagine AOSP il daemon **non sopravvive al reboot** se non è in init: `tools/vespera-netd-autostart.rc` va in `/system/etc/init/` (come Tailscale) e rilancia `vespera-netd` a `sys.boot_completed`.
