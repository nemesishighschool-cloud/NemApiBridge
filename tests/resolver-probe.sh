#!/bin/bash
set -euo pipefail
D=/tmp/nem-resolver-probe
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
mkdir -p "$D/src/test/resolver" "$D/classes" "$D/dex"
cat >"$D/src/test/resolver/Probe.java" <<'JAVA'
package test.resolver;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
public class Probe extends Activity {
 @Override public void onCreate(Bundle b) { super.onCreate(b); Intent i=new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); Intent chooser=Intent.createChooser(i,"Choisir une application"); Intent fake=new Intent(i).setClassName("test.resolver","test.resolver.OtherPicker"); chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,new Intent[]{fake}); startActivityForResult(chooser,42); }
}
JAVA
cat >"$D/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.resolver"><uses-sdk android:minSdkVersion="27" android:targetSdkVersion="32"/><application android:label="Resolver probe" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".Probe" android:exported="true"/><activity-alias android:name=".OtherPicker" android:targetActivity=".Probe" android:label="Other images" android:exported="true"><intent-filter><action android:name="android.intent.action.GET_CONTENT"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.OPENABLE"/><data android:mimeType="image/*"/></intent-filter></activity-alias></application></manifest>
XML
javac -source 8 -target 8 -classpath "$SDK/platforms/android-33/android.jar" -d "$D/classes" "$D/src/test/resolver/Probe.java"
"$BT/d8" --min-api 27 --output "$D/dex" "$D/classes/test/resolver/Probe.class"
"$BT/aapt" package -f -M "$D/AndroidManifest.xml" -I "$SDK/platforms/android-33/android.jar" -F "$D/probe-unsigned.apk"
(cd "$D/dex" && zip -q "$D/probe-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$D/probe-unsigned.apk" "$D/probe-aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$D/probe.apk" "$D/probe-aligned.apk"
echo "$D/probe.apk"
