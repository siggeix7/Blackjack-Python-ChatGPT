# Blackjack-Python-ChatGPT
I wanted to test ChatPGT 5 to see how far it could go in programming and this is what came out

## Versione Python

Il gioco originale da terminale e' in `BlackJack.py`.

```bash
python3 BlackJack.py
```

## APK Android

La versione Android nativa e' nella cartella `android/`. Non richiede Gradle: lo script usa direttamente Android SDK build-tools, `javac`, `d8`, `aapt2`, `zipalign` e `apksigner`.

Guida rapida:

```bash
git clone git@github.com:siggeix7/Blackjack-Python-ChatGPT.git
cd Blackjack-Python-ChatGPT/android
./build-apk.sh
```

L'APK viene creato in:

```text
android/build/outputs/blackjack-royal.apk
```

Requisiti:

- JDK installato con `javac`, `jar` e `keytool` nel `PATH`.
- Android SDK installato con Platform SDK `android-35`.
- Android SDK build-tools `35.0.0`.

Se hai installato Android Studio, di solito lo script trova l'SDK automaticamente in `~/Android/Sdk` su Linux o `~/Library/Android/sdk` su macOS. Se l'SDK e' in un altro percorso, passa `ANDROID_HOME` o `ANDROID_SDK_ROOT`:

```bash
cd android
ANDROID_HOME=/percorso/al/tuo/Android/Sdk ./build-apk.sh
```

Installazione rapida dei pacchetti SDK necessari:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Test smoke della logica di gioco:

```bash
cd android
./test-game.sh
```

Generazione APK firmato debug:

```bash
cd android
./build-apk.sh
```

Output:

```text
android/build/outputs/blackjack-royal.apk
```

Per firmare con un keystore personalizzato:

```bash
BLACKJACK_KEYSTORE=/path/to/release.keystore \
BLACKJACK_KEY_ALIAS=alias \
BLACKJACK_KEYSTORE_PASSWORD=password \
BLACKJACK_KEY_PASSWORD=password \
./build-apk.sh
```

## Pubblicare una release APK

Il repository contiene un workflow GitHub Actions in `.github/workflows/release-apk.yml`.

Ogni commit pubblicato su `main` avvia automaticamente la build Android e crea una GitHub Release con l'APK allegato.

Esempio:

```bash
git add .
git commit -m "Update game"
git push origin main
```

Il workflow crea una release con tag automatico `apk-<short-sha>`, per esempio `apk-a1b2c3d`, e allega un file come `blackjack-royal-a1b2c3d.apk`.

Il workflow puo' anche essere avviato manualmente dalla scheda GitHub Actions. In quel caso non crea una release, ma pubblica l'APK come artifact scaricabile dal run.
