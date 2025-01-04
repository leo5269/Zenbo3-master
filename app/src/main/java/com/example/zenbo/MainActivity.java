package com.example.zenbo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotCallback;
import com.asus.robotframework.API.RobotCmdState;
import com.asus.robotframework.API.RobotErrorCode;
import com.asus.robotframework.API.RobotFace;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.BeaconParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends RobotActivity implements BeaconConsumer {

    private static final String TAG = "MainActivity";
    public static MainActivity instance;

    //--- 語音 / 介面 / 權限相關 ---
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int REQUEST_CODE_SPEECH_INPUT = 1;
    private TextView txt;
    private String userLangCode = "zh"; // 預設中文
    private TextToSpeech textToSpeech;
    private Handler handler = new Handler();

    //--- Zenbo ---
    public static RobotCallback robotCallback = new RobotCallback() {
        private boolean hasWelcomed = false;
        @Override
        public void onResult(int cmd, int serial, RobotErrorCode err_code, Bundle result) { super.onResult(cmd, serial, err_code, result); }
        @Override
        public void onStateChange(int cmd, int serial, RobotErrorCode err_code, RobotCmdState state) {
            super.onStateChange(cmd, serial, err_code, state);
            if (err_code == RobotErrorCode.NO_ERROR && state == RobotCmdState.SUCCEED) {
                // 代表剛才的 moveBody(...) 動作已經成功完成
                Log.d("RobotCallback", "Zenbo movement finished, set isMoving=false");
                MainActivity.instance.isMoving = false;  // <-- reset
            }
        }


        @Override
        public void initComplete() {
            super.initComplete();
            if (!hasWelcomed) {
                hasWelcomed = true;
                MainActivity.instance.robotAPI.robot.speak("歡迎光臨，請說出需要的商品名稱");
                new Handler().postDelayed(() -> {
                    MainActivity.instance.startSpeechRecognition();
                }, 8000);
                MainActivity.instance.checkDatabaseConnection();
            }
        }
    };
    public static RobotCallback.Listen robotListenCallback = new RobotCallback.Listen() {
        @Override public void onFinishRegister() {}
        @Override public void onVoiceDetect(JSONObject jsonObject) {}
        @Override public void onSpeakComplete(String s, String s1) {}
        @Override public void onEventUserUtterance(JSONObject jsonObject) {
            try {
                String utterance = jsonObject.getString("text");
                Log.v("Voice Input", "User said: " + utterance);
                MainActivity.instance.fetchProductDetails(utterance);
            } catch (Exception e) {
                Log.e("VoiceRecognition", e.toString());
            }
        }
        @Override public void onResult(JSONObject jsonObject) {}
        @Override public void onRetry(JSONObject jsonObject) {}
    };

    //--- AltBeacon ---
    private BeaconManager beaconManager;
    private BeaconParser beaconParser;
    private Region targetRegion;
    private RangeNotifier rangeNotifier;
    private Runnable scanTimeoutRunnable;

    //--- BeaconInfo (UUID, Major, Minor) ---
    public static class BeaconInfo {
        public final String uuid;
        public final String major;
        public final String minor;
        public BeaconInfo(String uuid, String major, String minor) {
            this.uuid = uuid;
            this.major = major;
            this.minor = minor;
        }
    }

    //--- 商品 -> BeaconInfo 對應 ---
    private static final Map<String, BeaconInfo> PRODUCT_BEACON_MAP = new HashMap<String, BeaconInfo>() {{
        put("牛奶",  new BeaconInfo(
                "b5b182c7-eab1-4988-aa99-b5c1517008d9", "1",  "1"));
        put("麵包", new BeaconInfo(
                "b5b182c7-eab1-4988-aa99-b5c1517008d9", "1",  "2"));
        put("橘子", new BeaconInfo(
                "b5b182c7-eab1-4988-aa99-b5c1517008d9", "1", "56273"));
    }};

    public MainActivity() {
        super(robotCallback, robotListenCallback);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (instance == null) {
            instance = this;
        }

        //--- AltBeacon 初始化 ---
        beaconManager = BeaconManager.getInstanceForApplication(this);
        beaconParser = new BeaconParser()
                .setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24");
        beaconManager.getBeaconParsers().add(beaconParser);
        beaconManager.setForegroundScanPeriod(1100L);
        beaconManager.setForegroundBetweenScanPeriod(0L);
        beaconManager.bind(this);  // BeaconService

        //--- UI & Zenbo ---
        txt = findViewById(R.id.txt);
        this.robotAPI = new RobotAPI(getApplicationContext(), robotCallback);

        //--- TTS ---
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
            }
        });

        checkPermissions();
    }

    //--- BeaconConsumer 必要實作 ---
    @Override
    public void onBeaconServiceConnect() {
        // 需要掃描時，再動態 addRangeNotifier & startRanging
    }

    //--- 權限處理 ---
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (beaconManager != null && !beaconManager.isBound(this)) {
                    beaconManager.bind(this);
                }
            } else {
                speakOut("沒有定位權限，無法掃描 Beacon。");
            }
        }
    }

    //--- 語音辨識 ---
    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出商品名稱");
        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            speakOut("語音識別啟動失敗");
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SPEECH_INPUT
                && resultCode == RESULT_OK
                && data != null) {

            ArrayList<String> result =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String spokenText = result.get(0);

                if (spokenText.contains("結束")) {
                    speakOut("好的，有需要隨時叫我!");
                    handler.postDelayed(this::finish, 2000);
                } else {
                    detectLanguage(spokenText, new DetectCallback() {
                        @Override
                        public void onDetectSuccess(String languageCode) {
                            userLangCode = languageCode;
                            setTTSLanguage(languageCode);

                            if (!languageCode.startsWith("zh")) {
                                // 翻譯成中文再查
                                translateText(spokenText, "zh", new TranslateCallback() {
                                    @Override
                                    public void onTranslateSuccess(String translatedText) {
                                        fetchProductDetails(translatedText);
                                    }
                                    @Override
                                    public void onTranslateFail(String errorMsg) {
                                        fetchProductDetails(spokenText);
                                    }
                                });
                            } else {
                                // 原本就是中文
                                fetchProductDetails(spokenText);
                            }
                        }
                        @Override
                        public void onDetectFail(String errorMsg) {
                            userLangCode = "zh";
                            fetchProductDetails(spokenText);
                        }
                    });
                }
            }
        }
    }

    //--- Detect & Translate 介面 / 實作 ---
    interface DetectCallback {
        void onDetectSuccess(String languageCode);
        void onDetectFail(String errorMsg);
    }
    interface TranslateCallback {
        void onTranslateSuccess(String translatedText);
        void onTranslateFail(String errorMsg);
    }

    private void detectLanguage(String text, DetectCallback callback) {
        // Google Translation Detect API URL
        String url = "https://translation.googleapis.com/language/translate/v2/detect?key=AIzaSyBXg33pRLDFXNU-iJPP65z53TpCCCp-0wc";

        // 建立 JSON 請求主體 (可使用 JSONArray 包裝多條文字)
        // ex: { "q": ["需要偵測的文字"] }
        JSONObject requestBody = new JSONObject();
        try {
            JSONArray qArray = new JSONArray();
            qArray.put(text);
            requestBody.put("q", qArray);
        } catch (JSONException e) {
            e.printStackTrace();
            callback.onDetectFail(e.getMessage());
            return;
        }

        // 使用 OkHttp 發送 POST
        OkHttpClient client = new OkHttpClient();
        okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(JSON, requestBody.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> callback.onDetectFail(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> callback.onDetectFail("Response not successful: " + response.code()));
                    return;
                }

                String responseData = response.body().string();
                try {
                    JSONObject json = new JSONObject(responseData);
                    // 解析回傳: data.detections[0].language
                    // 參考官方文件: https://cloud.google.com/translate/docs/detecting-language
                    String detectedLanguage = json
                            .getJSONObject("data")
                            .getJSONArray("detections")
                            .getJSONArray(0)
                            .getJSONObject(0)
                            .getString("language");

                    runOnUiThread(() -> callback.onDetectSuccess(detectedLanguage));
                } catch (JSONException e) {
                    e.printStackTrace();
                    runOnUiThread(() -> callback.onDetectFail("Parse error: " + e.getMessage()));
                }
            }
        });
    }
    private void translateText(String text, String targetLang, TranslateCallback callback) {
        // Google Translation API URL
        String url = "https://translation.googleapis.com/language/translate/v2?key=AIzaSyBXg33pRLDFXNU-iJPP65z53TpCCCp-0wc";

        // 建立 JSON 請求主體
        // ex: { "q": "要翻譯的文字", "target": "en" }
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("q", text);
            requestBody.put("target", targetLang);
        } catch (JSONException e) {
            e.printStackTrace();
            callback.onTranslateFail(e.getMessage());
            return;
        }

        OkHttpClient client = new OkHttpClient();
        okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(JSON, requestBody.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> callback.onTranslateFail(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> callback.onTranslateFail("Response not successful: " + response.code()));
                    return;
                }

                String responseData = response.body().string();
                try {
                    JSONObject json = new JSONObject(responseData);
                    // 解析回傳: data.translations[0].translatedText
                    // https://cloud.google.com/translate/docs/basic/translate-text
                    String translatedText = json
                            .getJSONObject("data")
                            .getJSONArray("translations")
                            .getJSONObject(0)
                            .getString("translatedText");

                    runOnUiThread(() -> callback.onTranslateSuccess(translatedText));
                } catch (JSONException e) {
                    e.printStackTrace();
                    runOnUiThread(() -> callback.onTranslateFail("Parse error: " + e.getMessage()));
                }
            }
        });
    }

    //--- 設定TTS ---
    private void setTTSLanguage(String langCode) {
        Locale ttsLocale;
        switch (langCode) {
            case "en":
                ttsLocale = Locale.ENGLISH; break;
            case "ja":
                ttsLocale = Locale.JAPANESE; break;
            default:
                ttsLocale = Locale.getDefault();
        }
        int result = textToSpeech.setLanguage(ttsLocale);
        if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "不支援此語言: " + langCode);
        }
    }

    //--- 查詢商品資訊 ---
    public void fetchProductDetails(String productName) {
        String url = "http://192.168.0.109/get_product.php?product_name=" + productName;

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    String errorMessage = "網絡請求失敗";
                    txt.setText(errorMessage);
                    speakOut(errorMessage);
                });
                Log.e("HTTP_ERROR", e.getMessage());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        String serverError = "服務器錯誤，錯誤碼：" + response.code();
                        txt.setText(serverError);
                        translateTextAndSpeak(serverError);
                    });
                    return;
                }
                String responseData = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONObject jsonObject = new JSONObject(responseData);
                        if (jsonObject.has("error")) {
                            String error = jsonObject.getString("error");
                            txt.setText(error);
                            translateTextAndSpeak(error);
                        } else {
                            String name = jsonObject.getString("name");
                            double price = jsonObject.getDouble("price");
                            int stock   = jsonObject.getInt("stock");
                            String location = jsonObject.getString("location");

                            String productInfo = String.format(
                                    "商品名稱：%s\n價格：%.2f\n庫存：%d\n位置：%s",
                                    name, price, stock, location
                            );
                            txt.setText(productInfo);

                            // 播報前先翻譯成 userLangCode
                            String speechInfo = String.format(
                                    "商品%s的資訊已找到，價格是%.2f元，庫存有%d件，我現在帶您前往商品位置",
                                    name, price, stock
                            );
                            translateTextAndSpeak(speechInfo);

                            // 從 PRODUCT_BEACON_MAP 取出 BeaconInfo
                            BeaconInfo info = PRODUCT_BEACON_MAP.get(name.toLowerCase());
                            if (info != null) {
                                navigateToBeacon(info.uuid, info.major, info.minor);
                            } else {
                                translateTextAndSpeak("抱歉，找不到該商品對應的 beacon 資訊");
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        String parseError = "服務器響應解析失敗";
                        txt.setText(parseError);
                        translateTextAndSpeak(parseError);
                    }
                });
            }
        });
    }

    //--- 將中文訊息翻譯成 userLangCode 並播報 ---
    private void translateTextAndSpeak(String chineseText) {
        if (userLangCode.startsWith("zh")) {
            speakOut(chineseText);
        } else {
            translateText(chineseText, userLangCode, new TranslateCallback() {
                @Override
                public void onTranslateSuccess(String translatedText) {
                    speakOut(translatedText);
                }
                @Override
                public void onTranslateFail(String errorMsg) {
                    speakOut(chineseText);
                }
            });
        }
    }
    boolean isMoving = false;
    //--- 以 UUID / Major / Minor 來進行 Beacon 導覽 ---
    public void navigateToBeacon(String uuid, String major, String minor) {
        speakOut("開始尋找商品位置");

        rangeNotifier = new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
                if (beacons.isEmpty()) return;

                for (Beacon beacon : beacons) {
                    String bUuid  = beacon.getId1().toString().toLowerCase();
                    String bMajor = beacon.getId2().toString();
                    String bMinor = beacon.getId3().toString();

                    // 確認是否為目標 Beacon
                    if (bUuid.equalsIgnoreCase(uuid)
                            && bMajor.equalsIgnoreCase(major)
                            && bMinor.equalsIgnoreCase(minor)) {

                        double distance = beacon.getDistance();
                        Log.d(TAG, "Found target beacon! distance= " + distance);

                        if (distance > 1.3 && !isMoving) {
                            isMoving = true;
                            // 距離大於1m，往前移動
                            robotAPI.motion.moveBody(0.35f,0,0);
                        } else if (distance <= 1.0){
                            // 停止掃描 & 播報已到達
                            stopBeaconRanging();
                            robotAPI.motion.moveBody(0,0,0);
                            speakOut("已到達商品位置");
                            handler.postDelayed(() -> {
                                startSpeechRecognition();
                            }, 4000);
                        }
                    }
                }
            }
        };

        // 以 UUID / major / minor 建立 Region
        try {
            targetRegion = new Region("targetRegionId",
                    org.altbeacon.beacon.Identifier.parse(uuid),
                    org.altbeacon.beacon.Identifier.parse(major),
                    org.altbeacon.beacon.Identifier.parse(minor)
            );
            beaconManager.addRangeNotifier(rangeNotifier);
            beaconManager.startRangingBeaconsInRegion(targetRegion);

            // 設定超時
            scanTimeoutRunnable = () -> {
                stopBeaconRanging();
                robotAPI.motion.moveBody(0f, 0f, 0f);
                speakOut("無法找到商品位置，請稍後再試");
            };
            handler.postDelayed(scanTimeoutRunnable, 40000);

        } catch (Exception e) {
            Log.e(TAG, "Error starting ranging", e);
            speakOut("無法啟動 Beacon 掃描");
        }
    }

    private void stopBeaconRanging() {
        try {
            handler.removeCallbacks(scanTimeoutRunnable);
            if (rangeNotifier != null) {
                beaconManager.removeRangeNotifier(rangeNotifier);
                rangeNotifier = null;
            }
            if (targetRegion != null) {
                beaconManager.stopRangingBeaconsInRegion(targetRegion);
                targetRegion = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping beacon ranging", e);
        }
    }

    //--- 說話 ---
    private void speakOut(String text) {
        if (robotAPI != null) {
            robotAPI.robot.speak(text);
            Log.v("SpeakOut", "Zenbo speaking: " + text);
        } else {
            Log.e("SpeakOut", "RobotAPI is not initialized");
        }
    }

    //--- 測試資料庫連線 ---
    public void checkDatabaseConnection() {
        String url = "http://192.168.0.109/get_product.php?product_name=test";
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    speakOut("無法連接到資料庫");
                    txt.setText("資料庫連接失敗");
                });
                Log.e("DB_CONNECTION", e.getMessage());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        speakOut("資料庫連接成功");
                        txt.setText("資料庫連接成功");
                    });
                } else {
                    runOnUiThread(() -> {
                        speakOut("資料庫連接失敗，錯誤碼：" + response.code());
                        txt.setText("資料庫連接失敗，錯誤碼：" + response.code());
                    });
                }
            }
        });
    }

    //--- AltBeacon unbind ---
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (beaconManager != null && beaconManager.isBound(this)) {
            beaconManager.unbind(this);
        }
        if (robotAPI != null) {
            robotAPI.release();
        }
    }
}
