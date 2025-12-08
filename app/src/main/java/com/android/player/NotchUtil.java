package com.android.player;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class NotchUtil {

    public static void enableNotch(Activity activity) {
        googleP(activity.getWindow());
        huaweiEnable(activity.getWindow());
        xiaomiEnable(activity.getWindow());
    }

    private static void googleP(Window window) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
    }

    private static void huaweiEnable (Window window) {
        if (window == null) {
            return;
        }
        /*刘海屏全屏显示FLAG*/
        final int FLAG_NOTCH_SUPPORT=0x00010000;
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        try {
            Class layoutParamsExCls = Class.forName("com.huawei.android.view.LayoutParamsEx");
            Constructor con = layoutParamsExCls.getConstructor(WindowManager.LayoutParams.class);
            Object layoutParamsExObj = con.newInstance(layoutParams);
            Method method=layoutParamsExCls.getMethod("addHwFlags", int.class);
            method.invoke(layoutParamsExObj, FLAG_NOTCH_SUPPORT);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |InstantiationException
                | InvocationTargetException e) {
            Log.e("NotchUtil", "hw clear notch screen flag api error");
        } catch (Exception e) {
            Log.e("NotchUtil", "other Exception");
        }
    }

    private static void xiaomiEnable(Window window) {
        int flag = 0x00000100 | 0x00000200 | 0x00000400;
        try {
            Method method = Window.class.getMethod("addExtraFlags",
                    int.class);
            method.invoke(window, flag);
        } catch (Exception e) {
            Log.i("NotchUtil", "addExtraFlags not found.");
        }
    }

}
