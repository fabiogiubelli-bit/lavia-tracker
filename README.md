# LAVIA Tracker

App Android per registrare un'escursione, vederla sulla mappa in tempo reale (Mapbox) e ottenere il file GPX a fine uscita. Pensata come prototipo da provare subito, per il mondo [LAVIA](https://lavia.cc).

## Cosa fa

- Mappa Mapbox Outdoors con la tua posizione in tempo reale e la traccia che si disegna mentre cammini/pedali
- Statistiche live: distanza, tempo in movimento, dislivello positivo, velocità
- Registrazione in background (servizio in primo piano con notifica), anche a schermo spento
- Pausa / Riprendi (ogni ripresa apre un nuovo segmento nel GPX)
- A fine uscita: salva il GPX in `Download/LAVIA/` e permette di condividerlo (Drive, WhatsApp, mail, LAVIA...)
- Se l'app viene chiusa dal sistema, la traccia viene recuperata alla riapertura (in pausa)

## Installare l'APK sul telefono

1. Apri la pagina **Releases** del repository e scarica `lavia-tracker.apk` dalla release **Ultima build (APK)**
   (in alternativa: tab **Actions** → ultima esecuzione → artifact `lavia-tracker-apk`, che è uno zip).
2. Aprilo dal telefono e consenti l'installazione da questa fonte.
3. Al primo avvio l'app chiede il **token pubblico Mapbox** (vedi sotto) e i permessi di posizione e notifiche.

## Token Mapbox (gratuito)

1. Crea un account su <https://account.mapbox.com> e copia il **Default public token** (inizia con `pk.`).
2. Incollalo nell'app al primo avvio (si può cambiare dal pulsante "⚙ Token").

Per averlo già incorporato nell'APK, aggiungi il secret del repository `MAPBOX_PUBLIC_TOKEN` (Settings → Secrets and variables → Actions) e rilancia la build. Il token pubblico finisce comunque dentro l'APK: in Mapbox puoi limitarne l'uso agli URL consentiti.

Se la build fallisse con errore 401 sul repository Maven di Mapbox, aggiungi anche il secret `MAPBOX_DOWNLOADS_TOKEN` (token segreto `sk.` con scope `DOWNLOADS:READ`).

## Build in locale

Serve Android Studio (o JDK 17 + Android SDK + Gradle 8.14).

```bash
echo "MAPBOX_PUBLIC_TOKEN=pk.xxxxx" >> local.properties   # opzionale
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Con Android Studio basta aprire la cartella: il wrapper Gradle viene generato in automatico.

## Struttura

| File | Ruolo |
|---|---|
| `MainActivity.kt` | Mappa, statistiche, pulsanti, salvataggio e condivisione GPX |
| `TrackingService.kt` | Servizio in primo piano che riceve i fix GPS |
| `TrackRepository.kt` | Stato della registrazione, statistiche, persistenza su file |
| `GpxWriter.kt` | Generazione GPX 1.1 |
| `.github/workflows/build.yml` | Test + build dell'APK + release `apk-latest` |

## Limiti noti del prototipo

- APK **debug** (firma di debug): ottimo per provare, non per il Play Store
- Mappe online: niente download offline
- Il dislivello è calcolato dall'altitudine GPS con soglia di 3 m, quindi indicativo
- Su alcuni telefoni (Xiaomi, Huawei, Samsung) conviene escludere l'app dall'ottimizzazione batteria per registrazioni lunghe

## Idee per il passo successivo

Upload diretto del GPX su lavia.cc, import di tracce GPX da seguire sulla mappa, mappe offline, grafico altimetrico, firma release e pubblicazione su Play Store.
