package com.factory.mes;
import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bridge.getWebView().loadUrl(bridge.getLocalUrl() + "#/" + BuildConfig.MES_MODE);
    }
}
