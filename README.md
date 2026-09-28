# KucLab VPN (PoC)

> Hotové APK ke stažení najdeš v [Releases](../../releases) (sekce Assets).
> Po stažení povol instalaci z neznámých zdrojů a APK nainstaluj.

Android VPN klient, který jako exit servery používá živě skenované veřejné
free-proxy listy (HTTP/SOCKS4/SOCKS5) místo vlastní serverové infrastruktury.
Sestaveno a **ověřeno, že se skutečně kompiluje do instalovatelného APK**
(`app/build/outputs/apk/debug/app-debug.apk`, `./gradlew assembleDebug` proběhlo
úspěšně se skutečným Android SDK). **Nebylo ale odzkoušeno na reálném
zařízení/emulátoru** – v tomto prostředí není adb ani emulátor, takže běhové
chování (skutečné připojení, propustnost, chování Android VPN frameworku na
konkrétním výrobci/verzi) nikdo neověřil. Ber to jako solidní, ale netestovaný
na-zařízení PoC.

## Jak to funguje

- **Proxy pool** (`proxy/`): stahuje kandidáty z několika veřejných zdrojů
  (TheSpeedX, ShiftyTR, clarketm, proxyscrape.com, geonode.com), ověřuje
  živost skutečným TCP/HTTP handshake (ne jen tomu, co tvrdí zdrojový list),
  a **přeskenovává pool každé 3 minuty** – ve free proxy listech typicky
  desítky procent položek umírá během minut až hodin, takže jednorázový
  scan nestačí.
- **Tunelové jádro**: [`xjasonlyu/tun2socks`](https://github.com/xjasonlyu/tun2socks)
  (gVisor netstack, MIT licence) – zkompilováno jako statický Go binární
  soubor pro `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, bez potřeby
  Android NDK. Umí nativně směrovat přes `socks5://`, `socks4://` i
  `http://` upstream proxy.
- **fdbridge** (`app/src/main/kotlin/.../tunnel/TunnelController.kt` +
  vlastní Go program v `fdbridge/`): Android `VpnService` dá TUN file
  descriptor jen appce samotné, ne libovolnému spuštěnému procesu.
  `fdbridge` je malý čistě-Go program, který ten fd přijme od Kotlin kódu
  přes `LocalSocket`/`SCM_RIGHTS` (stejná technika jako u Clash for
  Android/Matsuri) a pak se `exec`-ne do `tun2socks` s tím fd. **Tento
  mechanismus je reálně otestován** end-to-end (viz níže) mimo tuto appku,
  jen ne uvnitř skutečného Android VpnService.
- **VpnCoreService**: Android `VpnService`, foreground notifikace, vybere
  nejlepší živou proxy z poolu, spustí tunel, a při pádu/úmrtí proxy
  automaticky rotuje na další (proxy servery umírají za pochodu, ne jen
  při startu).

## Co bylo skutečně otestováno v tomto prostředí (a co ne)

✅ `tun2socks` binárka se zkompilovala pro všechny 4 ABI a spustitelný
   `--help` výstup potvrzuje podporu `--device fd://N` a `--proxy socks5://…|http://…`.
✅ `fdbridge` mechanismus (SCM_RIGHTS předání fd přes abstract unix socket →
   `exec` → dítě čte z zděděného fd) byl end-to-end odzkoušen samostatným
   testem (`fdbridge/testsrv`) – funguje přesně jak má.
✅ SOCKS5 UDP ASSOCIATE handshake (`Socks5UdpDnsProbe.kt`) byl ověřen proti
   20 živým veřejným SOCKS5 proxy z reálného listu – protokol je
   implementován správně (dostáváme validní SOCKS5 odpovědi), ale **žádná
   z 20 testovaných veřejných SOCKS5 proxy ve skutečnosti UDP ASSOCIATE
   nepodporovala**. To je důležité zjištění, ne jen teoretická obava.
✅ Celý Android projekt se zkompiloval (`./gradlew assembleDebug`) do
   funkčního, korektně podepsaného debug APK se všemi natívními knihovnami
   zabalenými a manifestem obsahujícím `VpnService`.
❌ Appka nebyla nainstalována ani spuštěna na skutečném Android zařízení
   nebo emulátoru – tady žádný nebyl k dispozici. Reálné chování
   `VpnService.Builder().establish()`, chování OEM battery-optimization na
   pozadí, a end-to-end propustnost provozu **nejsou ověřené**.

## Vážná omezení, o kterých bys měl vědět (ne jen formalita)

1. **Bezpečnost proti operátorovi proxy serveru**: pro cíle přes HTTP proxy
   (drtivá většina veřejných listů) je provoz šifrovaný jen do té míry, do
   jaké cílová stránka používá HTTPS. Operátor proxy je typicky neznámý
   subjekt – u volně sesbíraných proxy je nezanedbatelná šance, že jde o
   kompromitovaný stroj, honeypot sbírající provoz, nebo server záměrně
   provozovaný pro MITM. „Šifrovaný VPN tunel" v marketingovém smyslu to
   není – je to jen řetězec nedůvěryhodných relay uzlů.
2. **DNS únik / nefunkčnost**: jak je vidět v testu výše, HTTP proxy neumí
   přenášet UDP vůbec a naprostá většina veřejných SOCKS5 proxy UDP
   ASSOCIATE ve skutečnosti nepodporuje. `ProxyValidator` proto preferuje
   uzly s potvrzenou DNS schopností, ale realisticky bude většina relací
   běžet jen s DNS řešeným přes proxy operátora (pokud vůbec) – nasaď jen
   pokud s tím počítáš.
3. **Právní/ToS rovina**: scraping a připojování se přes cizí, často
   neautorizované open proxy je v šedé zóně – řada z nich běží na
   zařízeních, kde majitel o roli "veřejné proxy" vůbec neví (odcizené
   routery/IoT). Distribuce takové appky (Play Store) by pravděpodobně
   narazila na politiku VPN služeb i na odpovědnost za provoz přes cizí
   infrastrukturu.
4. **Bez rate-limitingu/autentizace zdrojů** – žádný z použitých veřejných
   listů negarantuje SLA ani nemá API klíč; při větším provozu tě mohou
   zdrojové servery rate-limitovat nebo blokovat.

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

## Přebuild nativních binárek

Zdroj pro `fdbridge` je v `fdbridge/` (mimo Gradle strom, čistý Go modul).
`tun2socks` je nezměněný upstream z `github.com/xjasonlyu/tun2socks`. Obě
jsou statické Go binárky bez cgo, takže je lze přebuildovat bez NDK:

```
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o app/src/main/jniLibs/arm64-v8a/libtun2socks.so .
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o app/src/main/jniLibs/arm64-v8a/libfdbridge.so ./fdbridge
```

Analogicky pro `armeabi-v7a` (`GOARCH=arm GOARM=7`), `x86` (`GOARCH=386`), `x86_64`.
