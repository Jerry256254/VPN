# KucLab VPN

> Hotové APK ke stažení najdeš v [Releases](../../releases) (sekce Assets).
> Po stažení povol instalaci z neznámých zdrojů a APK nainstaluj.

Android VPN klient: skutečný WireGuard tunel na bezplatný Cloudflare WARP
relay. Žádný účet, žádný e-mail, žádná osobní metadata k používání potřeba —
aplikace si při prvním spuštění sama zaregistruje anonymní WARP identitu přes
stejné enrollment API, jaké používá oficiální klient 1.1.1.1 (stejný postup
jako open-source nástroj `wgcf`).

Sestaveno a **ověřeno, že se kompiluje do instalovatelného APK**.
Transportní jádro je end-to-end otestované proti živému WARP relay
(handshake + reálný TCP provoz tunelem, viz níže). **Nebylo ale odzkoušeno
na reálném zařízení/emulátoru** – v tomto prostředí není adb ani emulátor,
takže chování Android `VpnService` na konkrétním výrobci/verzi nikdo
neověřil. Ber to jako funkční, ale na-zařízení netestovaný build.

## Jak to funguje

- **WARP enrollment** (`wgtunnel register`): vygeneruje X25519 klíčový pár,
  zaregistruje ho na `api.cloudflareclient.com` a uloží vrácené WireGuard
  údaje (peer klíč, endpoint `engage.cloudflareclient.com:2408`, adresy
  rozhraní). Jedna identita na instalaci, uložená v privátním úložišti
  aplikace. Při opakovaných pádech tunelu se identita sama zrotuje.
- **Tunelové jádro** (`wgtunnel run`): `wireguard-go` userspace device
  (oficiální Go implementace WireGuardu, stejná, jakou používá oficiální
  WireGuard aplikace pro Android) běžící nad TUN file deskriptorem, který mu
  Kotlin kód předá přes `LocalSocket`/`SCM_RIGHTS` (stejná technika jako
  Clash for Android / Matsuri). Statická Go binárka pro `arm64-v8a`,
  `armeabi-v7a`, `x86`, `x86_64`, bez potřeby Android NDK. Tunel nese TCP
  **i UDP** (tedy i DNS), persistent keepalive 25 s, MTU 1280.
- **VpnCoreService**: Android `VpnService`, foreground notifikace, route
  `0.0.0.0/0` přes tunel, DNS `1.1.1.1` + `9.9.9.9` (tečou tunelem),
  automatické znovunavázání při pádu procesu tunelu.
- **UI**: stav linky, endpoint, relay IP, stáří handshaku, RX/TX počítadla
  (reálné čítače z WireGuard device, obnovované každé 2 s) a živý log.

## Co bylo skutečně otestováno (a co ne)

✅ `register` proti živému WARP API – vrací platnou identitu a peer údaje.
✅ `check` (součást binárky): reálný WireGuard handshake s
   `162.159.192.1:2408` + reálný HTTP požadavek tunelem na `1.1.1.1`
   (`CHECK-PASS`, ověřeno na tomto stroji).
✅ Předání fd přes abstract unix socket (`recvcheck` + `wgtunnel/fdcheck.py`)
   – mechanismus použitý na Androidu pro TUN fd.
✅ Celý Android projekt se zkompiloval (`./gradlew assembleDebug`) do
   funkčního, korektně podepsaného debug APK se všemi nativními binárkami
   a manifestem obsahujícím `VpnService`.
❌ Appka nebyla nainstalována ani spuštěna na skutečném Android zařízení
   nebo emulátoru. Reálné chování `VpnService.Builder().establish()`,
   chování OEM battery-optimalizací a end-to-end propustnost na zařízení
   **nejsou ověřené**.

## Soukromí a omezení – čti před použitím

1. **Relay provozuje Cloudflare.** Veškerý provoz tunelu jde přes jejich
   WARP infrastrukturu (free tier). Cloudflare o sobě tvrdí, že obsah
   neprodává a provozní data minimalizuje ([1.1.1.1 privacy
   policy](https://www.cloudflare.com/application/privacypolicy/)), ale
   technicky relay vidí toky jako každý jiný VPN operátor. „Zcela bez
   metadat" není žádný free relay na světě – tady aspoň není potřeba účet
   ani e-mail a operátor je známá firma, ne náhodný honeypot.
2. **WARP není anonymizační síť** (na rozdíl od Toru). Skryje provoz před
   lokální sítí a poskytovatelem připojení, ale není stavěný na anonymitu.
3. **Free tier bez SLA.** Cloudflare může enrollment API rate-limitovat
   nebo měnit; endpointy a porty se mohou časem měnit (aplikace bere
   endpoint z registrační odpovědi a umí fallback).
4. **Historie:** do v0.2.0 aplikace stavěla na živě skenovaných veřejných
   proxy + tun2socks. To se ukázalo jako slepá ulička: veřejné HTTP proxy
   neumí UDP vůbec (`DialUDP` vrací `errors.ErrUnsupported` – odtud
   `unsupported operation` v logu) a test ukázal, že UDP ASSOCIATE neumí
   ani naprostá většina veřejných SOCKS5. Bez UDP nefunguje DNS a tunel je
   prakticky mrtvý. Proto v0.3.0 přešlo na WireGuard + WARP.

## Sestavení

```
export ANDROID_HOME=~/android-sdk   # nebo cesta k tvému Android SDK
./gradlew assembleDebug
# výstup: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Vyžaduje JDK 17 (viz `gradle.properties` → `org.gradle.java.home`, uprav dle
svého systému) a Android SDK s `platforms;android-34` a
`build-tools;35.0.0` (nebo novější).

## Přebuild nativního jádra

Zdroj je v `wgtunnel/` (mimo Gradle strom, čistý Go modul). Statické
binárky bez cgo, takže jdou přebuildovat bez NDK:

```
cd wgtunnel
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o ../app/src/main/jniLibs/arm64-v8a/libwgtunnel.so .
```

Analogicky pro `armeabi-v7a` (`GOARCH=arm GOARM=7`), `x86` (`GOARCH=386`),
`x86_64`. Self-test po přebuildu:

```
go build -o /tmp/wgtunnel . && /tmp/wgtunnel register > /tmp/warp.json \
  && /tmp/wgtunnel check --config /tmp/warp.json   # CHECK-PASS = OK
./fdcheck.py /tmp/wgtunnel                          # FDCHECK-PASS = OK
```
