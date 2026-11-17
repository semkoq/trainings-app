package de.semkoq.trainingsapp;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HrPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
