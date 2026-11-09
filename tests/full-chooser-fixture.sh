#!/bin/bash
set -euo pipefail
D=/tmp/nem-full-chooser; SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}; BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
mkdir -p "$D/src/test/fullchooser" "$D/classes" "$D/dex"
cat >"$D/src/test/fullchooser/Chat.java" <<'JAVA'
package test.fullchooser;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.*;
public class Chat extends Activity {
 LinearLayout layout; EditText input; Button document; TextView answer;
 @Override public void onCreate(Bundle b) { super.onCreate(b); layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); setContentView(layout);
  Button attach=new Button(this); attach.setText("Attach files"); attach.setContentDescription("Attach files"); layout.addView(attach);
  document=new Button(this); document.setText("Document"); document.setAllCaps(false); document.setVisibility(android.view.View.GONE); layout.addView(document);
  attach.setOnClickListener(v->document.setVisibility(android.view.View.VISIBLE));
  document.setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE); Intent chooser=Intent.createChooser(i,"Choose an app"); Intent other=new Intent(i).setClassName("test.resolver","test.resolver.OtherPicker"); chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,new Intent[]{other}); startActivityForResult(chooser,7); });
  input=new EditText(this); input.setSingleLine(false); layout.addView(input); Button send=new Button(this); send.setText("Send");send.setContentDescription("Send");layout.addView(send);
  send.setOnClickListener(v->{TextView prompt=new TextView(this); prompt.setText(input.getText());layout.addView(prompt);input.setText(""); answer=new TextView(this);answer.setText("IMAGE_OK"); layout.addView(answer);});
 }
 @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data);if(request!=7 || result!=RESULT_OK || data==null)return;
  try { String name=""; try(Cursor c=getContentResolver().query(data.getData(),new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {if(c!=null && c.moveToFirst())name=c.getString(0);} try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null || in.read()<0)throw new IllegalStateException("Empty image");}
  TextView image=new TextView(this);image.setText("Received image");image.setContentDescription(name);layout.addView(image); Button del=new Button(this);del.setText("Delete");del.setContentDescription("Delete");layout.addView(del);document.setVisibility(android.view.View.GONE);
  }catch(Exception e){throw new RuntimeException(e);}
 }
}
JAVA
cat >"$D/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.fullchooser"><uses-sdk android:minSdkVersion="27" android:targetSdkVersion="32"/><application android:label="Bridge chooser fixture" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".Chat" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>
XML
javac -source 8 -target 8 -classpath "$SDK/platforms/android-33/android.jar" -d "$D/classes" "$D/src/test/fullchooser/Chat.java"
"$BT/d8" --min-api 27 --output "$D/dex" "$D/classes/test/fullchooser/Chat.class"
"$BT/aapt" package -f -M "$D/AndroidManifest.xml" -I "$SDK/platforms/android-33/android.jar" -F "$D/unsigned.apk"
(cd "$D/dex" && zip -q "$D/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$D/unsigned.apk" "$D/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$D/fullchooser.apk" "$D/aligned.apk"
echo "$D/fullchooser.apk"
