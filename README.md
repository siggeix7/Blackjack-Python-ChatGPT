# Blackjack-Python-ChatGPT
I wanted to test ChatPGT 5 to see how far it could go in programming and this is what came out

## Versione Python

Il gioco originale da terminale e' in `BlackJack.py`.

```bash
python3 BlackJack.py
```

## APK Android

La versione Android nativa e' nella cartella `android/`. Non richiede Gradle: lo script usa direttamente Android SDK build-tools, `javac`, `d8`, `aapt2`, `zipalign` e `apksigner`.

Requisiti:

- `ANDROID_HOME` impostato verso un Android SDK valido, oppure SDK disponibile in `/tmp/opencode/android-sdk`.
- Android build-tools `35.0.0`.
- Platform SDK `android-35`.
- JDK con `javac`, `jar` e `keytool` nel `PATH`.

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
