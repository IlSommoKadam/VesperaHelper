package com.vaonis.vesperahelper;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connections LAN sub-tab: Ethernet DHCP vs manual IP via vespera-netd. */
final class EthernetPanel {
    private static final int COLOR_LINK_OK = 0xFF2E7D32;
    private static final int COLOR_LINK_LAN = 0xFFEF6C00;
    private static final int COLOR_LINK_OFF = 0xFFC62828;
    private static final int COLOR_LINK_WAIT = 0xFF90A4AE;

    private final Activity activity;
    private final float density;
    private final EthernetStore store;
    private final LinearLayout root;
    private final View linkLamp;
    private final TextView linkMessage;
    private final TextView detailView;
    private final TextView resultView;
    private final RadioGroup modeGroup;
    private final RadioButton modeDhcp;
    private final RadioButton modeStatic;
    private final LinearLayout staticFields;
    private final EditText ipInput;
    private final EditText prefixInput;
    private final EditText gwInput;
    private final EditText dns1Input;
    private final EditText dns2Input;
    private final Button refreshBtn;
    private final Button applyDhcpBtn;
    private final Button applyStaticBtn;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;

    EthernetPanel(Activity activity, float density) {
        this.activity = activity;
        this.density = density;
        this.store = EthernetStore.from(activity);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFE8F5E9);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.bottomMargin = (int) (12 * density);
        root.setLayoutParams(rootLp);

        TextView title = new TextView(activity);
        title.setText(R.string.eth_section_title);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(15);
        title.setTextColor(0xFF1B5E20);
        root.addView(title);

        TextView info = new TextView(activity);
        info.setText(R.string.eth_section_info);
        info.setTextSize(13);
        info.setTextColor(0xFF37474F);
        info.setPadding(0, (int) (6 * density), 0, (int) (10 * density));
        root.addView(info);

        LinearLayout linkRow = new LinearLayout(activity);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        linkRow.setPadding(0, 0, 0, (int) (8 * density));

        int lampSize = (int) (18 * density);
        linkLamp = new View(activity);
        LinearLayout.LayoutParams lampLp = new LinearLayout.LayoutParams(lampSize, lampSize);
        lampLp.setMarginEnd((int) (10 * density));
        linkLamp.setLayoutParams(lampLp);
        setLampColor(COLOR_LINK_WAIT);
        linkRow.addView(linkLamp);

        linkMessage = new TextView(activity);
        linkMessage.setText(R.string.eth_link_checking);
        linkMessage.setTextSize(15);
        linkMessage.setTypeface(linkMessage.getTypeface(), Typeface.BOLD);
        linkMessage.setTextColor(0xFF263238);
        linkRow.addView(linkMessage);
        root.addView(linkRow);

        detailView = new TextView(activity);
        detailView.setText("");
        detailView.setTextSize(13);
        detailView.setTextColor(0xFF455A64);
        detailView.setLineSpacing(0, 1.15f);
        detailView.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(detailView);

        TextView configTitle = new TextView(activity);
        configTitle.setText(R.string.eth_config_title);
        configTitle.setTypeface(configTitle.getTypeface(), Typeface.BOLD);
        configTitle.setTextSize(14);
        configTitle.setTextColor(0xFF1B5E20);
        configTitle.setPadding(0, (int) (4 * density), 0, (int) (2 * density));
        root.addView(configTitle);

        TextView configHint = new TextView(activity);
        configHint.setText(R.string.eth_config_hint);
        configHint.setTextSize(12);
        configHint.setTextColor(0xFF546E7A);
        configHint.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(configHint);

        modeGroup = new RadioGroup(activity);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);
        modeGroup.setPadding(0, (int) (4 * density), 0, (int) (4 * density));
        modeDhcp = new RadioButton(activity);
        modeDhcp.setText(R.string.eth_mode_dhcp);
        modeDhcp.setId(View.generateViewId());
        modeStatic = new RadioButton(activity);
        modeStatic.setText(R.string.eth_mode_static);
        modeStatic.setId(View.generateViewId());
        modeGroup.addView(modeDhcp);
        modeGroup.addView(modeStatic);
        root.addView(modeGroup);

        staticFields = new LinearLayout(activity);
        staticFields.setOrientation(LinearLayout.VERTICAL);
        ipInput = labeledField(staticFields, R.string.eth_label_ip, R.string.eth_hint_ip,
                InputType.TYPE_CLASS_TEXT);
        prefixInput = labeledField(staticFields, R.string.eth_label_prefix, R.string.eth_hint_prefix,
                InputType.TYPE_CLASS_NUMBER);
        gwInput = labeledField(staticFields, R.string.eth_label_gw, R.string.eth_hint_gw,
                InputType.TYPE_CLASS_TEXT);
        dns1Input = labeledField(staticFields, R.string.eth_label_dns1, R.string.eth_hint_dns1,
                InputType.TYPE_CLASS_TEXT);
        dns2Input = labeledField(staticFields, R.string.eth_label_dns2, R.string.eth_hint_dns2,
                InputType.TYPE_CLASS_TEXT);
        root.addView(staticFields);

        refreshBtn = new Button(activity);
        refreshBtn.setText(R.string.eth_btn_refresh);
        refreshBtn.setAllCaps(false);
        refreshBtn.setOnClickListener(v -> refreshStatus());
        UiStyle.applyRaised(refreshBtn, UiStyle.SLATE, true);
        UiStyle.spaceBelow(refreshBtn, density);
        root.addView(refreshBtn);

        applyDhcpBtn = new Button(activity);
        applyDhcpBtn.setText(R.string.eth_btn_dhcp);
        applyDhcpBtn.setAllCaps(false);
        applyDhcpBtn.setOnClickListener(v -> applyDhcp());
        UiStyle.applyRaised(applyDhcpBtn, UiStyle.GREEN, true);
        UiStyle.spaceBelow(applyDhcpBtn, density);
        root.addView(applyDhcpBtn);

        applyStaticBtn = new Button(activity);
        applyStaticBtn.setText(R.string.eth_btn_static);
        applyStaticBtn.setAllCaps(false);
        applyStaticBtn.setOnClickListener(v -> applyStatic());
        UiStyle.applyRaised(applyStaticBtn, UiStyle.STEEL_BLUE, true);
        UiStyle.spaceBelow(applyStaticBtn, density);
        root.addView(applyStaticBtn);

        resultView = new TextView(activity);
        resultView.setTextSize(12);
        resultView.setTextColor(0xFF37474F);
        resultView.setPadding(0, (int) (4 * density), 0, 0);
        root.addView(resultView);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean manual = checkedId == modeStatic.getId();
            store.setMode(manual ? EthernetStore.MODE_STATIC : EthernetStore.MODE_DHCP);
            updateModeVisibility();
        });

        loadPrefsIntoFields();
        if (store.isStatic()) {
            modeGroup.check(modeStatic.getId());
        } else {
            modeGroup.check(modeDhcp.getId());
        }
        updateModeVisibility();
    }

    View view() {
        return root;
    }

    void onVisible() {
        refreshStatus();
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private EditText labeledField(LinearLayout parent, int labelRes, int hintRes, int inputType) {
        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setTextColor(0xFF546E7A);
        label.setPadding(0, (int) (6 * density), 0, 0);
        parent.addView(label);
        EditText edit = new EditText(activity);
        edit.setHint(hintRes);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        parent.addView(edit);
        return edit;
    }

    private void loadPrefsIntoFields() {
        ipInput.setText(store.ip());
        prefixInput.setText(store.prefix());
        gwInput.setText(store.gateway());
        dns1Input.setText(store.dns1());
        dns2Input.setText(store.dns2());
    }

    private void updateModeVisibility() {
        boolean manual = modeStatic.isChecked();
        staticFields.setVisibility(manual ? View.VISIBLE : View.GONE);
        applyStaticBtn.setVisibility(manual ? View.VISIBLE : View.GONE);
        applyDhcpBtn.setVisibility(manual ? View.GONE : View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshBtn.setEnabled(!value);
        applyDhcpBtn.setEnabled(!value);
        applyStaticBtn.setEnabled(!value);
        modeDhcp.setEnabled(!value);
        modeStatic.setEnabled(!value);
        if (value) {
            resultView.setText(R.string.eth_busy);
        }
    }

    private void setLampColor(int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        linkLamp.setBackground(circle);
    }

    private void refreshStatus() {
        if (busy) return;
        setBusy(true);
        linkMessage.setText(R.string.eth_link_checking);
        setLampColor(COLOR_LINK_WAIT);
        detailView.setText("");
        worker.execute(() -> {
            EthernetNet.Status status = EthernetNet.status(activity);
            boolean uplink = false;
            if (status != null && status.ok && !status.ip.isEmpty()) {
                uplink = EthernetNet.probeUplink(activity);
            }
            boolean finalUplink = uplink;
            mainHandler.post(() -> {
                setBusy(false);
                bindStatus(status, finalUplink, null);
            });
        });
    }

    private void applyDhcp() {
        if (busy) return;
        setBusy(true);
        worker.execute(() -> {
            EthernetNet.Status status = EthernetNet.applyDhcp(activity);
            boolean uplink = status != null && status.ok && !status.ip.isEmpty()
                    && EthernetNet.probeUplink(activity);
            String message = postApplyMessage(status, uplink);
            mainHandler.post(() -> {
                setBusy(false);
                if (status != null && status.ok) {
                    store.setDhcp();
                    modeGroup.check(modeDhcp.getId());
                }
                bindStatus(status, uplink, null);
                resultView.setText(message);
            });
        });
    }

    private void applyStatic() {
        if (busy) return;
        String ip = textOf(ipInput);
        String prefix = textOf(prefixInput);
        String gw = textOf(gwInput);
        String dns1 = textOf(dns1Input);
        String dns2 = textOf(dns2Input);
        if (!looksLikeIpv4(ip)) {
            resultView.setText(R.string.eth_err_bad_ip);
            return;
        }
        int pref;
        try {
            pref = Integer.parseInt(prefix);
        } catch (Exception e) {
            resultView.setText(R.string.eth_err_bad_prefix);
            return;
        }
        if (pref < 8 || pref > 30) {
            resultView.setText(R.string.eth_err_bad_prefix);
            return;
        }
        if (!gw.isEmpty() && !looksLikeIpv4(gw)) {
            resultView.setText(R.string.eth_err_bad_gw);
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            EthernetNet.Status status = EthernetNet.applyStatic(
                    activity, ip, String.valueOf(pref), gw, dns1, dns2);
            boolean uplink = status != null && status.ok && !status.ip.isEmpty()
                    && EthernetNet.probeUplink(activity);
            String message = postApplyMessage(status, uplink);
            mainHandler.post(() -> {
                setBusy(false);
                if (status != null && status.ok) {
                    store.setStatic(ip, String.valueOf(pref), gw, dns1, dns2);
                }
                bindStatus(status, uplink, null);
                resultView.setText(message);
            });
        });
    }

    private String postApplyMessage(EthernetNet.Status status, boolean uplink) {
        if (status == null || status.timeout) {
            return activity.getString(R.string.eth_err_timeout);
        }
        if (!status.ok) return formatError(status);
        if (!status.hasDefaultRoute) {
            return activity.getString(R.string.eth_warn_no_route);
        }
        if (uplink) {
            return activity.getString(R.string.eth_ok_uplink);
        }
        String gw = status.gateway.isEmpty() ? textOf(gwInput) : status.gateway;
        if (gw.isEmpty()) gw = "-";
        return activity.getString(R.string.eth_warn_no_uplink, gw);
    }

    private void bindStatus(EthernetNet.Status status, boolean uplink, Integer okMessageRes) {
        if (status == null || status.timeout) {
            setLinkState(COLOR_LINK_OFF, R.string.eth_link_daemon);
            detailView.setText(R.string.eth_err_timeout);
            resultView.setText(R.string.eth_err_timeout);
            return;
        }
        if (!status.ok) {
            setLinkState(COLOR_LINK_OFF, R.string.eth_link_offline);
            String err = formatError(status);
            detailView.setText(err);
            resultView.setText(err);
            return;
        }

        store.rememberFromStatus(status);
        if (EthernetStore.MODE_STATIC.equals(status.mode) && !modeStatic.isChecked()) {
            modeGroup.check(modeStatic.getId());
        } else if (EthernetStore.MODE_DHCP.equals(status.mode) && !modeDhcp.isChecked()) {
            modeGroup.check(modeDhcp.getId());
        }
        if (ipInput.getText().length() == 0 && !status.ip.isEmpty()) {
            ipInput.setText(status.ip);
        }
        if ((prefixInput.getText().length() == 0 || "24".equals(textOf(prefixInput)))
                && !status.prefix.isEmpty()) {
            prefixInput.setText(status.prefix);
        }
        if (gwInput.getText().length() == 0 && !status.gateway.isEmpty()) {
            gwInput.setText(status.gateway);
        }

        boolean lanUp = !status.ip.isEmpty();
        if (uplink) {
            setLinkState(COLOR_LINK_OK, R.string.eth_link_internet);
        } else if (lanUp) {
            setLinkState(COLOR_LINK_LAN, R.string.eth_link_lan_only);
        } else {
            setLinkState(COLOR_LINK_OFF, R.string.eth_link_offline);
        }

        detailView.setText(formatDetails(status));
        if (okMessageRes != null && status.ok) {
            resultView.setText(okMessageRes);
        } else if (status.ok) {
            resultView.setText("");
        }
        updateModeVisibility();
    }

    private void setLinkState(int color, int messageRes) {
        setLampColor(color);
        linkMessage.setText(messageRes);
        linkMessage.setTextColor(color);
    }

    private String formatDetails(EthernetNet.Status status) {
        String modeLabel = EthernetStore.MODE_STATIC.equals(status.mode)
                ? activity.getString(R.string.eth_mode_label_static)
                : activity.getString(R.string.eth_mode_label_dhcp);
        String ip = status.ip.isEmpty() ? "—" : status.ip;
        String prefix = status.prefix.isEmpty() ? "—" : status.prefix;
        String gw = status.gateway.isEmpty() ? "—" : status.gateway;
        String dns = status.dns1.isEmpty() ? "—" : status.dns1;
        if (!status.dns2.isEmpty()) dns = dns + ", " + status.dns2;
        String route = activity.getString(status.hasDefaultRoute
                ? R.string.eth_route_yes : R.string.eth_route_no);
        return activity.getString(R.string.eth_status_details,
                modeLabel, ip, prefix, gw, dns, route);
    }

    private String formatError(EthernetNet.Status status) {
        String code = status.error;
        if ("daemon_old".equals(code)) {
            return activity.getString(R.string.eth_err_daemon_old);
        }
        if ("timeout".equals(code)) {
            return activity.getString(R.string.eth_err_timeout);
        }
        if ("bad-ip".equals(code)) return activity.getString(R.string.eth_err_bad_ip);
        if ("bad-prefix".equals(code)) return activity.getString(R.string.eth_err_bad_prefix);
        if ("bad-gw".equals(code)) return activity.getString(R.string.eth_err_bad_gw);
        if (code == null || code.isEmpty()) {
            return activity.getString(R.string.eth_err_generic, status.raw);
        }
        return activity.getString(R.string.eth_err_generic, code);
    }

    private static String textOf(EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private static boolean looksLikeIpv4(String value) {
        if (value == null || value.isEmpty()) return false;
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
