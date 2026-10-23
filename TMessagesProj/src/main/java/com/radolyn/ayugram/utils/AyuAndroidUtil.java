package com.radolyn.ayugram.utils;
import android.app.Activity;
import android.view.View;
import java.io.File;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.Theme;
public final class AyuAndroidUtil {
    public static long getDirectorySize(File file) {
        if(file==null||!file.exists())return 0;
        try { if(java.nio.file.Files.isSymbolicLink(file.toPath()))return 0; } catch(Exception e){return 0;}
        if(!file.isDirectory())return file.length();
        long size=0; File[] files=file.listFiles();
        if(files!=null)for(File child:files)size+=getDirectorySize(child);
        return size;
    }
    public static boolean openForView(MessageObject message,Activity activity,Theme.ResourcesProvider provider) {return AndroidUtilities.openForView(message,activity,provider,false);}
    public static void showInputError(View view) {AndroidUtilities.shakeViewSpring(view,-6);BotWebViewVibrationEffect.APP_ERROR.vibrate();}
}
