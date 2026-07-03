package com.xuewu.KLink;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * KLink 主 Activity — WebView 壳，夺舍原游戏入口。
 * 加载 assets/index.html 作为控制面板。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private KLinkBridge bridge;

    private static final String GAME_ACTIVITY = "com.epicgames.unreal.SplashActivity";
    private static final int FILE_SELECT_CODE = 100;
    private static final int THEME_FILE_SELECT_CODE = 101;
    private static final int THEME_SAVE_CODE = 102;
    private static final int BG_IMAGE_SELECT_CODE = 103;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFullscreen();

        webView = new WebView(this);
        setupWebView();

        bridge = new KLinkBridge(this, webView);
        webView.addJavascriptInterface(bridge, "KLink");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setFullscreen() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                bridge.onWebViewReady();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 打开文件选择器，用于安装模组。
     */
    public void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_SELECT_CODE);
    }

    /**
     * 打开文件选择器，用于导入主题 JSON。
     */
    public void pickThemeFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String[] mimeTypes = {"application/json", "text/plain", "text/json"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, THEME_FILE_SELECT_CODE);
    }

    /**
     * 保存主题 JSON 到用户指定位置（通过系统文件选择器）。
     */
    public void saveThemeFile(String initialName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, initialName);
        startActivityForResult(intent, THEME_SAVE_CODE);
    }

    /**
     * 打开图片选择器，用于选取背景图片。
     */
    public void pickImageFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, BG_IMAGE_SELECT_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null || bridge == null) return;

        switch (requestCode) {
            case FILE_SELECT_CODE:
                bridge.onFilePicked(uri);
                break;
            case THEME_FILE_SELECT_CODE:
                bridge.onThemeFilePicked(uri);
                break;
            case THEME_SAVE_CODE:
                bridge.onThemeSaveUriReady(uri);
                break;
            case BG_IMAGE_SELECT_CODE:
                bridge.onBgImagePicked(uri);
                break;
        }
    }

    /**
     * 启动原游戏。
     */
    public void launchGame() {
        try {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), GAME_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动游戏: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // 不退出，最小化到后台
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.onDestroy();
        }
        super.onDestroy();
    }
}
