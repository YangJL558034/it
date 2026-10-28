package com.factory.mes;

import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

  @Override
  public void onCreate(Bundle state) {
    registerPlugin(StationVaultPlugin.class);
    registerPlugin(StationFeedbackPlugin.class);
    registerPlugin(SopPdfPlugin.class);
    super.onCreate(state);
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // Entry is set before the first WebView load via build-flavor config.
  }
}
