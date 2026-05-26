package com.example.nuclea;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ─────────────────────────────────────────────────────────────────────────────
//  NUCLEA — MainActivity  (FIXED)
//
//  Android-side fixes:
//    FIX A — nrfReady is now refreshed on every /ble/jam/status poll instead
//             of only on the initial PING, so stale state can't block jamming.
//    FIX B — A periodic status poll (every 5 s while BLE jam is active) keeps
//             the elapsed timer and nrfReady badge in sync with the ESP32.
//    FIX C — btnBleJamStart guard reads live nrfReady from /ble/jam/status
//             before allowing jam start, not just the cached value.
//    FIX D — bleJamTimer is now 60 s matching the firmware cap (was correct
//             already, but the onFinish now also polls /ble/jam/status to
//             confirm firmware stopped, and updates nrfReady badge).
// ─────────────────────────────────────────────────────────────────────────────

public class MainActivity extends AppCompatActivity {

    private static final String CLR_GREEN   = "#00E87A";
    private static final String CLR_GREEN2  = "#00A854";
    private static final String CLR_BLUE    = "#00B4FF";
    private static final String CLR_ORANGE  = "#FF8C00";
    private static final String CLR_RED     = "#FF3A3A";
    private static final String CLR_MUTED   = "#5A6A7A";
    private static final String CLR_MUTED2  = "#2A3040";
    private static final String CLR_PURPLE  = "#A020F0";
    private static final String CLR_PURPLE2 = "#7010B0";
    private static final String CLR_VIOLET  = "#E040FB";
    private static final String CLR_VIOLET2 = "#7A007A";

    // Top-level UI
    private EditText  etIp;
    private TextView  tvStatus, tvJamStatus, tvLog, tvBtConnected;
    private Button    btnConnect, btnJamStart, btnJamStop;
    private EditText  etBtQuickType;
    private Button    btnBtQuickType;
    private Button    btnBleOn, btnBleOff;

    // nRF24 BLE Jammer UI
    private Button   btnBleJamStart, btnBleJamStop;
    private TextView tvBleJamStatus, tvBleJamTimer, tvNrfReady;
    private CountDownTimer bleJamTimer;
    private boolean  isBleJamActive = false;

    // FIX B — periodic poll while BLE jam is running
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private static final int BLE_JAM_POLL_INTERVAL_MS = 5000;
    private final Runnable bleJamStatusPoller = new Runnable() {
        @Override public void run() {
            if (isBleJamActive) {
                pollBleJamStatus();
                pollHandler.postDelayed(this, BLE_JAM_POLL_INTERVAL_MS);
            }
        }
    };

    // RF slot views [4]
    private final Button[]   btnRfCapture = new Button[4];
    private final Button[]   btnRfReplay  = new Button[4];
    private final Button[]   btnRfClear   = new Button[4];
    private final TextView[] tvRfLabel    = new TextView[4];
    private final TextView[] tvRfData     = new TextView[4];

    // IR slot views [4]
    private final Button[]   btnIrCapture = new Button[4];
    private final Button[]   btnIrReplay  = new Button[4];
    private final Button[]   btnIrClear   = new Button[4];
    private final TextView[] tvIrLabel    = new TextView[4];
    private final TextView[] tvIrData     = new TextView[4];

    // BT Ducky slot views [4]
    private final Button[]   btnBtEdit    = new Button[4];
    private final Button[]   btnBtExec    = new Button[4];
    private final Button[]   btnBtClear   = new Button[4];
    private final TextView[] tvBtLabel    = new TextView[4];
    private final TextView[] tvBtData     = new TextView[4];

    // State
    private String  espIp       = "192.168.4.1";
    private boolean isJamming   = false;
    private boolean btConnected = false;
    private boolean btEnabled   = false;
    private boolean nrfReady    = false;

    private final String[] rfLabels   = {"SLOT 1", "SLOT 2", "SLOT 3", "SLOT 4"};
    private final String[] irLabels   = {"SLOT 1", "SLOT 2", "SLOT 3", "SLOT 4"};
    private final String[] btLabels   = {"BT SLOT 1", "BT SLOT 2", "BT SLOT 3", "BT SLOT 4"};
    private final String[] btPayloads = {"", "", "", ""};

    private final ExecutorService executor  = Executors.newCachedThreadPool();
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupListeners();
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        log("NUCLEA initialised — connect to NUCLEA_AP then tap PING");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (bleJamTimer != null) bleJamTimer.cancel();
        pollHandler.removeCallbacks(bleJamStatusPoller);  // FIX B cleanup
    }

    // ── View binding ──────────────────────────────────────────────────────
    private void bindViews() {
        etIp           = findViewById(R.id.etIp);
        tvStatus       = findViewById(R.id.tvStatus);
        tvJamStatus    = findViewById(R.id.tvJamStatus);
        tvLog          = findViewById(R.id.tvLog);
        tvBtConnected  = findViewById(R.id.tvBtConnected);
        btnConnect     = findViewById(R.id.btnConnect);
        btnJamStart    = findViewById(R.id.btnJamStart);
        btnJamStop     = findViewById(R.id.btnJamStop);
        etBtQuickType  = findViewById(R.id.etBtQuickType);
        btnBtQuickType = findViewById(R.id.btnBtQuickType);
        btnBleOn       = findViewById(R.id.btnBleOn);
        btnBleOff      = findViewById(R.id.btnBleOff);
        btnBleJamStart = findViewById(R.id.btnBleJamStart);
        btnBleJamStop  = findViewById(R.id.btnBleJamStop);
        tvBleJamStatus = findViewById(R.id.tvBleJamStatus);
        tvBleJamTimer  = findViewById(R.id.tvBleJamTimer);
        tvNrfReady     = findViewById(R.id.tvNrfReady);

        int[] rfRootIds = {R.id.slotRf0, R.id.slotRf1, R.id.slotRf2, R.id.slotRf3};
        int[] irRootIds = {R.id.slotIr0, R.id.slotIr1, R.id.slotIr2, R.id.slotIr3};
        int[] btRootIds = {R.id.slotBt0, R.id.slotBt1, R.id.slotBt2, R.id.slotBt3};

        for (int i = 0; i < 4; i++) {
            View rf = findViewById(rfRootIds[i]);
            tvRfLabel[i]    = rf.findViewById(R.id.tvSlotLabel);
            tvRfData[i]     = rf.findViewById(R.id.tvSlotData);
            btnRfCapture[i] = rf.findViewById(R.id.btnCapture);
            btnRfReplay[i]  = rf.findViewById(R.id.btnReplay);
            btnRfClear[i]   = rf.findViewById(R.id.btnClear);
            tvRfLabel[i].setText(rfLabels[i]);

            View ir = findViewById(irRootIds[i]);
            tvIrLabel[i]    = ir.findViewById(R.id.tvSlotLabel);
            tvIrData[i]     = ir.findViewById(R.id.tvSlotData);
            btnIrCapture[i] = ir.findViewById(R.id.btnCapture);
            btnIrReplay[i]  = ir.findViewById(R.id.btnReplay);
            btnIrClear[i]   = ir.findViewById(R.id.btnClear);
            tvIrLabel[i].setText(irLabels[i]);

            View bt = findViewById(btRootIds[i]);
            tvBtLabel[i]  = bt.findViewById(R.id.tvBtSlotLabel);
            tvBtData[i]   = bt.findViewById(R.id.tvBtSlotData);
            btnBtEdit[i]  = bt.findViewById(R.id.btnBtEdit);
            btnBtExec[i]  = bt.findViewById(R.id.btnBtExec);
            btnBtClear[i] = bt.findViewById(R.id.btnBtClear);
            tvBtLabel[i].setText(btLabels[i]);
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────
    private void setupListeners() {

        btnConnect.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            if (!ip.isEmpty()) espIp = ip;
            pingEsp();
        });

        btnJamStart.setOnClickListener(v ->
                doGet("/rf/jam/start", resp -> {
                    isJamming = true;
                    tvJamStatus.setText("> ██ JAMMING ACTIVE  433 MHz ██");
                    tvJamStatus.setTextColor(Color.parseColor(CLR_ORANGE));
                    log("RF jammer started");
                }));

        btnJamStop.setOnClickListener(v ->
                doGet("/rf/jam/stop", resp -> {
                    isJamming = false;
                    tvJamStatus.setText("> JAMMER INACTIVE");
                    tvJamStatus.setTextColor(Color.parseColor(CLR_MUTED));
                    log("RF jammer stopped");
                }));

        // ── nRF24 BLE Jammer ─────────────────────────────────────────────
        // FIX C — query live status before starting so we don't rely on
        //          potentially stale nrfReady cached from PING time.
        btnBleJamStart.setOnClickListener(v -> {
            log("Checking nRF24 status…");
            doGet("/ble/jam/status", resp -> {
                try {
                    JSONObject j = new JSONObject(resp);
                    nrfReady = j.optBoolean("nrfReady", false);
                    updateNrfReadyBadge();
                    if (!nrfReady) {
                        toast("nRF24L01 not ready — check wiring/power");
                        log("BLE jam blocked — nRF24 not ready (live check)");
                        return;
                    }
                    // Already jamming?
                    if (j.optBoolean("active", false)) {
                        isBleJamActive = true;
                        updateBleJamUI(true);
                        startBleJamCountdown();
                        startBleJamPoll();
                        log("BLE jam already active — UI synced");
                        return;
                    }
                    // Start it
                    doGet("/ble/jam/start", resp2 -> {
                        if (resp2.contains("NRF24_NOT_READY")) {
                            tvBleJamStatus.setText("> nRF24 NOT READY");
                            tvBleJamStatus.setTextColor(Color.parseColor(CLR_RED));
                            log("BLE jam: nRF24 not ready on ESP32");
                            return;
                        }
                        isBleJamActive = true;
                        updateBleJamUI(true);
                        log("BLE jammer started (nRF24L01+)");
                        startBleJamCountdown();
                        startBleJamPoll();  // FIX B
                    });
                } catch (Exception e) {
                    log("BLE jam status parse error: " + e.getMessage());
                }
            });
        });

        btnBleJamStop.setOnClickListener(v ->
                doGet("/ble/jam/stop", resp -> {
                    isBleJamActive = false;
                    if (bleJamTimer != null) bleJamTimer.cancel();
                    pollHandler.removeCallbacks(bleJamStatusPoller);  // FIX B
                    updateBleJamUI(false);
                    log("BLE jammer stopped");
                }));

        btnBleOn.setOnClickListener(v ->
                doGet("/bt/on", resp -> {
                    btEnabled = true;
                    updateBLEToggleUI();
                    log("BLE HID enabled");
                    toast("BLE ON — pair from target device");
                }));

        btnBleOff.setOnClickListener(v ->
                doGet("/bt/off", resp -> {
                    btEnabled = false;
                    btConnected = false;
                    updateBLEToggleUI();
                    updateBTBadge();
                    log("BLE HID disabled");
                    toast("BLE OFF");
                }));

        btnBtQuickType.setOnClickListener(v -> {
            String text = etBtQuickType.getText().toString();
            if (text.isEmpty()) return;
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                doGet("/bt/type?text=" + encoded, resp -> {
                    if (resp.contains("BT_NOT_CONNECTED")) {
                        toast("BLE not connected");
                        log("BT quick-type failed — not connected");
                    } else {
                        log("BT typed: " + text);
                    }
                });
            } catch (Exception e) {
                log("BT quick-type encode error: " + e.getMessage());
            }
        });

        for (int i = 0; i < 4; i++) {
            final int slot = i;

            tvRfLabel[slot].setOnLongClickListener(v -> { showRenameDialog(0, slot); return true; });
            tvIrLabel[slot].setOnLongClickListener(v -> { showRenameDialog(1, slot); return true; });
            tvBtLabel[slot].setOnLongClickListener(v -> { showRenameDialog(2, slot); return true; });

            btnRfCapture[slot].setOnClickListener(v -> startRFCapture(slot));

            btnRfReplay[slot].setOnClickListener(v ->
                    doGet("/rf/emulate?slot=" + slot, resp -> {
                        if (resp.contains("NO_RF")) {
                            tvRfData[slot].setText("> SLOT EMPTY");
                            tvRfData[slot].setTextColor(Color.parseColor(CLR_RED));
                        } else {
                            tvRfData[slot].setText("↗ REPLAYED");
                            tvRfData[slot].setTextColor(Color.parseColor(CLR_GREEN));
                            log("RF slot " + (slot + 1) + " replayed");
                        }
                    }));

            btnRfClear[slot].setOnClickListener(v ->
                    doGet("/rf/clear?slot=" + slot, resp -> {
                        tvRfData[slot].setText("— CLEARED");
                        tvRfData[slot].setTextColor(Color.parseColor(CLR_MUTED2));
                        log("RF slot " + (slot + 1) + " cleared");
                    }));

            btnIrCapture[slot].setOnClickListener(v -> startIRCapture(slot));

            btnIrReplay[slot].setOnClickListener(v ->
                    doGet("/ir/emulate?slot=" + slot, resp -> {
                        if (resp.contains("NO_IR")) {
                            tvIrData[slot].setText("> SLOT EMPTY");
                            tvIrData[slot].setTextColor(Color.parseColor(CLR_RED));
                        } else {
                            tvIrData[slot].setText("↗ REPLAYED");
                            tvIrData[slot].setTextColor(Color.parseColor(CLR_BLUE));
                            log("IR slot " + (slot + 1) + " replayed");
                        }
                    }));

            btnIrClear[slot].setOnClickListener(v ->
                    doGet("/ir/clear?slot=" + slot, resp -> {
                        tvIrData[slot].setText("— CLEARED");
                        tvIrData[slot].setTextColor(Color.parseColor(CLR_MUTED2));
                        log("IR slot " + (slot + 1) + " cleared");
                    }));

            btnBtEdit[slot].setOnClickListener(v -> showBTPayloadEditor(slot));

            btnBtExec[slot].setOnClickListener(v ->
                    doGet("/bt/exec?slot=" + slot, resp -> {
                        if (resp.contains("BLE_DISABLED")) {
                            tvBtData[slot].setText("> BLE IS OFF");
                            tvBtData[slot].setTextColor(Color.parseColor(CLR_RED));
                            toast("Turn BLE ON first");
                        } else if (resp.contains("BT_NOT_CONNECTED")) {
                            tvBtData[slot].setText("> BLE NOT CONNECTED");
                            tvBtData[slot].setTextColor(Color.parseColor(CLR_RED));
                            toast("BLE not connected — pair NUCLEA HID first");
                        } else if (resp.contains("NO_BT_PAYLOAD")) {
                            tvBtData[slot].setText("> SLOT EMPTY");
                            tvBtData[slot].setTextColor(Color.parseColor(CLR_RED));
                        } else {
                            tvBtData[slot].setText("▶ EXECUTING…");
                            tvBtData[slot].setTextColor(Color.parseColor(CLR_PURPLE));
                            log("BT slot " + (slot + 1) + " executing");
                            uiHandler.postDelayed(() -> {
                                tvBtData[slot].setText(previewPayload(btPayloads[slot]));
                                tvBtData[slot].setTextColor(Color.parseColor(CLR_PURPLE2));
                            }, 2000);
                        }
                    }));

            btnBtClear[slot].setOnClickListener(v ->
                    doGet("/bt/clear?slot=" + slot, resp -> {
                        btPayloads[slot] = "";
                        tvBtData[slot].setText("— EMPTY");
                        tvBtData[slot].setTextColor(Color.parseColor("#2A2040"));
                        log("BT slot " + (slot + 1) + " cleared");
                    }));
        }
    }

    // ── FIX B — poll /ble/jam/status while active ─────────────────────────
    private void startBleJamPoll() {
        pollHandler.removeCallbacks(bleJamStatusPoller);
        pollHandler.postDelayed(bleJamStatusPoller, BLE_JAM_POLL_INTERVAL_MS);
    }

    // FIX A — updates nrfReady from live ESP32 response
    private void pollBleJamStatus() {
        doGet("/ble/jam/status", resp -> {
            try {
                JSONObject j = new JSONObject(resp);
                nrfReady = j.optBoolean("nrfReady", false);
                updateNrfReadyBadge();
                boolean active = j.optBoolean("active", false);
                if (!active && isBleJamActive) {
                    // Firmware stopped (e.g. 60 s auto-stop) — sync UI
                    isBleJamActive = false;
                    if (bleJamTimer != null) bleJamTimer.cancel();
                    updateBleJamUI(false);
                    log("BLE jammer stopped (firmware auto-stop detected)");
                }
            } catch (Exception e) {
                log("BLE jam status poll error: " + e.getMessage());
            }
        });
    }

    // ── nRF24 BLE Jam UI helpers ──────────────────────────────────────────
    private void updateBleJamUI(boolean active) {
        if (active) {
            tvBleJamStatus.setText("> ██ BLE JAMMING ACTIVE  2.4 GHz ██");
            tvBleJamStatus.setTextColor(Color.parseColor(CLR_VIOLET));
            btnBleJamStart.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#3A003A")));
            btnBleJamStart.setTextColor(Color.parseColor(CLR_VIOLET));
            btnBleJamStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(CLR_VIOLET2)));
            btnBleJamStop.setTextColor(Color.parseColor("#FFFFFF"));
            tvBleJamTimer.setVisibility(View.VISIBLE);
        } else {
            tvBleJamStatus.setText("> BLE JAMMER INACTIVE");
            tvBleJamStatus.setTextColor(Color.parseColor(CLR_MUTED));
            btnBleJamStart.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#130618")));
            btnBleJamStart.setTextColor(Color.parseColor(CLR_VIOLET));
            btnBleJamStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#0C0E18")));
            btnBleJamStop.setTextColor(Color.parseColor(CLR_MUTED));
            tvBleJamTimer.setVisibility(View.GONE);
            tvBleJamTimer.setText("");
        }
    }

    // FIX D — onFinish polls status to confirm firmware stopped, updates nrfReady
    private void startBleJamCountdown() {
        if (bleJamTimer != null) bleJamTimer.cancel();
        bleJamTimer = new CountDownTimer(60_000, 1_000) {
            public void onTick(long ms) {
                int elapsed = (int) ((60_000 - ms) / 1000);
                tvBleJamTimer.setText("elapsed: " + elapsed + "s  /  60s cap");
            }
            public void onFinish() {
                pollBleJamStatus();   // FIX D — confirm firmware state
                isBleJamActive = false;
                pollHandler.removeCallbacks(bleJamStatusPoller);
                updateBleJamUI(false);
                log("BLE jammer auto-stopped (60 s limit)");
            }
        }.start();
    }

    private void updateNrfReadyBadge() {
        if (nrfReady) {
            tvNrfReady.setText("● NRF READY");
            tvNrfReady.setTextColor(Color.parseColor(CLR_VIOLET));
        } else {
            tvNrfReady.setText("○ NRF NOT READY");
            tvNrfReady.setTextColor(Color.parseColor(CLR_MUTED));
        }
    }

    // ── BT Payload Editor ─────────────────────────────────────────────────
    private void showBTPayloadEditor(int slot) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit BT Payload — " + btLabels[slot]);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setHorizontallyScrolling(false);
        input.setText(btPayloads[slot]);
        input.setTextColor(Color.parseColor("#C8D4E0"));
        input.setBackgroundColor(Color.parseColor("#0E0B16"));
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setHint("e.g. <<WIN+R>><<DELAY:500>>cmd<<ENTER>>");
        builder.setView(input);

        builder.setPositiveButton("SAVE TO ESP", (d, w) -> {
            String payload = input.getText().toString();
            btPayloads[slot] = payload;
            try {
                String encoded = URLEncoder.encode(payload, "UTF-8");
                doGet("/bt/set?slot=" + slot + "&payload=" + encoded, resp -> {
                    if (resp.contains("BT_PAYLOAD_SAVED")) {
                        tvBtData[slot].setText(previewPayload(payload));
                        tvBtData[slot].setTextColor(Color.parseColor(CLR_PURPLE2));
                        log("BT slot " + (slot + 1) + " saved (" + payload.length() + " chars)");
                    } else {
                        log("BT slot " + (slot + 1) + " save error: " + resp);
                    }
                });
            } catch (Exception e) {
                log("BT payload encode error: " + e.getMessage());
            }
        });
        builder.setNeutralButton("SYNTAX HELP", (d, w) -> showBTSyntaxHelp());
        builder.setNegativeButton("CANCEL", null);
        builder.show();
    }

    private void showBTSyntaxHelp() {
        String msg =
                "PLAIN TEXT   → typed as-is\n\n" +
                        "<<KEY>>      → special key press\n" +
                        "  Keys: ENTER TAB ESC SPACE BACKSPACE\n" +
                        "        DELETE HOME END PGUP PGDN\n" +
                        "        UP DOWN LEFT RIGHT\n" +
                        "        F1…F12 WIN ALT CTRL SHIFT CAPSLOCK\n\n" +
                        "<<CTRL+KEY>> → modifier combo\n" +
                        "  e.g.  <<CTRL+C>>  <<WIN+R>>\n\n" +
                        "<<DELAY:ms>> → pause (max 5000)\n\n" +
                        "EXAMPLES:\n" +
                        "<<WIN+R>><<DELAY:500>>cmd<<ENTER>>\n" +
                        "Hello World<<ENTER>>\n" +
                        "<<CTRL+ALT+DELETE>>";

        new AlertDialog.Builder(this)
                .setTitle("BT Payload Syntax")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    // ── RF Capture ────────────────────────────────────────────────────────
    private void startRFCapture(int slot) {
        tvRfData[slot].setText("▶ CAPTURING…  10s");
        tvRfData[slot].setTextColor(Color.parseColor(CLR_ORANGE));
        setSlotButtonsEnabled(true, false);
        log("RF slot " + (slot + 1) + " — capturing (10 s)…");

        executor.execute(() -> {
            String result = httpGet("http://" + espIp + "/rf/read?slot=" + slot, 12_000);
            uiHandler.post(() -> {
                setSlotButtonsEnabled(true, true);
                if (result == null) {
                    tvRfData[slot].setText("> TIMEOUT / ERROR");
                    tvRfData[slot].setTextColor(Color.parseColor(CLR_RED));
                    log("RF slot " + (slot + 1) + " — timeout");
                    return;
                }
                new CountDownTimer(1_000, 250) {
                    public void onTick(long ms) {}
                    public void onFinish() { pollRFSlot(slot); }
                }.start();
            });
        });

        new CountDownTimer(10_000, 1_000) {
            public void onTick(long ms) { tvRfData[slot].setText("▶ CAPTURING…  " + (ms / 1_000) + "s"); }
            public void onFinish() {}
        }.start();
    }

    private void pollRFSlot(int slot) {
        doGet("/status", resp -> {
            try {
                JSONObject j  = new JSONObject(resp);
                JSONArray  rf = j.getJSONArray("rfSlots");
                JSONObject s  = rf.getJSONObject(slot);
                if (s.getBoolean("hasData")) {
                    String line = "V:" + s.getLong("value") + "  B:" + s.getInt("bits") + "  P:" + s.getInt("protocol");
                    tvRfData[slot].setText("✓ " + line);
                    tvRfData[slot].setTextColor(Color.parseColor(CLR_GREEN));
                    log("RF slot " + (slot + 1) + " → " + line);
                } else {
                    tvRfData[slot].setText("> NO SIGNAL DETECTED");
                    tvRfData[slot].setTextColor(Color.parseColor(CLR_MUTED));
                    log("RF slot " + (slot + 1) + " — no signal");
                }
            } catch (Exception e) {
                tvRfData[slot].setText("> PARSE ERROR");
                tvRfData[slot].setTextColor(Color.parseColor(CLR_RED));
            }
        });
    }

    // ── IR Capture ────────────────────────────────────────────────────────
    private void startIRCapture(int slot) {
        tvIrData[slot].setText("▶ CAPTURING…  6s");
        tvIrData[slot].setTextColor(Color.parseColor(CLR_ORANGE));
        setSlotButtonsEnabled(false, false);
        log("IR slot " + (slot + 1) + " — capturing (6 s)…");

        executor.execute(() -> {
            String result = httpGet("http://" + espIp + "/ir/read?slot=" + slot, 8_000);
            uiHandler.post(() -> {
                setSlotButtonsEnabled(false, true);
                if (result == null) {
                    tvIrData[slot].setText("> TIMEOUT / ERROR");
                    tvIrData[slot].setTextColor(Color.parseColor(CLR_RED));
                    log("IR slot " + (slot + 1) + " — timeout");
                    return;
                }
                if (result.contains("IR_TIMEOUT")) {
                    tvIrData[slot].setText("> NO SIGNAL DETECTED");
                    tvIrData[slot].setTextColor(Color.parseColor(CLR_MUTED));
                    log("IR slot " + (slot + 1) + " — no signal");
                    return;
                }
                try {
                    JSONObject j = new JSONObject(result);
                    String line  = "CODE:0x" + j.optString("code", "????")
                            + "  " + j.optString("protocol", "?")
                            + "  " + j.optInt("bits", 0) + "bit";
                    tvIrData[slot].setText("✓ " + line);
                    tvIrData[slot].setTextColor(Color.parseColor(CLR_BLUE));
                    log("IR slot " + (slot + 1) + " → " + line);
                } catch (Exception e) {
                    tvIrData[slot].setText("> " + result);
                    tvIrData[slot].setTextColor(Color.parseColor(CLR_BLUE));
                }
            });
        });

        new CountDownTimer(6_000, 1_000) {
            public void onTick(long ms) { tvIrData[slot].setText("▶ CAPTURING…  " + (ms / 1_000) + "s"); }
            public void onFinish() {}
        }.start();
    }

    private void setSlotButtonsEnabled(boolean rfMode, boolean enabled) {
        Button[][] targets = rfMode
                ? new Button[][]{btnRfCapture, btnRfReplay, btnRfClear}
                : new Button[][]{btnIrCapture, btnIrReplay, btnIrClear};
        for (Button[] row : targets) for (Button b : row) b.setEnabled(enabled);
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    private void showRenameDialog(int type, int slot) {
        String kind = type == 0 ? "RF" : type == 1 ? "IR" : "BT";
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename " + kind + " Slot " + (slot + 1));

        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(type == 0 ? rfLabels[slot] : type == 1 ? irLabels[slot] : btLabels[slot]);
        builder.setView(input);

        builder.setPositiveButton("SAVE", (d, w) -> {
            String name = input.getText().toString().trim().toUpperCase(Locale.US);
            if (name.isEmpty()) return;
            String path = (type == 0 ? "/rf/label" : type == 1 ? "/ir/label" : "/bt/label")
                    + "?slot=" + slot + "&name=" + name.replace(" ", "%20");
            doGet(path, resp -> {
                if (type == 0) { rfLabels[slot] = name; tvRfLabel[slot].setText(name); }
                else if (type == 1) { irLabels[slot] = name; tvIrLabel[slot].setText(name); }
                else { btLabels[slot] = name; tvBtLabel[slot].setText(name); }
                log(kind + " slot " + (slot + 1) + " renamed → " + name);
            });
        });
        builder.setNegativeButton("CANCEL", null);
        builder.show();
    }

    // ── Ping / connect ────────────────────────────────────────────────────
    private void pingEsp() {
        setStatus("> CONNECTING…", CLR_ORANGE);
        log("Pinging http://" + espIp + "/status");
        doGet("/status", resp -> {
            try {
                JSONObject j = new JSONObject(resp);
                setStatus("● CONNECTED  —  " + espIp, CLR_GREEN);

                btConnected    = j.optBoolean("btConnected",  false);
                btEnabled      = j.optBoolean("bleEnabled",   false);
                isBleJamActive = j.optBoolean("bleJamming",   false);
                nrfReady       = j.optBoolean("nrfReady",     false);  // FIX A

                updateBTBadge();
                updateBLEToggleUI();
                updateNrfReadyBadge();
                if (isBleJamActive) {
                    updateBleJamUI(true);
                    startBleJamCountdown();
                    startBleJamPoll();
                }

                boolean jamming = j.optBoolean("jamming", false);
                log("Connected. rfJam=" + jamming + "  bleJam=" + isBleJamActive
                        + "  nrfReady=" + nrfReady + "  BLE=" + btConnected);

                if (jamming) {
                    isJamming = true;
                    tvJamStatus.setText("> ██ JAMMING ACTIVE ██");
                    tvJamStatus.setTextColor(Color.parseColor(CLR_ORANGE));
                }
                refreshAllSlots(j);
            } catch (Exception e) {
                setStatus("● CONNECTED  —  " + espIp + "  (raw)", CLR_GREEN);
                log("Connected (non-JSON)");
            }
        });
    }

    private void refreshAllSlots(JSONObject j) {
        try {
            JSONArray rfArr = j.getJSONArray("rfSlots");
            JSONArray irArr = j.getJSONArray("irSlots");
            JSONArray btArr = j.optJSONArray("btSlots");

            for (int i = 0; i < 4; i++) {
                JSONObject rf  = rfArr.getJSONObject(i);
                String rfLabel = rf.optString("label", "");
                if (!rfLabel.isEmpty()) { rfLabels[i] = rfLabel; tvRfLabel[i].setText(rfLabel); }
                if (rf.getBoolean("hasData")) {
                    String line = "V:" + rf.getLong("value") + "  B:" + rf.getInt("bits") + "  P:" + rf.getInt("protocol");
                    tvRfData[i].setText("✓ " + line);
                    tvRfData[i].setTextColor(Color.parseColor(CLR_GREEN));
                } else {
                    tvRfData[i].setText("> NO SIGNAL"); tvRfData[i].setTextColor(Color.parseColor(CLR_MUTED2));
                }

                JSONObject ir  = irArr.getJSONObject(i);
                String irLabel = ir.optString("label", "");
                if (!irLabel.isEmpty()) { irLabels[i] = irLabel; tvIrLabel[i].setText(irLabel); }
                if (ir.getBoolean("hasData")) {
                    String line = "CODE:0x" + ir.optString("code", "????") + "  " + ir.optString("protocol", "?");
                    tvIrData[i].setText("✓ " + line);
                    tvIrData[i].setTextColor(Color.parseColor(CLR_BLUE));
                } else {
                    tvIrData[i].setText("> NO SIGNAL"); tvIrData[i].setTextColor(Color.parseColor(CLR_MUTED2));
                }

                if (btArr != null) {
                    JSONObject bt  = btArr.getJSONObject(i);
                    String btLabel = bt.optString("label", "");
                    if (!btLabel.isEmpty()) { btLabels[i] = btLabel; tvBtLabel[i].setText(btLabel); }
                    if (bt.getBoolean("hasData")) {
                        String preview = bt.optString("preview", "");
                        tvBtData[i].setText(preview.isEmpty() ? "✓ PAYLOAD LOADED" : "✓ " + preview);
                        tvBtData[i].setTextColor(Color.parseColor(CLR_PURPLE2));
                    } else {
                        tvBtData[i].setText("— EMPTY"); tvBtData[i].setTextColor(Color.parseColor("#2A2040"));
                    }
                }
            }
        } catch (Exception e) {
            log("Slot sync error: " + e.getMessage());
        }
    }

    private void updateBTBadge() {
        if (btConnected) {
            tvBtConnected.setText("● BLE ON");
            tvBtConnected.setTextColor(Color.parseColor(CLR_PURPLE));
        } else {
            tvBtConnected.setText("○ BLE OFF");
            tvBtConnected.setTextColor(Color.parseColor(CLR_MUTED));
        }
    }

    private void updateBLEToggleUI() {
        if (btEnabled) {
            btnBleOn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(CLR_PURPLE)));
            btnBleOn.setTextColor(Color.parseColor("#060810"));
            btnBleOff.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0C0E18")));
            btnBleOff.setTextColor(Color.parseColor(CLR_MUTED));
        } else {
            btnBleOn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0C0E18")));
            btnBleOn.setTextColor(Color.parseColor(CLR_MUTED));
            btnBleOff.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#A020F0")));
            btnBleOff.setTextColor(Color.parseColor("#060810"));
        }
    }

    private String previewPayload(String payload) {
        if (payload == null || payload.isEmpty()) return "— EMPTY";
        String p = payload.replace("\n", "↵");
        return p.length() > 55 ? p.substring(0, 55) + "…" : p;
    }

    private void doGet(String path, ResponseCallback cb) {
        executor.execute(() -> {
            String result = httpGet("http://" + espIp + path, 10_000);
            uiHandler.post(() -> {
                if (result == null) {
                    log("ERR  " + path.split("\\?")[0] + " → timeout");
                    setStatus("> ERROR — check IP / Wi-Fi", CLR_RED);
                } else {
                    log("OK   " + path.split("\\?")[0] + " → " + result.substring(0, Math.min(result.length(), 80)));
                    cb.onResponse(result);
                }
            });
        });
    }

    private String httpGet(String urlStr, int timeoutMs) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.connect();
            int code = c.getResponseCode();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setStatus(String msg, String hex) {
        uiHandler.post(() -> { tvStatus.setText(msg); tvStatus.setTextColor(Color.parseColor(hex)); });
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        uiHandler.post(() -> tvLog.append("[" + ts + "]  " + msg + "\n"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    interface ResponseCallback { void onResponse(String resp); }
}