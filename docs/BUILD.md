# Building Dancing with Dementia

The app is built without Gradle, using the command-line Android tools (Ubuntu packages shown). Releases are **signed separately, on the maintainer's own machine**, with the release key; the key never goes near this repository or the build environment.

```bash
# Tools (Ubuntu)
apt-get install -y android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
export ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
PKG_DIR=com/seven7genanalytics/dancingwithdementia

# Optional private overlay: local/res takes priority over res when present
RES="-S res"; [ -d local/res ] && RES="-S local/res -S res --auto-add-overlay"

mkdir -p gen obj bin
aapt package -f -m -J gen $RES -M AndroidManifest.xml -I "$ANDROID_JAR"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d obj gen/$PKG_DIR/R.java src/$PKG_DIR/*.java
dalvik-exchange --dex --output=bin/classes.dex obj
aapt package -f -M AndroidManifest.xml $RES -I "$ANDROID_JAR" -F bin/unsigned.apk
(cd bin && zip -j unsigned.apk classes.dex)
zipalign -f -p 4 bin/unsigned.apk bin/aligned-unsigned.apk
```

`-encoding UTF-8` matters: the source contains an emoji literal (the book icon).

## Signing (maintainer's Mac only)

```bash
apksigner sign --ks /Volumes/DwDReleaseKey/dwd-release.jks --ks-key-alias dwd-release \
  --out DancingwithDementia-<version>.apk aligned-unsigned.apk
apksigner verify --print-certs DancingwithDementia-<version>.apk
```

Expected signer SHA-256: `92:0D:CE:CF:2E:76:45:B6:8B:C0:1C:5B:F2:B6:30:BE:3F:97:2B:7E:9C:6E:83:4B:06:B0:13:23:AF:A9:EE:7E`

Every release must raise `android:versionCode` in `AndroidManifest.xml`, or tablets will refuse the update.
