package liquidbounce.droidbridge.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;

import liquidbounce.droidbridge.host.cef.CefriumHostService;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, CefriumHostService.class));
        } else {
            startService(new Intent(this, CefriumHostService.class));
        }

        String dir = getApplicationInfo().nativeLibraryDir;
        File cef = new File(dir, "libcef.so");
        File mcef = new File(dir, "libmcef.so");
        String cefState = cef.isFile()
                ? "OK (" + cef.length() / 1024 / 1024 + " MB)" : "MISSING";
        String mcefState = mcef.isFile()
                ? "OK (" + mcef.length() / 1024 + " KB)" : "MISSING";

        TextView tv = new TextView(this);
        tv.setText("DroidBridge Host\n"
                + "Cefrium 0.19.0 (Chromium 156.0.8078.80)\n\n"
                + "承载进程已启动，打开启动器进入游戏即可。\n\n"
                + "nativeLibraryDir:\n" + dir + "\n\n"
                + "libcef.so:  " + cefState + "\n"
                + "libmcef.so: " + mcefState);
        tv.setPadding(48, 96, 48, 48);
        setContentView(tv);
    }

}
