package com.example.BLE_PAXmeter;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    // GPS & Warm-up Logik
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastValidLocation = null;
    private boolean isGpsWarmingUp = true;
    private int gpsWarmupCountdown = 10;
    private Handler warmupHandler = new Handler();
    private float currentSpeedKMH = 0f;
    private float speedThresholdMS = 6.94f;
    private long lastGpsUpdateTimestamp = 0;

    // Filter Parameter
    private long currentWindowDuration = 15 * 1000;
    private int rssiThreshold = -85;
    private int minPingsRequired = 2;
    private int sectionCount = 0;
    private int visibilityTimeThreshold = 10;
    private int speedDiffThreshold = 5;

    // UI Elemente
    private View layoutSetup, layoutLive;
    private CheckBox cbUseSpeed, cbUseRSSI, cbUsePings, cbUseDuration, cbVisibilityTime, cbSpeedDiff;
    private SeekBar sbSpeed, sbRSSI, sbPings, sbDuration, sbVisibilityTime, sbSpeedDiff;
    private TextView lblSpeedValue, lblDurationValue, lblRSSIValue, lblPingsValue, lblPrognose, lblVisibilityTimeValue, lblSpeedDiffValue;
    private Button startBT, haltestelleBT, stopBT, btnBus, btnBahn, btnZug, btnFindStop;
    private EditText lineET, stopET, directionET, commentET, idET, nFahrgaste;
    private EditText etIn, etOut; // NEU
    private TextView nGerate, nFilteredET, exception;
    private TextView tvStatusGPS, tvStatusMessung, tvStatusInternet, tvStatusSpeed;

    private Mode mode = Mode.Off;
    private boolean windowActive = false;
    private boolean wasTriggeredInThisSegment = false;
    private Handler countdownHandler = new Handler();
    private Runnable countdownRunnable;
    private Handler statusMonitorHandler = new Handler();

    private JSONObject fileText;
    private HashMap<String, RSSIStats> bluetoothData = new HashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scb;

    private String filename, startzeit;
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private String currentStopName = "";
    private String currentDelay = "0";
    private String currentTripId = "n/a";
    private String currentOperator = "n/a";
    private String currentVehicleType = "n/a";
    private String currentOccupancy = "n/a"; // Für die Auslastung
    private double sectionSpeedSum = 0;
    private int sectionSpeedCount = 0;
    private float sectionMaxSpeed = 0;
    private int lastPassengerCount = 0;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> { if (!result.containsValue(false)) setupGpsUpdates(); }
        );

        initUI();
        setupFilters();

        btnFindStop.setEnabled(false);
        btnFindStop.setText("⏳ GPS Initialisierung...");

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                // Wir nehmen nur Daten unter 40m Genauigkeit an
                if (location.getAccuracy() > 40) return;

                lastValidLocation = location;
                lastGpsUpdateTimestamp = System.currentTimeMillis();
                currentSpeedKMH = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;

                if (windowActive) {
                    sectionSpeedSum += currentSpeedKMH;
                    sectionSpeedCount++;
                }
                if (currentSpeedKMH > sectionMaxSpeed) {
                    sectionMaxSpeed = currentSpeedKMH;
                }
                checkIfReadyForSearch();
                checkSpeedTrigger();
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };

        checkPermissionsAndStartGps();
        startGpsWarmup();
    }

    private void startGpsWarmup() {
        isGpsWarmingUp = true;
        gpsWarmupCountdown = 10;
        warmupHandler.post(new Runnable() {
            @Override
            public void run() {
                if (gpsWarmupCountdown > 0) {
                    btnFindStop.setText("⏳ GPS Warm-up (" + gpsWarmupCountdown + "s)");
                    gpsWarmupCountdown--;
                    warmupHandler.postDelayed(this, 1000);
                } else {
                    isGpsWarmingUp = false;
                    checkIfReadyForSearch();
                }
            }
        });
    }

    private void checkIfReadyForSearch() {
        runOnUiThread(() -> {
            if (!isGpsWarmingUp && lastValidLocation != null) {
                btnFindStop.setEnabled(true);
                btnFindStop.setText("📍 Halt suchen");
                btnFindStop.setBackgroundColor(Color.parseColor("#2E7D32"));
            } else if (!isGpsWarmingUp) {
                btnFindStop.setText("⏳ Warte auf Signal...");
            }
        });
    }

    private void checkPermissionsAndStartGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT});
        } else {
            setupGpsUpdates();
        }
    }

    private void setupGpsUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, locationListener);
        } catch (SecurityException ignored) {}
    }

    private void initUI() {
        layoutSetup = findViewById(R.id.layout_setup);
        layoutLive = findViewById(R.id.layout_live);
        startBT = findViewById(R.id.startMeasurementBT);
        haltestelleBT = findViewById(R.id.ArrivedAtStopBT);
        stopBT = findViewById(R.id.stopMeasurementBT);
        btnFindStop = findViewById(R.id.btnFindStop);
        btnBus = findViewById(R.id.btnBus);
        btnBahn = findViewById(R.id.btnBahn);
        btnZug = findViewById(R.id.btnZug);
        lblPrognose = findViewById(R.id.lblPrognose);
        nFahrgaste = findViewById(R.id.nFahrgasteET);
        etIn = findViewById(R.id.etIn);   // NEU
        etOut = findViewById(R.id.etOut);
        nGerate = findViewById(R.id.nGerateET);
        nFilteredET = findViewById(R.id.nFilteredET);
        exception = findViewById(R.id.exceptionTV);
        tvStatusGPS = findViewById(R.id.tvStatusGPS);
        tvStatusMessung = findViewById(R.id.tvStatusMessung);
        tvStatusInternet = findViewById(R.id.tvStatusInternet);
        tvStatusSpeed = findViewById(R.id.tvStatusSpeed);
        lineET = findViewById(R.id.lineET);
        stopET = findViewById(R.id.stopET);
        directionET = findViewById(R.id.directionET);
        idET = findViewById(R.id.idET);
        commentET = findViewById(R.id.commentET);
        cbVisibilityTime = findViewById(R.id.cbVisibilityTime);
        sbVisibilityTime = findViewById(R.id.sbVisibilityTime);
        lblVisibilityTimeValue = findViewById(R.id.lblVisibilityTimeValue);

        cbSpeedDiff = findViewById(R.id.cbSpeedDiff);
        sbSpeedDiff = findViewById(R.id.sbSpeedDiff);
        lblSpeedDiffValue = findViewById(R.id.lblSpeedDiffValue);

        startBT.setOnClickListener(v -> Start());
        haltestelleBT.setOnClickListener(v -> Haltestelle());
        stopBT.setOnClickListener(v -> pseudoStop());
        btnFindStop.setOnClickListener(v -> triggerManualStopSearch());
        btnBus.setOnClickListener(v -> applyPreset("Bus"));
        btnBahn.setOnClickListener(v -> applyPreset("Bahn"));
        btnZug.setOnClickListener(v -> applyPreset("Zug"));
    }

    private void triggerManualStopSearch() {
        if (lastValidLocation == null) return;
        btnFindStop.setEnabled(false);
        btnFindStop.setText("🔍 Suche...");
        findNearestStop(lastValidLocation.getLatitude(), lastValidLocation.getLongitude());
    }

    private void findNearestStop(double lat, double lon) {
        Log.i("VVO_CHECK", "Starte Suche mit: " + lat + " / " + lon);

        new Thread(() -> {
            try {
                // URL korrekt mit Lat/Lon
                String url = String.format(Locale.US,
                        "https://efa.vvo-online.de/VMSSL3/XSLT_STOPFINDER_REQUEST?locationServerActive=1&type_sf=coord&name_sf=%.6f:%.6f:WGS84&outputFormat=rapidJSON",
                        lon, lat);

                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String rawJson = response.body() != null ? response.body().string() : "";
                    Log.i("VVO_CHECK", "Antwort erhalten: " + (rawJson.length() > 100 ? rawJson.substring(0,100) : rawJson));

                    if (!response.isSuccessful()) throw new Exception("HTTP Fehler: " + response.code());

                    JSONObject json = new JSONObject(rawJson);
                    JSONArray locations = json.optJSONArray("locations");

                    if (locations == null || locations.length() == 0) {
                        showError("VVO: Keine Treffer in 'locations'");
                        return;
                    }

                    // Wir nehmen die erste Location
                    JSONObject root = locations.getJSONObject(0);
                    String confirmedName = root.optString("name", "Unbekannter Ort");

                    List<String> names = new ArrayList<>();
                    List<String> ids = new ArrayList<>();

                    // Wir checken assignedStops
                    JSONArray assigned = root.optJSONArray("assignedStops");
                    if (assigned != null) {
                        for (int i = 0; i < assigned.length(); i++) {
                            JSONObject s = assigned.getJSONObject(i);
                            names.add(s.optString("name") + " (" + s.optInt("distance") + "m)");
                            ids.add(s.optString("id"));
                        }
                    }

                    // Falls assignedStops leer war, schauen wir, ob die Location selbst ein Stop ist
                    if (names.isEmpty() && "stop".equals(root.optString("type"))) {
                        names.add(root.optString("name"));
                        ids.add(root.optString("id"));
                    }

                    if (names.isEmpty()) {
                        showError("Keine Haltestellen in der Antwort gefunden.");
                    } else {
                        runOnUiThread(() -> {
                            btnFindStop.setEnabled(true);
                            btnFindStop.setText("📍 Auswählen...");
                            new AlertDialog.Builder(this)
                                    .setTitle("📍 " + confirmedName)
                                    .setItems(names.toArray(new String[0]), (dialog, which) -> {
                                        String selName = names.get(which).split(" \\(")[0];
                                        stopET.setText(selName);
                                        btnFindStop.setText("📍 " + selName);
                                        fetchDepartures(ids.get(which));
                                    })
                                    .setCancelable(true)
                                    .show();
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("VVO_CHECK", "CRASH in Thread: ", e);
                showError("Fehler: " + e.getMessage());
            }
        }).start();
    }

    // Hilfsmethode für UI-Feedback
    private void showError(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            btnFindStop.setEnabled(true);
            btnFindStop.setText("📍 Halt suchen");
        });
    }

    private void fetchDepartures(String stopId) {
        new Thread(() -> {
            try {
                String url = String.format("https://efa.vvo-online.de/VMSSL3/XSLT_DM_REQUEST?locationServerActive=1&type_dm=any&name_dm=%s&outputFormat=rapidJSON&mode=direct", stopId);

                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();

                // Parser für die VVO-Zeit (UTC)
                SimpleDateFormat sdfIn = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdfIn.setTimeZone(TimeZone.getTimeZone("UTC"));
                // Format für die Anzeige (Lokalzeit)
                SimpleDateFormat sdfOut = new SimpleDateFormat("HH:mm", Locale.getDefault());

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        JSONArray events = json.optJSONArray("stopEvents");

                        if (events == null) return;

                        List<String> displayList = new ArrayList<>();
                        List<JSONObject> rawEvents = new ArrayList<>();

                        for (int i = 0; i < events.length(); i++) {
                            JSONObject event = events.getJSONObject(i);
                            JSONObject trans = event.getJSONObject("transportation");

                            String line = trans.optString("number");
                            String destination = trans.getJSONObject("destination").optString("name").replace("Chemnitz, ", "");

                            // Zeiten parsen und umrechnen
                            Date plannedDate = sdfIn.parse(event.getString("departureTimePlanned"));
                            String timePlanned = sdfOut.format(plannedDate);

                            String timeDisplay = timePlanned;

                            if (event.has("departureTimeEstimated")) {
                                Date estimatedDate = sdfIn.parse(event.getString("departureTimeEstimated"));
                                String timeEstimated = sdfOut.format(estimatedDate);
                                timeDisplay += " -> " + timeEstimated + " (Live)";
                            } else {
                                timeDisplay += " (Plan)";
                            }

                            displayList.add(timeDisplay + "\n" + line + " " + destination);
                            rawEvents.add(event); // Speichern für den Klick-Event
                        }

                        runOnUiThread(() -> {
                            new AlertDialog.Builder(this)
                                    .setTitle("Fahrt auswählen")
                                    .setItems(displayList.toArray(new String[0]), (dialog, which) -> {
                                        try {
                                            JSONObject selected = rawEvents.get(which);
                                            importFahrtData(selected);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    })
                                    .setNegativeButton("Abbrechen", null)
                                    .show();
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("VVO_DEBUG", "Fehler", e);
            }
        }).start();
    }
    // Neue Methode, um die Daten in deine UI zu übernehmen
    private void importFahrtData(JSONObject event) throws Exception {
        // 1. Grunddaten
        JSONObject trans = event.getJSONObject("transportation");
        String line = trans.optString("number");
        String dest = trans.getJSONObject("destination").optString("name").replace("Chemnitz, ", "");

        // 2. Operator & Trip-ID (Pfade an reale VVO-Antwort angepasst)
        // Die Trip-ID liegt bei der aktuellen EFA-Version direkt als "id" in transportation
        currentTripId = trans.optString("id", "unbekannt");

        // Der Operator liegt ebenfalls in transportation
        JSONObject operatorObj = trans.optJSONObject("operator");
        currentOperator = (operatorObj != null) ? operatorObj.optString("name", "unbekannt") : "unbekannt";

        // 3. Fahrzeugdetails & Auslastung
        JSONObject props = trans.optJSONObject("properties");
        if (props != null) {
            // Falls vehicleType nicht da ist, nehmen wir den Produktnamen (z.B. Bus/Tram)
            currentVehicleType = props.optString("vehicleType", trans.optJSONObject("product") != null ? trans.getJSONObject("product").optString("name") : "n/a");
            currentOccupancy = props.optString("occupancy", "Keine Daten");
        } else {
            currentOccupancy = "Keine Daten";
            currentVehicleType = "n/a";
        }

        // 4. Zeit-Logik (UTC -> Lokal)
        SimpleDateFormat sdfIn = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdfIn.setTimeZone(TimeZone.getTimeZone("UTC"));
        long planned = sdfIn.parse(event.getString("departureTimePlanned")).getTime();
        long estimated = event.has("departureTimeEstimated")
                ? sdfIn.parse(event.getString("departureTimeEstimated")).getTime()
                : planned;
        currentDelay = String.valueOf((estimated - planned) / 60000);

        // UI Feedback
        currentStopName = stopET.getText().toString();
        runOnUiThread(() -> {
            lineET.setText(line);
            directionET.setText(dest);
            Toast.makeText(this, "Trip geladen: " + currentOperator + " (Trip: " + currentTripId + ")", Toast.LENGTH_SHORT).show();
        });
    }
    private void Start() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return;
        layoutSetup.setVisibility(View.GONE);
        layoutLive.setVisibility(View.VISIBLE);
        mode = Mode.On;
        sectionCount = 0;
        filename = "Messung_L" + lineET.getText().toString() + "_" +
                new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Calendar.getInstance().getTime());bluetoothData.clear();
        fileText = new JSONObject();
        try {
            // --- HEADER DATEN ---
            fileText.put("Start_Zeitpunkt", new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Calendar.getInstance().getTime()));
            fileText.put("Linie", lineET.getText().toString());
            fileText.put("Richtung", directionET.getText().toString());
            fileText.put("Fahrzeug_ID_Manuell", idET.getText().toString());

            // VVO Zusatzinfos im Header
            fileText.put("VVO_Trip_ID", currentTripId);
            fileText.put("VVO_Betrieb", currentOperator);
            fileText.put("VVO_Fahrzeugtyp", currentVehicleType);
            fileText.put("VVO_Start_Auslastung", currentOccupancy);
            fileText.put("VVO_Start_Verspaetung", currentDelay);

            // Kommentarfeld mitnehmen
            fileText.put("Kommentar", "");

        } catch (Exception ignored) {
            Log.e("JSON_START", "Fehler beim Erstellen des Headers");
        }
        findeBL();
        startStatusMonitor();
        startNewSegmentLogic();
        String initialPax = nFahrgaste.getText().toString();
        lastPassengerCount = initialPax.isEmpty() ? 0 : Integer.parseInt(initialPax);
    }

    private void startNewSegmentLogic() {
        wasTriggeredInThisSegment = false;
        haltestelleBT.setText("CUT (Halt: " + sectionCount + ")");
        nFahrgaste.postDelayed(() -> {
            nFahrgaste.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(nFahrgaste, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
        if (!cbUseSpeed.isChecked()) {
            if (cbUseDuration.isChecked()) startMeasurementWindow();
            else {
                windowActive = true;
                tvStatusMessung.setText("MESSUNG: DAUER");
                startzeit = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime());
            }
        } else tvStatusMessung.setText("WARTE AUF START");
    }

    private void checkSpeedTrigger() {
        if (mode == Mode.On && cbUseSpeed.isChecked() && !windowActive && !wasTriggeredInThisSegment) {
            if ((currentSpeedKMH / 3.6f) > speedThresholdMS) startMeasurementWindow();
        }
    }

    private void startMeasurementWindow() {
        if (windowActive) return;
        windowActive = true;
        wasTriggeredInThisSegment = true;
        haltestelleBT.setEnabled(false);
        haltestelleBT.setAlpha(0.5f);
        if (cbUseDuration.isChecked()) {
            final long[] timeLeft = {currentWindowDuration / 1000};
            countdownRunnable = new Runnable() {
                @Override public void run() {
                    if (windowActive && timeLeft[0] > 0) {
                        tvStatusMessung.setText("AKTIV: " + timeLeft[0] + "s");
                        timeLeft[0]--;
                        countdownHandler.postDelayed(this, 1000);
                    } else if (windowActive) stopWindowAuto();
                }
            };
            countdownHandler.post(countdownRunnable);
        } else tvStatusMessung.setText("MESSUNG AKTIV");
        startzeit = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime());
    }

    private void stopWindowAuto() {
        windowActive = false;
        runOnUiThread(() -> {
            haltestelleBT.setEnabled(true);
            haltestelleBT.setAlpha(1.0f);
            tvStatusMessung.setText("CUT BEREIT");
        });
    }
    private boolean isDeviceValid(RSSIStats stats, float overallAverageSpeed) {
        // 1. Alte Filter prüfen
        if (cbUseRSSI.isChecked() && stats.getAverage() < rssiThreshold) return false;
        if (cbUsePings.isChecked() && stats.count < minPingsRequired) return false;

        // 2. Neuer Filter: Signalsichtbarkeit
        if (cbVisibilityTime != null && cbVisibilityTime.isChecked()) {
            if (stats.getDurationSec() < visibilityTimeThreshold) return false;
        }

        // 3. Neuer Filter: Geschwindigkeitsdifferenz
        if (cbSpeedDiff != null && cbSpeedDiff.isChecked()) {
            float deviceSpeed = stats.getAverageSpeed();
            // Fällt die Gerätegeschwindigkeit unter den Toleranzbereich der Durchschnittsgeschwindigkeit?
            if (deviceSpeed < (overallAverageSpeed - speedDiffThreshold)) return false;
        }

        // Hat alle aktivierten Filter überstanden
        return true;
    }
    private void updateLiveStats() {
        int net = 0;
        int brutto = bluetoothData.size();

        // Durchschnittsgeschwindigkeit berechnen (für den neuen Filter)
        float overallAverageSpeed = sectionSpeedCount > 0 ? (float) (sectionSpeedSum / sectionSpeedCount) : currentSpeedKMH;

        for (RSSIStats s : bluetoothData.values()) {
            // Hier greift unsere neue Filter-Methode
            if (isDeviceValid(s, overallAverageSpeed)) {
                net++;
            }
        }

        final int fN = net, fB = brutto;
        runOnUiThread(() -> {
            nGerate.setText(String.valueOf(fB));
            nFilteredET.setText(String.valueOf(fN));
            lblPrognose.setText(String.valueOf((int)(fN * 1.25)));
        });
    }
    private void Haltestelle() {
        try {
            // 1. Werte holen
            int inVal = getIntFromEdit(etIn);
            int outVal = getIntFromEdit(etOut);
            String currentText = nFahrgaste.getText().toString();
            int currentInput = currentText.isEmpty() ? 0 : Integer.parseInt(currentText);

            // 2. Logik: Hat der User manuell etwas anderes eingetippt als den alten Stand?
            int finalCount;
            if (currentInput != lastPassengerCount) {
                finalCount = currentInput; // Manuelle Korrektur
            } else {
                finalCount = lastPassengerCount - outVal + inVal; // Automatische Rechnung
            }
            if (finalCount < 0) finalCount = 0;

            // 3. WICHTIG: Den neuen Wert für den nächsten Halt merken
            lastPassengerCount = finalCount;
            JSONObject s = new JSONObject();
            s.put("Halt_Nr", sectionCount);
            s.put("Haltestelle", currentStopName);
            s.put("Zeit", startzeit);
            s.put("Brutto_BT", bluetoothData.size());
            s.put("Netto_BT", nFilteredET.getText().toString());
            s.put("Fahrgaeste_Manuell", finalCount);

            // Durchschnittsgeschwindigkeit berechnen (wird auch für den Filter genutzt)
            double fahrzeugVAvg = (sectionSpeedCount > 0) ? (sectionSpeedSum / sectionSpeedCount) : 0;

            s.put("Fahrzeug_V_Avg", String.format(Locale.US, "%.1f", fahrzeugVAvg));
            s.put("Fahrzeug_V_Max", String.format(Locale.US, "%.1f", sectionMaxSpeed));

            JSONArray devicesArray = new JSONArray();

            double totalSectionSpeedSum = 0;
            int totalSectionPings = 0;

            for (String mac : bluetoothData.keySet()) {
                RSSIStats stats = bluetoothData.get(mac);

                // NEU: Hier nutzen wir unsere zentrale Filter-Methode!
                if (isDeviceValid(stats, (float) fahrzeugVAvg)) {

                    JSONObject d = new JSONObject();
                    d.put("mac", mac);
                    d.put("pings", stats.count);
                    d.put("first_seen", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(stats.firstSeenMs)));
                    d.put("duration_sec", stats.getDurationSec());
                    d.put("v_start", String.format(Locale.US, "%.1f", stats.firstSpeed));
                    d.put("v_end", String.format(Locale.US, "%.1f", stats.lastSpeed));
                    d.put("v_avg", String.format(Locale.US, "%.1f", stats.getAverageSpeed()));
                    d.put("rssi_min", stats.minRSSI);
                    d.put("rssi_max", stats.maxRSSI);
                    d.put("rssi_avg", stats.getAverage());

                    devicesArray.put(d);
                }
            }

            s.put("Geraete_Details", devicesArray);
            fileText.accumulate("Abschnitte", s);
            stopLeScan();

            // Reset für nächsten Halt (optional)
            sectionCount++;
            bluetoothData.clear();
            sectionSpeedSum = 0;
            sectionSpeedCount = 0;
            sectionMaxSpeed = 0;
            lastPassengerCount = finalCount;
            runOnUiThread(() -> {
                nFahrgaste.setText(String.valueOf(lastPassengerCount));
                etIn.setText("");
                etOut.setText("");
            });
            if (lastValidLocation != null) {
                updateNextStopAuto(lastValidLocation.getLatitude(), lastValidLocation.getLongitude());
            }
            updateLiveStats();
            startNewSegmentLogic();
            new Handler().postDelayed(() -> {
                startLeScan();
            }, 500);
        } catch (Exception ex) {
            exception.setText("Fehler beim Export");
        }
    }
    private int getIntFromEdit(EditText et) {
        String s = et.getText().toString();
        if (s.isEmpty() || s.equals("-") || s.equals("+")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
    private void updateNextStopAuto(double lat, double lon) {
        new Thread(() -> {
            try {
                String url = String.format(Locale.US,
                        "https://efa.vvo-online.de/VMSSL3/XSLT_STOPFINDER_REQUEST?locationServerActive=1&type_sf=coord&name_sf=%.6f:%.6f:WGS84&outputFormat=rapidJSON",
                        lon, lat);

                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) return;

                    String rawJson = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(rawJson);
                    JSONArray locations = json.optJSONArray("locations");

                    if (locations != null && locations.length() > 0) {
                        // Wir nehmen das erste Location-Objekt (oft vom Typ 'address' oder 'coord')
                        JSONObject firstLocation = locations.getJSONObject(0);
                        String foundStopName = "";

                        // Wir greifen direkt auf das Array der zugeordneten Haltestellen zu
                        JSONArray assigned = firstLocation.optJSONArray("assignedStops");

                        if (assigned != null && assigned.length() > 0) {
                            // Da der VVO nach Distanz sortiert, ist Index 0 der nächste Halt
                            JSONObject nearestStop = assigned.getJSONObject(0);
                            foundStopName = nearestStop.optString("name");
                        }
                        // Fallback: Falls keine assignedStops da sind, aber die Location selbst ein Stop ist
                        else if ("stop".equals(firstLocation.optString("type"))) {
                            foundStopName = firstLocation.optString("name");
                        }

                        if (!foundStopName.isEmpty()) {
                            final String finalName = foundStopName;
                            runOnUiThread(() -> {
                                // Update der globalen Variable für das nächste JSON-Segment
                                currentStopName = finalName;
                                // Anzeige im UI-Feld aktualisieren
                                stopET.setText(finalName);
                                // Den Button-Text ebenfalls updaten, damit du siehst, was erkannt wurde
                                btnFindStop.setText("📍 " + finalName);

                                Log.i("VVO_AUTO", "Nächster Halt automatisch gesetzt: " + finalName);
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("VVO_AUTO", "Fehler beim automatischen Stop-Update", e);
            }
        }).start();
    }


    private void findeBL() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) return;
        scanner = bm.getAdapter().getBluetoothLeScanner();

        // Callback nur einmalig definieren
        scb = new ScanCallback() {
            @Override public void onScanResult(int ct, ScanResult r) {
                if (!windowActive) return;
                String m = r.getDevice().getAddress();
                if (!bluetoothData.containsKey(m)) {
                    bluetoothData.put(m, new RSSIStats(currentSpeedKMH));
                }
                bluetoothData.get(m).addValue(r.getRssi(), currentSpeedKMH);
                updateLiveStats();
            }
        };

        startLeScan(); // Den eigentlichen Scan-Vorgang starten
    }

    private void startLeScan() {
        if (scanner != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            // ScanSettings auf LOW_LATENCY lassen für maximale Rate
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb);
            Log.d("SCANNER", "BLE Scan gestartet/erneuert");
        }
    }

    private void stopLeScan() {
        if (scanner != null && scb != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.stopScan(scb);
            Log.d("SCANNER", "BLE Scan gestoppt");
        }
    }

    private void startStatusMonitor() {
        statusMonitorHandler.post(new Runnable() {
            @Override public void run() {
                if (mode == Mode.On) {
                    updateStatusGrid();
                    statusMonitorHandler.postDelayed(this, 2000);
                }
            }
        });
    }

    private void updateStatusGrid() {
        boolean gpsOk = (System.currentTimeMillis() - lastGpsUpdateTimestamp < 10000);
        tvStatusGPS.setText(gpsOk ? "GPS: OK" : "GPS: SUCHE...");
        tvStatusGPS.setTextColor(gpsOk ? Color.GREEN : Color.RED);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities cap = (cm != null) ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
        boolean netOk = cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        tvStatusInternet.setText(netOk ? "NET: JA" : "NET: NEIN");
        tvStatusInternet.setTextColor(netOk ? Color.WHITE : Color.YELLOW);
        tvStatusSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", currentSpeedKMH));
    }

    private void applyPreset(String type) {
        cbUseSpeed.setChecked(true); cbUseRSSI.setChecked(true);
        cbUsePings.setChecked(true); cbUseDuration.setChecked(true);
        if (type.equals("Bus")) { sbSpeed.setProgress(15); sbPings.setProgress(9); sbRSSI.setProgress(15); }
        else if (type.equals("Bahn")) { sbSpeed.setProgress(20); sbPings.setProgress(14); sbRSSI.setProgress(20); }
        else { sbSpeed.setProgress(40); sbPings.setProgress(24); sbRSSI.setProgress(25); }
    }

    private void setupFilters() {
        cbUseSpeed = findViewById(R.id.cbUseSpeed); cbUseRSSI = findViewById(R.id.cbUseRSSI);
        cbUsePings = findViewById(R.id.cbUsePings); cbUseDuration = findViewById(R.id.cbUseDuration);
        cbVisibilityTime = findViewById(R.id.cbVisibilityTime); // NEU
        cbSpeedDiff = findViewById(R.id.cbSpeedDiff);           // NEU

        sbSpeed = findViewById(R.id.sbSpeed); sbRSSI = findViewById(R.id.sbRSSI);
        sbPings = findViewById(R.id.sbPings); sbDuration = findViewById(R.id.sbDuration);
        sbVisibilityTime = findViewById(R.id.sbVisibilityTime); // NEU
        sbSpeedDiff = findViewById(R.id.sbSpeedDiff);           // NEU

        lblSpeedValue = findViewById(R.id.lblSpeedValue); lblDurationValue = findViewById(R.id.lblDurationValue);
        lblRSSIValue = findViewById(R.id.lblRSSIValue); lblPingsValue = findViewById(R.id.lblPingsValue);
        lblVisibilityTimeValue = findViewById(R.id.lblVisibilityTimeValue); // NEU
        lblSpeedDiffValue = findViewById(R.id.lblSpeedDiffValue);           // NEU

        sbDuration.setProgress(5); // Ergibt 15s (5 + 10 Offset)
        sbSpeed.setProgress(20);
        sbPings.setProgress(1);    // Ergibt 2 Pings (1 + 1 Offset)
        sbVisibilityTime.setProgress(5); // NEU: Ergibt 10s (5 + 5 Offset)
        sbSpeedDiff.setProgress(3);      // NEU: Ergibt 5 km/h (3 + 2 Offset)

        setupSlider(sbSpeed, lblSpeedValue, 5, " km/h", v -> speedThresholdMS = v / 3.6f);
        setupSlider(sbDuration, lblDurationValue, 10, " s", v -> currentWindowDuration = v * 1000L);
        setupSlider(sbRSSI, lblRSSIValue, -100, " dBm", v -> rssiThreshold = v - 100);
        setupSlider(sbPings, lblPingsValue, 1, "", v -> minPingsRequired = v);
        setupSlider(sbVisibilityTime, lblVisibilityTimeValue, 5, " s", v -> visibilityTimeThreshold = v);
        setupSlider(sbSpeedDiff, lblSpeedDiffValue, 2, " km/h", v -> speedDiffThreshold = v);

        cbUseSpeed.setChecked(true);
        cbUsePings.setChecked(true);
        cbUseDuration.setChecked(true);
        cbUseRSSI.setChecked(false);        // RSSI beim Start AUS (wie von dir beobachtet)
        cbVisibilityTime.setChecked(true);  // Sichtbarkeit beim Start AN
        cbSpeedDiff.setChecked(false);     // NEU: Standardmäßig aus

        // Aktivierung der Slider initial setzen
        sbSpeed.setEnabled(cbUseSpeed.isChecked());
        sbPings.setEnabled(cbUsePings.isChecked());
        sbDuration.setEnabled(cbUseDuration.isChecked());
        sbRSSI.setEnabled(cbUseRSSI.isChecked());
        sbVisibilityTime.setEnabled(cbVisibilityTime.isChecked());
        sbSpeedDiff.setEnabled(cbSpeedDiff.isChecked());

        // NEU: Listener hinzufügen, damit die Slider beim Klicken auf die Checkbox reagieren
        cbUseSpeed.setOnClickListener(v -> sbSpeed.setEnabled(cbUseSpeed.isChecked()));
        cbUsePings.setOnClickListener(v -> sbPings.setEnabled(cbUsePings.isChecked()));
        cbUseDuration.setOnClickListener(v -> sbDuration.setEnabled(cbUseDuration.isChecked()));
        cbUseRSSI.setOnClickListener(v -> sbRSSI.setEnabled(cbUseRSSI.isChecked()));
        cbVisibilityTime.setOnClickListener(v -> sbVisibilityTime.setEnabled(cbVisibilityTime.isChecked()));
        cbSpeedDiff.setOnClickListener(v -> sbSpeedDiff.setEnabled(cbSpeedDiff.isChecked()));
    }

    private void setupSlider(SeekBar sb, TextView lbl, int offset, String unit, OnValueChange listener) {
        int initialValue = sb.getProgress() + offset;
        lbl.setText(initialValue + unit);
        listener.onChange(initialValue);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean f) {
                int v = p + offset; lbl.setText(v + unit); listener.onChange(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void pseudoStop() {
        // 1. Das Feld sichtbar machen (da es im Layout auf 'gone' steht)
        commentET.setVisibility(View.VISIBLE);
        commentET.setHint("Bemerkungen zur Fahrt (optional)...");

        // 2. Ein Container, damit das Textfeld im Dialog nicht am Rand klebt
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 50; // Padding links
        params.rightMargin = 50; // Padding rechts
        commentET.setLayoutParams(params);

        // Falls das Feld noch einen alten Parent hat (vom Layout), lösen wir es kurz
        if (commentET.getParent() != null) {
            ((ViewGroup) commentET.getParent()).removeView(commentET);
        }
        container.addView(commentET);

        // 3. Der angepasste Dialog
        new AlertDialog.Builder(this)
                .setTitle("Messung beenden")
                .setMessage("Möchten Sie das Protokoll speichern?")
                .setView(container) // Hier binden wir das Feld ein
                .setPositiveButton("Ja, Speichern", (d, w) -> {
                    // Erst jetzt rufen wir Stop() auf, nachdem der User Zeit für Notizen hatte
                    Stop();
                })
                .setNegativeButton("Abbrechen", (d, w) -> {
                    // Falls abgebrochen wird: Feld wieder verstecken
                    commentET.setVisibility(View.GONE);
                })
                .show();
    }

    private void Stop() {
        mode = Mode.Off; windowActive = false;
        try {
            // Da fileText in Start() schon erstellt wurde, fügen wir den Text jetzt hinzu
            // put() überschreibt einen bestehenden Key im JSONObject einfach
            fileText.put("Kommentar", commentET.getText().toString());
        } catch (Exception e) {
            Log.e("STOP", "Fehler beim Aktualisieren des Kommentars");
        }
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        if (scanner != null && scb != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(scb);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, filename + ".json");
        startActivityForResult(i, 1);
        layoutLive.setVisibility(View.GONE); layoutSetup.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                os.write(fileText.toString(4).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }

    public static class RSSIStats {
        int count = 0;
        long sum = 0;
        int minRSSI = 0;
        int maxRSSI = -100;

        long firstSeenMs;
        long lastSeenMs;
        float firstSpeed;
        float lastSpeed;
        float speedSum = 0;

        public RSSIStats(float currentSpeed) {
            this.firstSeenMs = System.currentTimeMillis();
            this.lastSeenMs = firstSeenMs;
            this.firstSpeed = currentSpeed;
            this.lastSpeed = currentSpeed;
        }

        public void addValue(int r, float currentSpeed) {
            if (count == 0) {
                minRSSI = r;
                maxRSSI = r;
            } else {
                if (r < minRSSI) minRSSI = r;
                if (r > maxRSSI) maxRSSI = r;
            }
            sum += r;
            count++;
            speedSum += currentSpeed;
            lastSeenMs = System.currentTimeMillis();
            lastSpeed = currentSpeed;
        }

        public int getAverage() { return count > 0 ? (int)(sum/count) : -100; }
        public float getAverageSpeed() { return count > 0 ? (speedSum / count) : 0; }
        public long getDurationSec() { return (lastSeenMs - firstSeenMs) / 1000; }
    }

    interface OnValueChange { void onChange(int val); }
    enum Mode { Off, On }
}