package com.lody.plugin;

import android.app.Activity;
import com.lody.plugin.control.PluginActivityControl;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.lody.plugin.api.LPluginBug;
import com.lody.plugin.bean.LActivityPlugin;
import com.lody.plugin.control.PluginActivityCallback;
import com.lody.plugin.exception.LaunchPluginException;
import com.lody.plugin.exception.NotFoundPluginException;
import com.lody.plugin.exception.PluginCreateFailedException;
import com.lody.plugin.exception.PluginNotExistException;
import com.lody.plugin.manager.LApkManager;
import com.lody.plugin.manager.LCallbackManager;
import com.lody.plugin.manager.LPluginBugManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Created by lody  on 2015/3/27.
 */
public class LActivityProxy extends Activity implements ILoadPlugin {

    private static final String TAG = LActivityProxy.class.getSimpleName();
    private LActivityPlugin remotePlugin;
    boolean meetBUG = false;

    @Override
    public LActivityPlugin loadPlugin(Activity ctx, String apkPath) {
        //插件必须要确认有没有经过初始化，不然只是空壳
        remotePlugin = new LActivityPlugin(ctx, apkPath);
        return remotePlugin;

    }

    @Override
    public LActivityPlugin loadPlugin(Activity ctx, String apkPath, String activityName) {
        LActivityPlugin plugin = loadPlugin(ctx, apkPath);
        plugin.setTopActivityName(activityName);
        fillPlugin(plugin);
        return plugin;
    }


    /**
     * 装载插件
     *
     * @param plugin
     */
    @Override
    public void fillPlugin(LActivityPlugin plugin) {
        if (plugin == null) {
            throw new PluginNotExistException("Plugin is null!");
        }
        String apkPath = plugin.getPluginPath();
        File apk = new File(apkPath);
        if (!apk.exists()) throw new NotFoundPluginException(apkPath);

        if (!this.remotePlugin.from().canUse()) {
            Log.i(TAG, "Plugin is not been init,init it now！");
            LApkManager.initApk(plugin.from(),this,super.getResources());
            remotePlugin.from().debug();

        } else {
            Log.i(TAG, "Plugin have been init.");
        }
        fillPluginTheme(plugin);
        fillPluginActivity(plugin);


    }

    private void fillPluginTheme(LActivityPlugin plugin) {

        Resources.Theme pluginTheme = plugin.from().pluginRes.newTheme();
        pluginTheme.setTo(super.getTheme());
        plugin.setTheme(pluginTheme);

        PackageInfo packageInfo = plugin.from().pluginPkgInfo;
        String mClass = plugin.getTopActivityName();

        Log.i(TAG, "Before fill Plugin 's Theme,We check the plugin:info = " + packageInfo + "topActivityName = " + mClass);

        int defaultTheme = packageInfo.applicationInfo.theme;
        ActivityInfo curActivityInfo = null;
        for (ActivityInfo a : packageInfo.activities) {
            if (a.name.equals(mClass)) {
                curActivityInfo = a;
                if (a.theme != 0) {
                    defaultTheme = a.theme;
                } else if (defaultTheme != 0) {
                    //ignore
                } else {
                    //支持不同系统的默认Theme
                    if (Build.VERSION.SDK_INT >= 14) {
                        defaultTheme = android.R.style.Theme_DeviceDefault;
                    } else {
                        defaultTheme = android.R.style.Theme;
                    }
                }
                break;
            }
        }
        Log.i(TAG,"Plugin theme = " + plugin.getTheme());
        plugin.getTheme().applyStyle(defaultTheme, true);
        setTheme(defaultTheme);
        if (curActivityInfo != null) {
            getWindow().setSoftInputMode(curActivityInfo.softInputMode);
        }

        if (LPluginConfig.usePluginTitle) {
            CharSequence title = null;
            try {
                title = LPluginTool.getAppName(this, plugin.getPluginPath());
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            if (title != null) setTitle(title);
        }



    }


    /**
     * 装载插件的Activity
     *
     * @param plugin
     */
    private void fillPluginActivity(LActivityPlugin plugin) {
        try {
            String top = plugin.getTopActivityName();
            if (top == null) {
                top = plugin.from().pluginPkgInfo.activities[0].name;
                plugin.setTopActivityName(top);
            }
            Activity myPlugin = (Activity) plugin.from().pluginLoader.loadClass(plugin.getTopActivityName()).newInstance();
            plugin.setCurrentPluginActivity(myPlugin);

        } catch (Exception e) {
            throw new LaunchPluginException(e.getMessage());
        }
    }




    @Override
    protected void onCreate(Bundle savedInstanceState) {


        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, final Throwable ex) {

                LPluginBug bug = new LPluginBug();
                bug.error = ex;
                bug.errorTime = System.currentTimeMillis();
                bug.errorThread = thread;
                bug.errorPlugin = remotePlugin;
                bug.processId = android.os.Process.myPid();
                LPluginBugManager.callAllErrorListener(bug);



            }
        });
        super.onCreate(savedInstanceState);
        final Bundle pluginMessage = getIntent().getExtras();

        String pluginActivityName;
        String pluginDexPath;
        //int pluginIndex;
        if (pluginMessage != null) {
            pluginActivityName = pluginMessage.getString(LPluginConfig.KEY_PLUGIN_ACT_NAME, LPluginConfig.DEF_PLUGIN_CLASS_NAME);
            pluginDexPath = pluginMessage.getString(LPluginConfig.KEY_PLUGIN_DEX_PATH, LPluginConfig.DEF_PLUGIN_DEX_PATH);
            //pluginIndex = pluginMessage.getInt(LPluginConfig.KEY_PLUGIN_INDEX, 0);
        } else {
            throw new PluginCreateFailedException("Please put the Plugin Path!");
        }
        if (pluginDexPath == LPluginConfig.DEF_PLUGIN_DEX_PATH) {
            throw new PluginCreateFailedException("Please put the Plugin Path!");
        }

        remotePlugin = loadPlugin(LActivityProxy.this, pluginDexPath);

        if (pluginActivityName != LPluginConfig.DEF_PLUGIN_CLASS_NAME) {
            remotePlugin.setTopActivityName(pluginActivityName);
        }

        fillPlugin(remotePlugin);
        //remotePlugin.from().debug();

        PluginActivityControl control = new PluginActivityControl(LActivityProxy.this, remotePlugin.getCurrentPluginActivity(), remotePlugin.from().pluginApplication);
        remotePlugin.setControl(control);
        control.dispatchProxyToPlugin();
        try {
            control.callOnCreate(savedInstanceState);
            LCallbackManager.callAllOnCreate(savedInstanceState);
        } catch (Exception e) {
            meetBUG = true;
            processError(e);
        }

    }

    private void processError(Exception e) {
        e.printStackTrace();
        //Not use yet
    }


    @Override
    public Resources getResources() {
        if (remotePlugin == null)
            return super.getResources();
        return remotePlugin.from().pluginRes == null ? super.getResources() : remotePlugin.from().pluginRes;
    }

    @Override
    public Resources.Theme getTheme() {
        if (remotePlugin == null)
            return super.getTheme();
        return remotePlugin.getTheme() == null ? super.getTheme() : remotePlugin.getTheme();
    }

    @Override
    public AssetManager getAssets() {
        if (remotePlugin == null)
            return super.getAssets();
        return remotePlugin.from().pluginAssets == null ? super.getAssets() : remotePlugin.from().pluginAssets;
    }


    @Override
    public ClassLoader getClassLoader() {
        if (remotePlugin == null) {
            return super.getClassLoader();
        }
        if (remotePlugin.from().canUse()) {
            return remotePlugin.from().pluginLoader;
        }
        return super.getClassLoader();
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callOnResume();
            LCallbackManager.callAllOnResume();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (remotePlugin == null) {
            return;
        }

        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            //if(!meetBUG) {
            try {
                caller.callOnStop();
                LCallbackManager.callAllOnStop();
            } catch (Exception e) {
                meetBUG = true;
                processError(e);
            }
            // }
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                try {
                    caller.callOnDestroy();
                    LCallbackManager.callAllOnDestroy();
                } catch (Exception e) {
                    meetBUG = true;
                    processError(e);
                }
            }
        }


    }

    @Override
    protected void onPause() {
        super.onPause();
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                try {
                    caller.callOnPause();
                    LCallbackManager.callAllOnPause();
                } catch (Exception e) {
                    meetBUG = true;
                    processError(e);
                }
            }
        }
    }


    //Lody~
    //FIX ME:序列化和反序列化暂时工作不正常，
    //原因是序列化使用的类加载器不包含插件的类。
    //

/*    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                try {
                    caller.callOnSaveInstanceState(outState);
                    LCallbackManager.callAllOnSaveInstanceState(outState);
                } catch (Exception e) {
                    meetBUG = true;
                    processError(e);
                }
            }
        }
    }*/

/*    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                try {
                    caller.callOnRestoreInstanceState(savedInstanceState);
                    LCallbackManager.callAllOnRestoreInstanceState(savedInstanceState);
                } catch (Exception e) {
                    meetBUG = true;
                    processError(e);
                }

            }
        }

    }*/

    @Override
    public void onBackPressed() {

        if (remotePlugin == null) {
            super.onBackPressed();
        }
        if (meetBUG) {
            //这种情况下，应该立即退出插件，不然Activity将会阻塞
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);

        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            try {
                caller.callOnBackPressed();
                LCallbackManager.callAllOnBackPressed();
            } catch (Exception e) {
                meetBUG = true;
                processError(e);
            }

        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                try {
                    caller.callOnStop();
                    LCallbackManager.callAllOnStop();
                } catch (Exception e) {
                    meetBUG = true;
                    processError(e);
                }

            }
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (remotePlugin == null) {
            return;
        }

        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            try {
                caller.callOnRestart();
                LCallbackManager.callAllOnRestart();
            } catch (Exception e) {
                meetBUG = true;
                processError(e);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remotePlugin == null) {
            return super.onKeyDown(keyCode, event);
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            if (!meetBUG) {
                LCallbackManager.callAllOnKeyDown(keyCode, event);
                return caller.callOnKeyDown(keyCode, event);
            }
        }

        return super.onKeyDown(keyCode, event);
    }


    @Override
    public ComponentName startService(Intent service) {
        //TODO:转移Service跳转目标
        //暂未实现，实现只是时间问题


        return super.startService(service);
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callDump(prefix, fd, writer, args);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callOnConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callOnPostResume();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callOnDetachedFromWindow();
        }
        super.onDetachedFromWindow();
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        if (remotePlugin == null) {
            return super.onCreateView(name, context, attrs);
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            return caller.callOnCreateView(name, context, attrs);
        }
        return super.onCreateView(name, context, attrs);
    }

    @Override
    public View onCreateView(View parent, String name, Context context,
                             AttributeSet attrs) {
        if (remotePlugin == null) {
            return super.onCreateView(parent, name, context, attrs);
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            return caller.callOnCreateView(parent, name, context, attrs);
        }
        return super.onCreateView(parent, name, context, attrs);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (remotePlugin == null) {
            return;
        }
        PluginActivityCallback caller = remotePlugin.getControl();
        if (caller != null) {
            caller.callOnNewIntent(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (remotePlugin == null) {
            return;
        }
        remotePlugin.getControl().getPluginRef().call("onActivityResult", requestCode, resultCode, data);
    }


}
