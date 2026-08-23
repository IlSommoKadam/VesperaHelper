package com.vaonis.vesperahelper;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Selects a Vespera and requests its system-wide Wi-Fi connection. */
public final class MainActivity extends Activity {
    static final String EXTRA_FROM_BOOT = "from_boot";
    private static final int LOCATION_REQUEST_CODE = 100;
    private static final int SCAN_PERMISSION_REQUEST_CODE = 102;
    static final int REQUEST_PICK_SWU = 210;
    private WifiManager wifiManager;
    private LocationManager locationManager;
    private VesperaDeviceStore deviceStore;
    private TextView versionLabel;
    private TextView status;
    private TextView vesperaInfo;
    private LinearLayout scanLegendRow;
    private TextView connectionInfo;
    private TextView singularityInfo;
    private TextView singularityTitle;
    private Button singularityStatusBar;
    private TextView configuredDeviceInfo;
    private TextView deviceTitle;
    private LinearLayout savedDevicePanel;
    private Button deviceStatusBar;
    private LinearLayout foundDevicesList;
    private EditText ssidInput;
    private EditText bssidInput;
    private EditText hostInput;
    private EditText portInput;
    private Button saveManual;
    private Button clearDevice;
    private Button locationSettings;
    private Button refresh;
    private Button connect;
    private Button disconnect;
    private Button checkInstrument;
    private Button restartSingularity;
    private TextView actionResult;
    private Spinner languageSpinner;
    private boolean languageSpinnerReady;
    private static final int TAB_WIFI = 0;
    private static final int TAB_PHOTOS = 1;
    private static final int TAB_TELESCOPE = 2;
    private static final int TAB_SYSTEM = 3;
    private static final int TAB_TELEGRAM = 4;

    private Button tabWifi;
    private Button tabPhotos;
    private Button tabTelescope;
    private Button tabSystem;
    private Button tabTelegram;
    private FixedScrollView wifiScroll;
    private TelescopePanel telescopePanel;
    private PhotoPanel photoPanel;
    private SystemPanel systemPanel;
    private TelegramPanel telegramPanel;
    private int currentTab = TAB_WIFI;
    /** True when the saved instrument is currently seen in Wi-Fi scan. */
    private boolean savedDeviceOnline;
    /** True from Connect tap until the service reports a terminal status. */
    private boolean connectRequested;
    /** One-shot startup connection after the saved instrument appears in scan results. */
    private boolean autoConnectPending = true;
    private boolean portDiscoveryRunning;
    /** Bumped to cancel an in-flight port + Singularity verification. */
    private int verifyGeneration;
    private int lastDetectedApiPort = -1;
    private static final int PORT_PROBE_ATTEMPTS = 3;
    private static final long PORT_PROBE_INITIAL_DELAY_MS = 1_500;
    private static final long PORT_PROBE_RETRY_DELAY_MS = 1_000;
    private static final int COLOR_OFFLINE = UiStyle.STEEL;
    private static final int COLOR_DETECTED = UiStyle.AMBER;
    private static final int COLOR_CONNECTING = UiStyle.STEEL_BLUE;
    private static final int COLOR_CONNECTED = UiStyle.GREEN;
    private static final int COLOR_ACTION = UiStyle.SLATE;
    private static final int COLOR_DANGER = UiStyle.ROSE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private InstrumentWatchdog instrumentWatchdog;
    private boolean scanReceiverRegistered;
    private final BroadcastReceiver scanResultsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            showVesperaScanResult(updated);
        }
    };
    private boolean statusReceiverRegistered;
    private boolean instrumentStatusReceiverRegistered;
    private final BroadcastReceiver connectionStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (wifiScroll != null) wifiScroll.pin();
            String connectionStatus = intent.getStringExtra(VesperaConnectionService.EXTRA_STATUS);
            if (connectionStatus != null) {
                setConnectionState(connectionStatus);
                if (VesperaConnectionService.STATUS_CONNECTED.equals(connectionStatus)) {
                    VesperaConnectionService.requestDaemonRoute(MainActivity.this);
                    show(getString(R.string.connected_to,
                            deviceStore.getModel(), deviceStore.getSsid()));
                    restoreInstrumentStatusIfKnown();
                    verifyApiPorts(true);
                    startWatchdogIfConnected();
                    notifyTelescopePanel();
                } else if (VesperaConnectionService.STATUS_DISCONNECTED.equals(connectionStatus)
                        || VesperaConnectionService.STATUS_LOST.equals(connectionStatus)
                        || VesperaConnectionService.STATUS_UNAVAILABLE.equals(connectionStatus)) {
                    lastDetectedApiPort = -1;
                    VesperaPortScanner.clear();
                    if (telescopePanel != null) {
                        telescopePanel.clearPortInventory();
                    }
                    cancelPortVerification();
                    stopWatchdog();
                    InstrumentWatchdog.clearSnapshot();
                    resetSingularityStatusUi();
                    clearStaleReachabilityStatus(connectionStatus);
                }
            }
        }
    };
    private final BroadcastReceiver instrumentStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (wifiScroll != null) wifiScroll.pin();
            String message = intent.getStringExtra(InstrumentWatchdog.EXTRA_MESSAGE);
            if (message == null) return;
            boolean detected = intent.getBooleanExtra(InstrumentWatchdog.EXTRA_DETECTED, false);
            int port = intent.getIntExtra(InstrumentWatchdog.EXTRA_PORT, -1);
            String statusCode = intent.getStringExtra(InstrumentWatchdog.EXTRA_STATUS);
            if (statusCode == null) {
                statusCode = detected
                        ? SingularityDetector.Status.CONNECTED.name()
                        : InstrumentWatchdog.STATUS_IDLE;
            }
            boolean manual = intent.getBooleanExtra(InstrumentWatchdog.EXTRA_MANUAL, false);
            showInstrumentStatus(message, detected, port, statusCode, manual);
        }
    };

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifiManager = getSystemService(WifiManager.class);
        locationManager = getSystemService(LocationManager.class);
        deviceStore = VesperaDeviceStore.from(this);
        buildUi();
        refreshConfiguredDeviceLabel();
        PhotoSyncService.onAppOpened(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask: reopening from launcher skips onCreate — still re-evaluate HD policy.
        PhotoSyncService.onAppOpened(this);
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        int bottomPadding = (int) (120 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(padding, padding, padding, padding);
        header.setBackgroundColor(0xFFE8EEF4);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        versionLabel = new TextView(this);
        versionLabel.setText(getString(R.string.app_name) + " " + appVersionLabel());
        versionLabel.setTextSize(16);
        versionLabel.setTypeface(versionLabel.getTypeface(), android.graphics.Typeface.BOLD);
        versionLabel.setTextColor(0xFF1A237E);
        versionLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        languageSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(
                this, R.array.language_entries, android.R.layout.simple_spinner_item);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setSelection(AppLocale.indexOf(AppLocale.getLanguage(this)), false);
        languageSpinner.setBackgroundResource(R.drawable.bg_language_spinner);
        int hPad = (int) (10 * density);
        int vPad = (int) (6 * density);
        languageSpinner.setPaddingRelative(hPad, vPad, (int) (28 * density), vPad);
        languageSpinner.setMinimumWidth((int) (110 * density));
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        languageSpinner.setLayoutParams(langLp);
        languageSpinnerReady = true;
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                                 int position, long id) {
                if (!languageSpinnerReady) return;
                String selected = AppLocale.languageAt(position);
                if (selected.equals(AppLocale.getLanguage(MainActivity.this))) return;
                AppLocale.setLanguage(MainActivity.this, selected);
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        header.addView(versionLabel);
        header.addView(languageSpinner);

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(0xFF90A4AE);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (2 * density))));

        LinearLayout tabBar = buildTabBar(density);

        wifiScroll = new FixedScrollView(this);
        wifiScroll.setVerticalScrollBarEnabled(true);
        wifiScroll.setScrollbarFadingEnabled(false);
        wifiScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, padding / 2, padding, bottomPadding);
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText(R.string.status_configure);
        connectionInfo = new TextView(this);
        connectionInfo.setText(getString(R.string.connection_state,
                StatusTexts.connection(this, VesperaConnectionService.STATUS_DISCONNECTED)));
        singularityInfo = new TextView(this);
        singularityInfo.setText(getString(R.string.singularity_state,
                StatusTexts.singularity(this, InstrumentWatchdog.STATUS_IDLE)));
        singularityTitle = new TextView(this);
        singularityTitle.setText(R.string.singularity_section);
        singularityTitle.setTypeface(singularityTitle.getTypeface(), android.graphics.Typeface.BOLD);
        singularityTitle.setPadding(0, (int) (10 * density), 0, (int) (4 * density));
        singularityStatusBar = new Button(this);
        singularityStatusBar.setAllCaps(false);
        showIdleSingularityBar();
        configuredDeviceInfo = new TextView(this);
        vesperaInfo = new TextView(this);
        vesperaInfo.setText(R.string.scan_hint);
        scanLegendRow = buildScanLegendRow(density);
        deviceStatusBar = new Button(this);
        deviceStatusBar.setAllCaps(false);
        showIdleDeviceBar();
        foundDevicesList = new LinearLayout(this);
        foundDevicesList.setOrientation(LinearLayout.VERTICAL);

        deviceTitle = new TextView(this);
        deviceTitle.setText(R.string.device_empty_state);

        savedDevicePanel = new LinearLayout(this);
        savedDevicePanel.setOrientation(LinearLayout.VERTICAL);
        int panelPad = (int) (12 * density);
        savedDevicePanel.setPadding(panelPad, panelPad, panelPad, panelPad);
        savedDevicePanel.setBackgroundColor(0xFFDCEBFA);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.START;
        panelLp.topMargin = (int) (4 * density);
        panelLp.bottomMargin = (int) (8 * density);
        savedDevicePanel.setLayoutParams(panelLp);
        savedDevicePanel.addView(configuredDeviceInfo);

        ssidInput = new EditText(this);
        ssidInput.setHint(R.string.hint_ssid);
        ssidInput.setText(deviceStore.getSsid());
        ssidInput.setSingleLine(true);
        bssidInput = new EditText(this);
        bssidInput.setHint(R.string.hint_bssid);
        bssidInput.setText(deviceStore.getBssid());
        bssidInput.setSingleLine(true);
        saveManual = new Button(this);
        saveManual.setText(R.string.btn_save_manual);
        saveManual.setOnClickListener(v -> saveManualDevice());
        styleRaisedButton(saveManual, COLOR_ACTION, true);
        clearDevice = new Button(this);
        clearDevice.setText(R.string.btn_clear_device);
        clearDevice.setOnClickListener(v -> {
            deviceStore.clear();
            ssidInput.setText("");
            bssidInput.setText("");
            refreshConfiguredDeviceLabel();
            show(getString(R.string.device_cleared));
            showVesperaScanResult(false);
        });
        styleRaisedButton(clearDevice, UiStyle.ROSE, true);
        locationSettings = new Button(this);
        locationSettings.setText(R.string.btn_location);
        locationSettings.setOnClickListener(v -> openLocationSettings());
        styleRaisedButton(locationSettings, COLOR_ACTION, true);
        refresh = new Button(this);
        refresh.setText(R.string.btn_scan);
        refresh.setOnClickListener(v -> refreshVesperaScan());
        styleRaisedButton(refresh, COLOR_ACTION, true);
        connect = new Button(this);
        connect.setText(R.string.btn_connect);
        connect.setOnClickListener(v -> connect());
        disconnect = new Button(this);
        disconnect.setText(R.string.btn_disconnect);
        disconnect.setOnClickListener(v -> disconnect());
        hostInput = new EditText(this);
        hostInput.setHint(R.string.hint_host);
        hostInput.setText("10.0.0.1");
        hostInput.setSingleLine(true);
        portInput = new EditText(this);
        portInput.setHint(R.string.hint_port);
        portInput.setText("8083");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setSingleLine(true);
        checkInstrument = new Button(this);
        checkInstrument.setText(R.string.btn_check_instrument);
        checkInstrument.setOnClickListener(v -> requestInstrumentCheck());
        styleRaisedButton(checkInstrument, COLOR_ACTION, true);
        restartSingularity = new Button(this);
        restartSingularity.setText(R.string.btn_restart_singularity);
        restartSingularity.setOnClickListener(v -> requestSingularityRestart());
        styleRaisedButton(restartSingularity, UiStyle.TERRACOTTA, true);
        actionResult = new TextView(this);
        actionResult.setText(R.string.action_result_idle);
        refreshConnectButtons();
        layout.addView(status);
        layout.addView(connectionInfo);
        layout.addView(deviceTitle);
        layout.addView(savedDevicePanel);
        layout.addView(ssidInput);
        layout.addView(bssidInput);
        layout.addView(saveManual);
        layout.addView(clearDevice);
        layout.addView(locationSettings);
        layout.addView(refresh);
        layout.addView(vesperaInfo);
        layout.addView(scanLegendRow);
        layout.addView(deviceStatusBar);
        layout.addView(foundDevicesList);
        layout.addView(singularityTitle);
        layout.addView(singularityInfo);
        layout.addView(singularityStatusBar);

        int actionGap = (int) (12 * density);
        View actionDivider = new View(this);
        actionDivider.setBackgroundColor(0xFFCFD8DC);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (1 * density)));
        dividerLp.topMargin = actionGap;
        dividerLp.bottomMargin = actionGap;
        actionDivider.setLayoutParams(dividerLp);
        layout.addView(actionDivider);

        layout.addView(connect);
        layout.addView(disconnect);
        layout.addView(hostInput);
        layout.addView(portInput);
        layout.addView(checkInstrument);
        layout.addView(restartSingularity);
        layout.addView(actionResult);
        wifiScroll.addView(layout);
        wifiScroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetBottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insetBottom);
            return insets.consumeSystemWindowInsets();
        });

        telescopePanel = new TelescopePanel(this, density, padding);
        photoPanel = new PhotoPanel(this, density, padding);
        systemPanel = new SystemPanel(this, density, padding);
        telegramPanel = new TelegramPanel(this, density, padding);

        root.addView(header);
        root.addView(headerDivider);
        root.addView(tabBar);
        root.addView(wifiScroll);
        root.addView(photoPanel.view());
        root.addView(telescopePanel.view());
        root.addView(systemPanel.view());
        root.addView(telegramPanel.view());
        setContentView(root);
        showTab(TAB_WIFI);
    }

    private LinearLayout buildTabBar(float density) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFFE8EEF4);
        int outerPad = (int) (8 * density);
        wrap.setPadding(outerPad, (int) (4 * density), outerPad, outerPad);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(UiStyle.tabStripWell(density));
        int inset = Math.max(2, (int) (2 * density));
        tabBar.setPadding(inset, inset, inset, inset);
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabWifi = new Button(this);
        tabWifi.setAllCaps(false);
        tabWifi.setGravity(Gravity.CENTER);
        tabWifi.setText(R.string.tab_wifi);
        tabWifi.setOnClickListener(v -> showTab(TAB_WIFI));
        tabPhotos = new Button(this);
        tabPhotos.setAllCaps(false);
        tabPhotos.setGravity(Gravity.CENTER);
        tabPhotos.setText(R.string.tab_photos);
        tabPhotos.setOnClickListener(v -> showTab(TAB_PHOTOS));
        tabTelescope = new Button(this);
        tabTelescope.setAllCaps(false);
        tabTelescope.setGravity(Gravity.CENTER);
        tabTelescope.setText(R.string.tab_telescope);
        tabTelescope.setOnClickListener(v -> showTab(TAB_TELESCOPE));
        tabSystem = new Button(this);
        tabSystem.setAllCaps(false);
        tabSystem.setGravity(Gravity.CENTER);
        tabSystem.setText(R.string.tab_system);
        tabSystem.setOnClickListener(v -> showTab(TAB_SYSTEM));
        tabTelegram = new Button(this);
        tabTelegram.setAllCaps(false);
        tabTelegram.setGravity(Gravity.CENTER);
        tabTelegram.setText(R.string.tab_telegram);
        tabTelegram.setOnClickListener(v -> showTab(TAB_TELEGRAM));

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabWifi.setLayoutParams(tabLp);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabPhotos.setLayoutParams(tabLp2);
        LinearLayout.LayoutParams tabLp3 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabTelescope.setLayoutParams(tabLp3);
        LinearLayout.LayoutParams tabLp4 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabSystem.setLayoutParams(tabLp4);
        LinearLayout.LayoutParams tabLp5 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabTelegram.setLayoutParams(tabLp5);
        tabBar.addView(tabWifi);
        tabBar.addView(tabDivider(density));
        tabBar.addView(tabPhotos);
        tabBar.addView(tabDivider(density));
        tabBar.addView(tabTelescope);
        tabBar.addView(tabDivider(density));
        tabBar.addView(tabSystem);
        tabBar.addView(tabDivider(density));
        tabBar.addView(tabTelegram);
        wrap.addView(tabBar);
        return wrap;
    }

    private View tabDivider(float density) {
        View line = new View(this);
        line.setBackgroundColor(UiStyle.TAB_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.max(2, Math.round(2 * density)), ViewGroup.LayoutParams.MATCH_PARENT);
        int v = Math.round(8 * density);
        lp.topMargin = v;
        lp.bottomMargin = v;
        line.setLayoutParams(lp);
        return line;
    }

    private void showTab(int tab) {
        if (currentTab == TAB_TELESCOPE && tab != TAB_TELESCOPE && telescopePanel != null) {
            telescopePanel.onHidden();
        }
        if (currentTab == TAB_PHOTOS && tab != TAB_PHOTOS && photoPanel != null) {
            photoPanel.onPause();
        }
        if (currentTab == TAB_SYSTEM && tab != TAB_SYSTEM && systemPanel != null) {
            systemPanel.onHidden();
        }
        if (currentTab == TAB_TELEGRAM && tab != TAB_TELEGRAM && telegramPanel != null) {
            telegramPanel.onHidden();
        }
        currentTab = tab;
        if (wifiScroll != null) {
            wifiScroll.setVisibility(tab == TAB_WIFI ? View.VISIBLE : View.GONE);
        }
        if (photoPanel != null) {
            photoPanel.view().setVisibility(tab == TAB_PHOTOS ? View.VISIBLE : View.GONE);
        }
        if (telescopePanel != null) {
            telescopePanel.view().setVisibility(tab == TAB_TELESCOPE ? View.VISIBLE : View.GONE);
        }
        if (systemPanel != null) {
            systemPanel.view().setVisibility(tab == TAB_SYSTEM ? View.VISIBLE : View.GONE);
        }
        if (telegramPanel != null) {
            telegramPanel.view().setVisibility(tab == TAB_TELEGRAM ? View.VISIBLE : View.GONE);
        }
        styleTab(tabWifi, tab == TAB_WIFI, true, false);
        styleTab(tabPhotos, tab == TAB_PHOTOS, false, false);
        styleTab(tabTelescope, tab == TAB_TELESCOPE, false, false);
        styleTab(tabSystem, tab == TAB_SYSTEM, false, false);
        styleTab(tabTelegram, tab == TAB_TELEGRAM, false, true);
        if (tab == TAB_PHOTOS && photoPanel != null) {
            photoPanel.onResume();
        } else if (tab == TAB_TELESCOPE && telescopePanel != null) {
            syncTelescopePanelHost();
            telescopePanel.onVisible();
        } else if (tab == TAB_SYSTEM && systemPanel != null) {
            systemPanel.onVisible();
        } else if (tab == TAB_TELEGRAM && telegramPanel != null) {
            telegramPanel.onVisible();
        }
    }

    private void syncTelescopePanelHost() {
        if (telescopePanel == null) return;
        String host = hostInput != null ? hostInput.getText().toString().trim() : "";
        if (host.isEmpty()) host = "10.0.0.1";
        telescopePanel.setHost(host);
        telescopePanel.setApiPort(lastDetectedApiPort);
    }

    private void notifyTelescopePanel() {
        if (telescopePanel == null) return;
        syncTelescopePanelHost();
        telescopePanel.onConnectionChanged();
    }

    private void updatePortInventoryUi(VesperaPortScan scan) {
        if (telescopePanel != null) {
            telescopePanel.updatePortInventory(scan);
        }
        if (photoPanel != null) {
            photoPanel.refreshUserFolderHint();
        }
    }

    private void styleTab(Button tab, boolean selected, boolean roundLeft, boolean roundRight) {
        if (tab == null) return;
        tab.setAllCaps(false);
        tab.setGravity(Gravity.CENTER);
        UiStyle.applyTab(tab, COLOR_CONNECTED, selected, roundLeft, roundRight);
        tab.setTextSize(12);
        tab.setMaxLines(2);
        tab.setTypeface(tab.getTypeface(), selected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private String appVersionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "v?";
        }
    }

    private void refreshConfiguredDeviceLabel() {
        if (deviceStore.isConfigured()) {
            String model = deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel();
            deviceTitle.setText(getString(R.string.device_saved_state, model));
            String freq = deviceStore.getFrequencyMhz() > 0
                    ? getString(R.string.channel_mhz, deviceStore.getFrequencyMhz())
                    : getString(R.string.channel_auto);
            configuredDeviceInfo.setText(getString(R.string.device_detail_body,
                    deviceStore.getSsid(), deviceStore.getBssid(), freq));
            savedDevicePanel.setVisibility(View.VISIBLE);
            savedDevicePanel.setBackgroundColor(0xFFDCEBFA);
            status.setText(getString(R.string.ready_for, deviceStore.getModel(), deviceStore.getSsid()));
        } else {
            deviceTitle.setText(R.string.device_empty_state);
            configuredDeviceInfo.setText(R.string.not_saved_label);
            savedDevicePanel.setVisibility(View.GONE);
            status.setText(R.string.status_configure);
        }
    }

    private void saveManualDevice() {
        autoConnectPending = false;
        String ssid = ssidInput.getText().toString().trim();
        String bssid = bssidInput.getText().toString().trim();
        if (!VesperaDeviceStore.isVesperaSsid(ssid)) {
            show(getString(R.string.ssid_invalid));
            return;
        }
        if (!bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
            show(getString(R.string.bssid_invalid));
            return;
        }
        deviceStore.save(ssid, bssid, deviceStore.getFrequencyMhz());
        refreshConfiguredDeviceLabel();
        show(getString(R.string.device_saved, deviceStore.getModel(), ssid));
        showVesperaScanResult(false);
    }

    private void selectScannedDevice(ScanResult result) {
        autoConnectPending = false;
        deviceStore.saveFromScan(result);
        ssidInput.setText(result.SSID);
        bssidInput.setText(result.BSSID == null ? "" : result.BSSID.toLowerCase(Locale.US));
        refreshConfiguredDeviceLabel();
        show(getString(R.string.device_selected,
                VesperaDeviceStore.guessModel(result.SSID), result.SSID));
        showVesperaScanResult(false);
    }

    private void refreshVesperaScan() {
        if (!hasWifiPermissions()) {
            vesperaInfo.setText(R.string.perm_requesting);
            requestWifiPermissions(SCAN_PERMISSION_REQUEST_CODE);
            return;
        }
        if (locationManager != null && !locationManager.isLocationEnabled()) {
            vesperaInfo.setText(getString(R.string.location_off, scanPrerequisites()));
            return;
        }
        if (isVesperaConnected()) {
            showVesperaScanResult(false);
            wifiManager.startScan();
            return;
        }
        vesperaInfo.setText(R.string.scanning);
        showSearchingDeviceBar();
        boolean accepted = wifiManager.startScan();
        if (!accepted) {
            vesperaInfo.setText(getString(R.string.scan_throttled, scanPrerequisites()));
            showVesperaScanResult(false);
        }
    }

    private void requestWifiPermissions(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            }, requestCode);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, requestCode);
        }
    }

    private void showVesperaScanResult(boolean freshResults) {
        List<ScanResult> found = new ArrayList<>();
        for (ScanResult result : wifiManager.getScanResults()) {
            if (VesperaDeviceStore.isVesperaSsid(result.SSID)) found.add(result);
        }
        Collections.sort(found, Comparator.comparingInt((ScanResult r) -> r.level).reversed());
        wifiScroll.pin();
        foundDevicesList.removeAllViews();

        ScanResult savedMatch = null;
        for (ScanResult result : found) {
            if (deviceStore.matchesScan(result)) {
                savedMatch = result;
                break;
            }
        }
        savedDeviceOnline = savedMatch != null;

        if (found.isEmpty()) {
            vesperaInfo.setText(getString(
                    freshResults ? R.string.scan_none_fresh : R.string.scan_none_cached,
                    scanPrerequisites()));
            if (isVesperaRequesting()) {
                showConnectingDeviceBar();
            } else if (deviceStore.isConfigured()) {
                showOfflineDeviceBar();
            } else {
                showIdleDeviceBar();
            }
            refreshConnectButtons();
            return;
        }
        vesperaInfo.setText(getString(
                freshResults ? R.string.scan_found_fresh : R.string.scan_found_cached,
                found.size()));

        // Fixed primary bar: prefer saved instrument, else strongest signal.
        ScanResult primary = savedMatch != null ? savedMatch : found.get(0);
        bindDeviceRow(deviceStatusBar, primary, true);

        for (ScanResult result : found) {
            if (result == primary) continue;
            Button pick = new Button(this);
            bindDeviceRow(pick, result, true);
            foundDevicesList.addView(pick);
        }
        if (!savedDeviceOnline && deviceStore.isConfigured()) {
            Button offline = new Button(this);
            offline.setAllCaps(false);
            offline.setEnabled(false);
            offline.setText(getString(R.string.device_offline_row,
                    deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel(),
                    deviceStore.getSsid(),
                    deviceStore.getBssid()));
            styleStatusBar(offline, COLOR_OFFLINE);
            foundDevicesList.addView(offline);
        }
        if (isVesperaRequesting()) {
            showConnectingDeviceBar();
        }
        refreshConnectButtons();
        maybeAutoConnect();
    }

    private void maybeAutoConnect() {
        if (!SystemSettingsStore.from(this).wifiConnect()) return;
        if (!autoConnectPending
                || !savedDeviceOnline
                || !deviceStore.isConfigured()
                || isVesperaConnected()
                || isVesperaRequesting()) {
            return;
        }
        autoConnectPending = false;
        SystemActivityLog.record(this, SystemActivityLog.KIND_WIFI, SystemActivityLog.DETAIL_OK);
        connect();
    }

    private LinearLayout buildScanLegendRow(float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int gap = (int) (6 * density);
        row.setPadding(0, (int) (4 * density), 0, (int) (6 * density));
        row.addView(legendChip(getString(R.string.legend_offline), COLOR_OFFLINE, density, gap));
        row.addView(legendChip(getString(R.string.legend_detected), COLOR_DETECTED, density, gap));
        row.addView(legendChip(getString(R.string.legend_connected), COLOR_CONNECTED, density, 0));
        return row;
    }

    private TextView legendChip(String label, int backgroundColor, float density, int marginEnd) {
        TextView chip = new TextView(this);
        int h = (int) (8 * density);
        int v = (int) (4 * density);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(h, v, h, v);
        UiStyle.applyRecessed(chip, backgroundColor);
        chip.setTextColor(UiStyle.textOn(backgroundColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(marginEnd);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void showSearchingDeviceBar() {
        if (deviceStatusBar == null) return;
        if (isVesperaRequesting()) {
            showConnectingDeviceBar();
            return;
        }
        if (isVesperaConnected()) {
            return;
        }
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(R.string.scan_bar_searching);
        styleStatusBar(deviceStatusBar, COLOR_OFFLINE);
    }

    private void showConnectingDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        String model = deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel();
        deviceStatusBar.setText(getString(R.string.scan_bar_connecting, model, deviceStore.getSsid()));
        styleStatusBar(deviceStatusBar, COLOR_CONNECTING);
    }

    private void showIdleDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(R.string.scan_bar_idle);
        styleStatusBar(deviceStatusBar, COLOR_OFFLINE);
    }

    private void showOfflineDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(getString(R.string.device_offline_row,
                deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel(),
                deviceStore.getSsid(),
                deviceStore.getBssid()));
        styleStatusBar(deviceStatusBar, COLOR_OFFLINE);
    }

    private void bindDeviceRow(Button row, ScanResult result, boolean clickable) {
        int bars = WifiManager.calculateSignalLevel(result.level, 5) + 1;
        String model = VesperaDeviceStore.guessModel(result.SSID);
        boolean saved = deviceStore.matchesScan(result);
        boolean connected = saved && isVesperaConnected();
        String marker = connected ? "✓ " : (saved ? "● " : "");
        row.setAllCaps(false);
        row.setText(marker + model + "\n" + result.SSID
                + "\n" + result.BSSID + " · " + result.level + " dBm (" + bars + "/5) · "
                + result.frequency + " MHz");
        styleDeviceRow(row, connected ? COLOR_CONNECTED : COLOR_DETECTED, clickable && !connected);
        if (clickable && !connected) {
            row.setEnabled(true);
            row.setOnClickListener(v -> selectScannedDevice(result));
        } else {
            row.setEnabled(false);
            row.setOnClickListener(null);
        }
    }

    private void styleStatusBar(TextView row, int backgroundColor) {
        row.setAllCaps(false);
        UiStyle.applyRecessed(row, backgroundColor);
    }

    private void styleDeviceRow(Button row, int backgroundColor, boolean asButton) {
        row.setAllCaps(false);
        if (asButton) {
            styleRaisedButton(row, backgroundColor, true);
        } else {
            styleStatusBar(row, backgroundColor);
        }
    }

    private boolean isVesperaConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())
                && VesperaConnectionService.getActiveNetwork() != null;
    }

    private boolean isVesperaRequesting() {
        if (connectRequested) return true;
        String statusCode = VesperaConnectionService.getLastStatus();
        return statusCode != null && statusCode.startsWith(VesperaConnectionService.STATUS_REQUESTING);
    }

    private boolean hasWifiPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception unavailable) {
            show(getString(R.string.location_settings_fail));
        }
    }

    private String scanPrerequisites() {
        boolean locationEnabled = locationManager != null && locationManager.isLocationEnabled();
        return getString(R.string.prereq_summary,
                getString(wifiManager.isWifiEnabled() ? R.string.prereq_wifi_on : R.string.prereq_wifi_off),
                getString(locationEnabled ? R.string.prereq_loc_on : R.string.prereq_loc_off),
                getString(hasWifiPermissions() ? R.string.prereq_perm_ok : R.string.prereq_perm_missing));
    }

    private void connect() {
        if (!hasWifiPermissions()) {
            requestWifiPermissions(LOCATION_REQUEST_CODE);
            return;
        }
        if (!deviceStore.isConfigured()) {
            String ssid = ssidInput.getText().toString().trim();
            String bssid = bssidInput.getText().toString().trim();
            if (VesperaDeviceStore.isVesperaSsid(ssid)
                    && bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
                deviceStore.save(ssid, bssid, deviceStore.getFrequencyMhz());
                refreshConfiguredDeviceLabel();
            } else {
                show(getString(R.string.select_device_first));
                return;
            }
        }
        status.setText(getString(R.string.connecting_to,
                deviceStore.getModel(), deviceStore.getSsid()));
        connectRequested = true;
        showConnectingDeviceBar();
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_CONNECT);
        startForegroundService(service);
        refreshConnectButtons();
    }

    /** Full port inventory (FTP 21/2121/… + API 8082/8083) and API port for Singularity. */
    private void verifyApiPorts(boolean waitForNetwork) {
        if (!isVesperaConnected()) {
            actionResult.setText(getString(R.string.action_result,
                    getString(R.string.no_vespera_network)));
            if (telescopePanel != null) {
                telescopePanel.clearPortInventory();
            }
            return;
        }
        if (portDiscoveryRunning) {
            actionResult.setText(R.string.api_port_busy);
            return;
        }
        portDiscoveryRunning = true;
        final int generation = ++verifyGeneration;
        actionResult.setText(R.string.port_scan_running);
        if (telescopePanel != null) {
            telescopePanel.showPortScanRunning();
        }
        VesperaConnectionService.requestDaemonRoute(this);
        final String host = hostInput.getText().toString().trim().isEmpty()
                ? "10.0.0.1" : hostInput.getText().toString().trim();
        probeExecutor.execute(() -> {
            try {
                if (waitForNetwork) sleepQuietly(PORT_PROBE_INITIAL_DELAY_MS);
                VesperaPortScan scan = null;
                int detectedPort = -1;
                for (int attempt = 0; attempt < PORT_PROBE_ATTEMPTS; attempt++) {
                    if (generation != verifyGeneration) return;
                    if (!isVesperaConnected()) break;
                    Network network = VesperaConnectionService.getActiveNetwork();
                    if (network == null) break;
                    if (attempt > 0) sleepQuietly(PORT_PROBE_RETRY_DELAY_MS);
                    scan = VesperaPortScanner.scan(network, host);
                    detectedPort = scan.apiRestPort > 0 ? scan.apiRestPort : scan.apiSocketPort;
                    if (detectedPort <= 0) {
                        detectedPort = InstrumentWatchdog.probeApiPort(
                                MainActivity.this, network, attempt == 0);
                    }
                    if (detectedPort > 0 || scan.openCount() > 0) break;
                }
                final VesperaPortScan finalScan = scan;
                final int result = detectedPort;
                mainHandler.post(() -> {
                    if (generation != verifyGeneration) return;
                    if (!isVesperaConnected()) return;
                    if (finalScan != null) {
                        updatePortInventoryUi(finalScan);
                    }
                    applyDetectedPort(result);
                    if (result > 0) {
                        actionResult.setText(getString(R.string.port_scan_done_api, result));
                    } else if (finalScan != null && finalScan.openCount() > 0) {
                        actionResult.setText(getString(R.string.port_scan_done_partial,
                                finalScan.openCount()));
                    } else {
                        actionResult.setText(R.string.api_port_not_found);
                    }
                });
            } finally {
                mainHandler.post(() -> {
                    if (generation == verifyGeneration) portDiscoveryRunning = false;
                });
            }
        });
    }

    private void applyDetectedPort(int port) {
        if (port <= 0) return;
        lastDetectedApiPort = port;
        InstrumentWatchdog.rememberPort(port);
        hostInput.setText("10.0.0.1");
        portInput.setText(String.valueOf(port));
        notifyTelescopePanel();
    }

    private void cancelPortVerification() {
        verifyGeneration++;
        portDiscoveryRunning = false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void requestInstrumentCheck() {
        ensureWatchdog().requestManualCheck();
    }

    private void requestSingularityRestart() {
        ensureWatchdog().requestManualRestart();
    }

    private InstrumentWatchdog ensureWatchdog() {
        if (instrumentWatchdog == null) {
            instrumentWatchdog = new InstrumentWatchdog(this);
        }
        return instrumentWatchdog;
    }

    private void startWatchdogIfConnected() {
        if (!SystemSettingsStore.from(this).watchdog()) {
            stopWatchdog();
            return;
        }
        if (isVesperaConnected()) {
            ensureWatchdog().start();
        }
    }

    void onSystemSettingsSaved() {
        startWatchdogIfConnected();
    }

    private void stopWatchdog() {
        if (instrumentWatchdog != null) {
            instrumentWatchdog.stop();
        }
    }

    private void restoreInstrumentStatusIfKnown() {
        InstrumentWatchdog.Snapshot snap = InstrumentWatchdog.lastSnapshot();
        if (snap == null) return;
        showInstrumentStatus(snap.message, snap.detected, snap.port, snap.status, false);
    }

    private void resetSingularityStatusUi() {
        showInstrumentStatus(getString(R.string.watchdog_not_connected), false, -1,
                InstrumentWatchdog.STATUS_IDLE, true);
    }

    private void showInstrumentStatus(String message, boolean detected, int port, String statusCode,
                                      boolean updateActionResult) {
        mainHandler.post(() -> {
            applyDetectedPort(port);
            if (updateActionResult) {
                actionResult.setText(getString(R.string.action_result, message));
            }
            if (singularityInfo != null) {
                singularityInfo.setText(getString(R.string.singularity_state,
                        StatusTexts.singularity(this, statusCode)));
            }
            updateSingularityStatusBar(statusCode);
            notifyTelescopePanel();
        });
    }

    private void showIdleSingularityBar() {
        updateSingularityStatusBar(InstrumentWatchdog.STATUS_IDLE);
    }

    private void updateSingularityStatusBar(String statusCode) {
        if (singularityStatusBar == null) return;
        singularityStatusBar.setEnabled(false);
        singularityStatusBar.setOnClickListener(null);
        if (statusCode == null || InstrumentWatchdog.STATUS_IDLE.equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_idle);
            styleStatusBar(singularityStatusBar, COLOR_OFFLINE);
            return;
        }
        if (InstrumentWatchdog.STATUS_CHECKING.equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_checking);
            styleStatusBar(singularityStatusBar, COLOR_CONNECTING);
            return;
        }
        if (InstrumentWatchdog.STATUS_RECOVERING.equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_recovering);
            styleStatusBar(singularityStatusBar, COLOR_CONNECTING);
            return;
        }
        if (InstrumentWatchdog.STATUS_STARTING.equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_starting);
            styleStatusBar(singularityStatusBar, COLOR_CONNECTING);
            return;
        }
        if (SingularityDetector.Status.CONNECTED.name().equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_connected);
            styleStatusBar(singularityStatusBar, COLOR_CONNECTED);
            return;
        }
        if (SingularityDetector.Status.NOT_RUNNING.name().equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_not_running);
            styleStatusBar(singularityStatusBar, COLOR_DETECTED);
            return;
        }
        if (SingularityDetector.Status.API_DOWN.name().equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_api_down);
            styleStatusBar(singularityStatusBar, COLOR_DETECTED);
            return;
        }
        if (SingularityDetector.Status.NO_WIFI.name().equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_no_wifi);
            styleStatusBar(singularityStatusBar, COLOR_OFFLINE);
            return;
        }
        if (SingularityDetector.Status.DAEMON_MISSING.name().equals(statusCode)) {
            singularityStatusBar.setText(R.string.singularity_bar_no_daemon);
            styleStatusBar(singularityStatusBar, COLOR_OFFLINE);
            return;
        }
        singularityStatusBar.setText(R.string.singularity_bar_disconnected);
        styleStatusBar(singularityStatusBar, COLOR_DETECTED);
    }

    private void verifyReachability() {
        Network network = VesperaConnectionService.getActiveNetwork();
        if (network == null) {
            showVerification(getString(R.string.no_vespera_network));
            return;
        }
        String host = hostInput.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalidPort) {
            showVerification(getString(R.string.invalid_port));
            return;
        }
        showVerification(getString(R.string.verifying, host, port));
        probeExecutor.execute(() -> {
            try (Socket socket = VesperaSockets.create(network)) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
                showVerification(getString(R.string.reachable, host, port));
            } catch (IOException failure) {
                showVerification(getString(R.string.not_reachable,
                        host, port, failure.getClass().getSimpleName()));
            }
        });
    }

    private void disconnect() {
        autoConnectPending = false;
        connectRequested = false;
        lastDetectedApiPort = -1;
        VesperaPortScanner.clear();
        if (telescopePanel != null) {
            telescopePanel.clearPortInventory();
        }
        cancelPortVerification();
        stopWatchdog();
        InstrumentWatchdog.clearSnapshot();
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_DISCONNECT);
        startService(service);
        setConnectionState(VesperaConnectionService.STATUS_DISCONNECTED);
        resetSingularityStatusUi();
        clearStaleReachabilityStatus(VesperaConnectionService.STATUS_DISCONNECTED);
    }

    private void clearStaleReachabilityStatus(String connectionStatus) {
        String device = deviceStore.isConfigured() ? (" (" + deviceStore.getSsid() + ")") : "";
        if (VesperaConnectionService.STATUS_LOST.equals(connectionStatus)) {
            show(getString(R.string.stale_lost, device));
        } else {
            show(getString(R.string.stale_disconnected, device));
        }
    }

    private void show(String message) {
        mainHandler.post(() -> status.setText(message));
    }

    private void showVerification(String message) {
        mainHandler.post(() -> {
            status.setText(message);
            actionResult.setText(getString(R.string.action_result, message));
        });
    }

    private void setConnectionState(String code) {
        mainHandler.post(() -> {
            if (code == null || !code.startsWith(VesperaConnectionService.STATUS_REQUESTING)) {
                connectRequested = false;
            }
            connectionInfo.setText(getString(R.string.connection_state,
                    StatusTexts.connection(this, code)));
            if (VesperaConnectionService.STATUS_CONNECTED.equals(code)) {
                status.setText(getString(R.string.connected_to,
                        deviceStore.getModel(), deviceStore.getSsid()));
            } else if (!VesperaConnectionService.STATUS_CONNECTED.equals(code)
                    && (code == null
                    || !code.startsWith(VesperaConnectionService.STATUS_REQUESTING))) {
                if (singularityInfo != null) {
                    singularityInfo.setText(getString(R.string.singularity_state,
                            StatusTexts.singularity(this, InstrumentWatchdog.STATUS_IDLE)));
                }
                showIdleSingularityBar();
            }
            refreshConnectButtons();
            if (deviceStatusBar == null) return;
            if (code != null && code.startsWith(VesperaConnectionService.STATUS_REQUESTING)) {
                showConnectingDeviceBar();
            } else {
                showVesperaScanResult(false);
            }
        });
    }

    /**
     * Like system Wi-Fi: show only Connect or only Disconnect for the current state.
     * Connect (green) when detected and not connected; Disconnect (red) when connected.
     * If offline/not ready, Connect stays visible but disabled (grey).
     */
    private void refreshConnectButtons() {
        if (connect == null || disconnect == null) return;
        boolean connected = isVesperaConnected();
        boolean requesting = isVesperaRequesting();
        boolean canConnect = !connected && !requesting && deviceStore.isConfigured() && savedDeviceOnline;
        if (connected) {
            connect.setVisibility(View.GONE);
            disconnect.setVisibility(View.VISIBLE);
            styleActionButton(disconnect, true, COLOR_DANGER);
        } else {
            disconnect.setVisibility(View.GONE);
            connect.setVisibility(View.VISIBLE);
            if (requesting) {
                connect.setText(R.string.btn_connecting);
                styleActionButton(connect, false, COLOR_CONNECTING);
            } else {
                connect.setText(R.string.btn_connect);
                if (canConnect) {
                    styleActionButton(connect, true, COLOR_CONNECTED);
                } else {
                    styleActionButton(connect, false, COLOR_OFFLINE);
                }
            }
        }
    }

    private void styleActionButton(Button button, boolean selectable, int color) {
        button.setAllCaps(true);
        styleRaisedButton(button, color, selectable);
    }

    private void styleRaisedButton(Button button, int color, boolean enabled) {
        if (button == null) return;
        UiStyle.applyRaised(button, color, enabled);
        float density = getResources().getDisplayMetrics().density;
        if (button.getParent() instanceof LinearLayout
                && ((LinearLayout) button.getParent()).getOrientation() == LinearLayout.VERTICAL) {
            UiStyle.spaceBelow(button, density);
        } else if (button.getParent() == null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(8 * density);
            button.setLayoutParams(lp);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_SWU) return;
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (telescopePanel != null) telescopePanel.onSwuPicked(uri);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != LOCATION_REQUEST_CODE && code != SCAN_PERMISSION_REQUEST_CODE) return;
        if (!hasWifiPermissions()) {
            status.setText(R.string.perm_denied_status);
            vesperaInfo.setText(R.string.perm_denied_scan);
            return;
        }
        if (code == LOCATION_REQUEST_CODE) {
            connect();
        } else {
            refreshVesperaScan();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String currentConnectionStatus = VesperaConnectionService.getLastStatus();
        setConnectionState(currentConnectionStatus);
        if (VesperaConnectionService.STATUS_CONNECTED.equals(currentConnectionStatus)) {
            VesperaConnectionService.requestDaemonRoute(this);
            restoreInstrumentStatusIfKnown();
            if (!InstrumentWatchdog.hasCachedStatus()) {
                verifyApiPorts(false);
            }
            startWatchdogIfConnected();
        }
        if (!scanReceiverRegistered) {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scanResultsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(scanResultsReceiver, filter);
            }
            scanReceiverRegistered = true;
        }
        if (!statusReceiverRegistered) {
            IntentFilter filter = new IntentFilter(VesperaConnectionService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectionStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(connectionStatusReceiver, filter);
            }
            statusReceiverRegistered = true;
        }
        if (!instrumentStatusReceiverRegistered) {
            IntentFilter filter = new IntentFilter(InstrumentWatchdog.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(instrumentStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(instrumentStatusReceiver, filter);
            }
            instrumentStatusReceiverRegistered = true;
        }
        refreshVesperaScan();
        if (photoPanel != null && currentTab == TAB_PHOTOS) photoPanel.onResume();
        if (telescopePanel != null) {
            telescopePanel.onActivityResume();
            syncTelescopePanelHost();
            if (currentTab == TAB_TELESCOPE) {
                telescopePanel.onVisible();
            } else {
                telescopePanel.probePowerWarning();
            }
        }
        if (systemPanel != null && currentTab == TAB_SYSTEM) systemPanel.onVisible();
        if (telegramPanel != null && currentTab == TAB_TELEGRAM) telegramPanel.onVisible();
    }

    @Override protected void onDestroy() {
        if (photoPanel != null) photoPanel.shutdown();
        if (telescopePanel != null) telescopePanel.shutdown();
        stopWatchdog();
        if (instrumentWatchdog != null) {
            instrumentWatchdog.shutdown();
            instrumentWatchdog = null;
        }
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onPause() {
        stopWatchdog();
        if (scanReceiverRegistered) {
            unregisterReceiver(scanResultsReceiver);
            scanReceiverRegistered = false;
        }
        if (statusReceiverRegistered) {
            unregisterReceiver(connectionStatusReceiver);
            statusReceiverRegistered = false;
        }
        if (instrumentStatusReceiverRegistered) {
            unregisterReceiver(instrumentStatusReceiver);
            instrumentStatusReceiverRegistered = false;
        }
        if (telescopePanel != null) {
            if (currentTab == TAB_TELESCOPE) telescopePanel.onHidden();
            telescopePanel.onAppPause();
        }
        if (systemPanel != null && currentTab == TAB_SYSTEM) systemPanel.onHidden();
        if (telegramPanel != null && currentTab == TAB_TELEGRAM) telegramPanel.onHidden();
        super.onPause();
    }
}
