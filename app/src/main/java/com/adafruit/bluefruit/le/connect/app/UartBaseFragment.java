package com.adafruit.bluefruit.le.connect.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.adafruit.bluefruit.le.connect.R;
import com.adafruit.bluefruit.le.connect.ble.BleUtils;
import com.adafruit.bluefruit.le.connect.ble.UartPacket;
import com.adafruit.bluefruit.le.connect.ble.UartPacketManagerBase;
import com.adafruit.bluefruit.le.connect.ble.central.BlePeripheralUart;
import com.adafruit.bluefruit.le.connect.mqtt.MqttManager;
import com.adafruit.bluefruit.le.connect.mqtt.MqttSettings;
import com.adafruit.bluefruit.le.connect.utils.KeyboardUtils;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;


import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// TODO: register
public abstract class UartBaseFragment extends ConnectedPeripheralFragment implements UartPacketManagerBase.Listener, MqttManager.MqttManagerListener {
    // Log
    private final static String TAG = UartBaseFragment.class.getSimpleName();

    //my data
    Calendar calendarPast;
    int groupCt =0;
    boolean activePredMode = false;
    String predText ="";
    int tapCt =0;
    Context tmpContext;

    // Configuration
    public final static int kDefaultMaxPacketsToPaintAsText = 500;
    private final static int kInfoColor = Color.parseColor("#F21625");

    // Constants
    private final static String kPreferences = "UartActivity_prefs";
    private final static String kPreferences_eol = "eol";
    private final static String kPreferences_eolCharactersId = "eolCharactersId";
    private final static String kPreferences_echo = "echo";
    private final static String kPreferences_asciiMode = "ascii";
    private final static String kPreferences_timestampDisplayMode = "timestampdisplaymode";

    // UI
    private EditText mBufferTextView;
    private String modifiedText;
    private String debugText="";

    private EditText mModifiedTextView;
    private RecyclerView mBufferRecylerView;
    protected TimestampItemAdapter mBufferItemAdapter;
    private EditText mSendEditText;
    private Button mSendButton;
    private Button mPredButton;
    private MenuItem mMqttMenuItem;
    private Handler mMqttMenuItemAnimationHandler;
    private TextView mSentBytesTextView;
    private TextView mReceivedBytesTextView;
    protected Spinner mSendPeripheralSpinner;

    // UI TextBuffer (refreshing the text buffer is managed with a timer because a lot of changes can arrive really fast and could stall the main thread)
    private final Handler mUIRefreshTimerHandler = new Handler();
    private final Runnable mUIRefreshTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isUITimerRunning) {
                reloadData();
                // Log.d(TAG, "updateDataUI");
                mUIRefreshTimerHandler.postDelayed(this, 200);
            }
        }
    };
    private boolean isUITimerRunning = false;


    // Data
    protected final Handler mMainHandler = new Handler(Looper.getMainLooper());
    protected UartPacketManagerBase mUartData;
    protected List<BlePeripheralUart> mBlePeripheralsUart = new ArrayList<>();

    private boolean mShowDataInHexFormat;
    private boolean mIsTimestampDisplayMode;
    private boolean mIsEchoEnabled;
    private boolean mIsEolEnabled;
    private int mEolCharactersId;

    private final SpannableStringBuilder mTextSpanBuffer = new SpannableStringBuilder();

    protected MqttManager mMqttManager;

    private int maxPacketsToPaintAsText;
    private int mPacketsCacheLastSize = 0;
    private int localPredInt = 0;
    private boolean localPredMode = false;

    // region Fragment Lifecycle
    public UartBaseFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retain this fragment across configuration changes
        setRetainInstance(true);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final Context context = getContext();

calendarPast = Calendar.getInstance();

        // Buffer recycler view
        if (context != null) {
            mBufferRecylerView = view.findViewById(R.id.bufferRecyclerView);
            DividerItemDecoration itemDecoration = new DividerItemDecoration(context, DividerItemDecoration.VERTICAL);
            Drawable lineSeparatorDrawable = ContextCompat.getDrawable(context, R.drawable.simpledivideritemdecoration);
            assert lineSeparatorDrawable != null;
            itemDecoration.setDrawable(lineSeparatorDrawable);
            mBufferRecylerView.addItemDecoration(itemDecoration);

            LinearLayoutManager layoutManager = new LinearLayoutManager(context);
            //layoutManager.setStackFromEnd(true);        // Scroll to bottom when adding elements
            mBufferRecylerView.setLayoutManager(layoutManager);

            SimpleItemAnimator itemAnimator = (SimpleItemAnimator) mBufferRecylerView.getItemAnimator();
            if (itemAnimator != null) {
                itemAnimator.setSupportsChangeAnimations(false);         // Disable update animation
            }
            mBufferItemAdapter = new TimestampItemAdapter(context);            // Adapter

            mBufferRecylerView.setAdapter(mBufferItemAdapter);
        }

        // Buffer
        mBufferTextView = view.findViewById(R.id.bufferTextView);

        mModifiedTextView = view.findViewById(R.id.modifiedTextView);
        modifiedText = "";
        if (mBufferTextView != null) {
            mBufferTextView.setKeyListener(null);     // make it not editable
        }

        // Send Text
        mSendEditText = view.findViewById(R.id.sendEditText);
        mSendEditText.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onClickSend();
                return true;
            }
            return false;
        });
        mSendEditText.setOnFocusChangeListener((view1, hasFocus) -> {
            if (!hasFocus) {
                // Dismiss keyboard when sendEditText loses focus
                KeyboardUtils.dismissKeyboard(view1);
            }
        });

        mSendButton = view.findViewById(R.id.sendButton);

        mPredButton = view.findViewById(R.id.predButton);
        //mSendButton.setOnClickListener(view12 -> onClickSend());
        tmpContext = getContext();
mSendButton.setOnClickListener(new View.OnClickListener() {
    public void onClick(View v) {
        // クリック時の処理


        RequestQueue queue = Volley.newRequestQueue(tmpContext);

        String posturl ="https://navymouse.sakura.ne.jp/lolipop-flask/result";


        StringRequest postRequest = new StringRequest(Request.Method.POST,posturl,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        try {


                            mModifiedTextView.setText( response);

                            modifiedText ="";
                        } catch (Exception e) {
                            // error
                            mModifiedTextView.setText(e.toString());
                        }
                    }
                },
                new Response.ErrorListener(){
                    @Override
                    public void onErrorResponse(VolleyError error){
                        //error
                        mModifiedTextView.setText(error.toString());
                    }
                }){
            @Override
            protected Map<String,String> getParams(){
                // パラメータ設定
                Map<String,String> params = new HashMap<String,String>();
                params.put("field",modifiedText.substring(1));


                return params;
            }
        };

        // Add the request to the RequestQueue.
        //  queue.add(stringRequest);

        queue.add(postRequest);

    }
});

        mPredButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // クリック時の処理

               // activePredMode = true;
                localPredMode = true;

            }
        });
        final boolean isInMultiUartMode = isInMultiUartMode();
        mSendPeripheralSpinner = view.findViewById(R.id.sendPeripheralSpinner);
        mSendPeripheralSpinner.setVisibility(isInMultiUartMode ? View.VISIBLE : View.GONE);

        // Counters
        mSentBytesTextView = view.findViewById(R.id.sentBytesTextView);
        mReceivedBytesTextView = view.findViewById(R.id.receivedBytesTextView);

        // Read shared preferences
        maxPacketsToPaintAsText = kDefaultMaxPacketsToPaintAsText; //PreferencesFragment.getUartTextMaxPackets(this);

        // Read local preferences
        if (context != null) {
            SharedPreferences preferences = context.getSharedPreferences(kPreferences, Context.MODE_PRIVATE);
            setShowDataInHexFormat(!preferences.getBoolean(kPreferences_asciiMode, true));
            final boolean isTimestampDisplayMode = preferences.getBoolean(kPreferences_timestampDisplayMode, false);
            setDisplayFormatToTimestamp(isTimestampDisplayMode);
            setEchoEnabled(preferences.getBoolean(kPreferences_echo, true));
            mIsEolEnabled = preferences.getBoolean(kPreferences_eol, true);
            mEolCharactersId = preferences.getInt(kPreferences_eolCharactersId, 0);
            FragmentActivity activity = getActivity();
            if (activity != null) {
                activity.invalidateOptionsMenu();        // update options menu with current values
            }

            // Mqtt init
            if (mMqttManager == null) {
                mMqttManager = new MqttManager(context, this);
                if (MqttSettings.isConnected(context)) {
                    mMqttManager.connectFromSavedSettings();
                }
            } else {
                mMqttManager.setListener(this);
            }
        }
    }

    private void setShowDataInHexFormat(boolean showDataInHexFormat) {
        mShowDataInHexFormat = showDataInHexFormat;
        mBufferItemAdapter.setShowDataInHexFormat(showDataInHexFormat);

    }

    private void setEchoEnabled(boolean isEchoEnabled) {
        mIsEchoEnabled = isEchoEnabled;
        mBufferItemAdapter.setEchoEnabled(isEchoEnabled);
    }

    abstract protected boolean isInMultiUartMode();

    @Override
    public void onResume() {
        super.onResume();

        activePredMode = false;
        localPredMode=false;
        localPredInt=0;
        predText = "";
        debugText="";
        tapCt = 0;

        FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        updateMqttStatus();

        updateBytesUI();

        isUITimerRunning = true;
        mUIRefreshTimerHandler.postDelayed(mUIRefreshTimerRunnable, 0);
    }

    @Override
    public void onPause() {
        super.onPause();

        //Log.d(TAG, "remove ui timer");
        isUITimerRunning = false;
        mUIRefreshTimerHandler.removeCallbacksAndMessages(mUIRefreshTimerRunnable);

        // Save preferences
        final Context context = getContext();
        if (context != null) {
            SharedPreferences preferences = context.getSharedPreferences(kPreferences, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(kPreferences_echo, mIsEchoEnabled);
            editor.putBoolean(kPreferences_eol, mIsEolEnabled);
            editor.putInt(kPreferences_eolCharactersId, mEolCharactersId);
            editor.putBoolean(kPreferences_asciiMode, !mShowDataInHexFormat);
            editor.putBoolean(kPreferences_timestampDisplayMode, mIsTimestampDisplayMode);

            editor.apply();
        }
    }

    @Override
    public void onDestroy() {
        mUartData = null;

        // Disconnect mqtt
        if (mMqttManager != null) {
            mMqttManager.disconnect();
        }

        // Uart
        if (mBlePeripheralsUart != null) {
            for (BlePeripheralUart blePeripheralUart : mBlePeripheralsUart) {
                blePeripheralUart.uartDisable();
            }
            mBlePeripheralsUart.clear();
            mBlePeripheralsUart = null;
        }

        super.onDestroy();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_uart, menu);

        // Mqtt
        mMqttMenuItem = menu.findItem(R.id.action_mqttsettings);
        mMqttMenuItemAnimationHandler = new Handler();
        mMqttMenuItemAnimationRunnable.run();

        // DisplayMode
        MenuItem displayModeMenuItem = menu.findItem(R.id.action_displaymode);
        displayModeMenuItem.setTitle(String.format("%s: %s", getString(R.string.uart_settings_displayMode_title), getString(mIsTimestampDisplayMode ? R.string.uart_settings_displayMode_timestamp : R.string.uart_settings_displayMode_text)));
        SubMenu displayModeSubMenu = displayModeMenuItem.getSubMenu();
        if (mIsTimestampDisplayMode) {
            MenuItem displayModeTimestampMenuItem = displayModeSubMenu.findItem(R.id.action_displaymode_timestamp);
            displayModeTimestampMenuItem.setChecked(true);
        } else {
            MenuItem displayModeTextMenuItem = displayModeSubMenu.findItem(R.id.action_displaymode_text);
            displayModeTextMenuItem.setChecked(true);
        }

        // DataMode
        MenuItem dataModeMenuItem = menu.findItem(R.id.action_datamode);
        dataModeMenuItem.setTitle(String.format("%s: %s", getString(R.string.uart_settings_dataMode_title), getString(mShowDataInHexFormat ? R.string.uart_settings_dataMode_hex : R.string.uart_settings_dataMode_ascii)));
        SubMenu dataModeSubMenu = dataModeMenuItem.getSubMenu();
        if (mShowDataInHexFormat) {
            MenuItem dataModeHexMenuItem = dataModeSubMenu.findItem(R.id.action_datamode_hex);
            dataModeHexMenuItem.setChecked(true);
        } else {
            MenuItem dataModeAsciiMenuItem = dataModeSubMenu.findItem(R.id.action_datamode_ascii);
            dataModeAsciiMenuItem.setChecked(true);
        }

        // Echo
        MenuItem echoMenuItem = menu.findItem(R.id.action_echo);
        echoMenuItem.setTitle(R.string.uart_settings_echo_title);
        echoMenuItem.setChecked(mIsEchoEnabled);

        // Eol
        MenuItem eolMenuItem = menu.findItem(R.id.action_eol);
        eolMenuItem.setTitle(R.string.uart_settings_eol_title);
        eolMenuItem.setChecked(mIsEolEnabled);

        // Eol Characters
        MenuItem eolModeMenuItem = menu.findItem(R.id.action_eolmode);
        eolModeMenuItem.setTitle(String.format("%s: %s", getString(R.string.uart_settings_eolCharacters_title), getString(getEolCharactersStringId())));
        SubMenu eolModeSubMenu = eolModeMenuItem.getSubMenu();
        int selectedEolCharactersSubMenuId;
        switch (mEolCharactersId) {
            case 1:
                selectedEolCharactersSubMenuId = R.id.action_eolmode_r;
                break;
            case 2:
                selectedEolCharactersSubMenuId = R.id.action_eolmode_nr;
                break;
            case 3:
                selectedEolCharactersSubMenuId = R.id.action_eolmode_rn;
                break;
            default:
                selectedEolCharactersSubMenuId = R.id.action_eolmode_n;
                break;
        }
        MenuItem selectedEolCharacterMenuItem = eolModeSubMenu.findItem(selectedEolCharactersSubMenuId);
        selectedEolCharacterMenuItem.setChecked(true);


    }


    @Override
    public void onDestroyOptionsMenu() {
        super.onDestroyOptionsMenu();

        mMqttMenuItemAnimationHandler.removeCallbacks(mMqttMenuItemAnimationRunnable);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return super.onOptionsItemSelected(item);
        }

        switch (item.getItemId()) {
            case R.id.action_help: {
                FragmentManager fragmentManager = activity.getSupportFragmentManager();
                CommonHelpFragment helpFragment = CommonHelpFragment.newInstance(getString(R.string.uart_help_title), getString(R.string.uart_help_text_android));
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction()
                        .replace(R.id.contentLayout, helpFragment, "Help");
                fragmentTransaction.addToBackStack(null);
                fragmentTransaction.commit();
                return true;
            }

            case R.id.action_mqttsettings: {
                FragmentManager fragmentManager = activity.getSupportFragmentManager();
                MqttSettingsFragment mqttSettingsFragment = MqttSettingsFragment.newInstance();
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction()
                        .replace(R.id.contentLayout, mqttSettingsFragment, "MqttSettings");
                fragmentTransaction.addToBackStack(null);
                fragmentTransaction.commit();
                return true;
            }

            case R.id.action_displaymode_timestamp: {
                setDisplayFormatToTimestamp(true);
                invalidateTextView();
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_displaymode_text: {
                setDisplayFormatToTimestamp(false);
                invalidateTextView();
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_datamode_hex: {
                setShowDataInHexFormat(true);
                invalidateTextView();
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_datamode_ascii: {
                setShowDataInHexFormat(false);
                invalidateTextView();
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_echo: {
                setEchoEnabled(!mIsEchoEnabled);
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_eol: {
                mIsEolEnabled = !mIsEolEnabled;
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_eolmode_n: {
                mEolCharactersId = 0;
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_eolmode_r: {
                mEolCharactersId = 1;
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_eolmode_nr: {
                mEolCharactersId = 2;
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_eolmode_rn: {
                mEolCharactersId = 3;
                activity.invalidateOptionsMenu();
                return true;
            }

            case R.id.action_export: {
                export();
                return true;
            }

            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    // endregion

    // region Uart
    protected abstract void setupUart();

    protected abstract void send(String message);

    private void onClickSend() {
        String newText = mSendEditText.getText().toString();
        mSendEditText.setText("");       // Clear editText

        // Add eol
        if (mIsEolEnabled) {
            // Add newline character if checked
            newText += getEolCharacters();
        }

        send(newText);
    }

    // endregion

    // region UI
    protected void updateUartReadyUI(boolean isReady) {
        // Check null because crash detected in logs
        if (mSendEditText != null) {
            mSendEditText.setEnabled(isReady);
        }
        if (mSendButton != null) {
            mSendButton.setEnabled(isReady);
        }
    }

    private void addTextToSpanBuffer(SpannableStringBuilder spanBuffer, String text, int color, boolean isBold) {
        final int from = spanBuffer.length();
        spanBuffer.append(text);
        spanBuffer.setSpan(new ForegroundColorSpan(color), from, from + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (isBold) {
            spanBuffer.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), from, from + text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @MainThread
    private void updateBytesUI() {
        if (mUartData != null) {
            mSentBytesTextView.setText(String.format(getString(R.string.uart_sentbytes_format), mUartData.getSentBytes()));
            mReceivedBytesTextView.setText(String.format(getString(R.string.uart_receivedbytes_format), mUartData.getReceivedBytes()));
        }
    }

    private void setDisplayFormatToTimestamp(boolean enabled) {
        mIsTimestampDisplayMode = enabled;
        mBufferTextView.setVisibility(enabled ? View.GONE : View.VISIBLE);
        mBufferRecylerView.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    abstract protected int colorForPacket(UartPacket packet);

    private boolean isFontBoldForPacket(UartPacket packet) {
        return packet.getMode() == UartPacket.TRANSFERMODE_TX;
    }

    private void invalidateTextView() {
        if (!mIsTimestampDisplayMode) {
            mPacketsCacheLastSize = 0;
            mTextSpanBuffer.clear();
            mBufferTextView.setText("");

            mModifiedTextView.setText("");
        }
    }

    private void reloadData() {
        List<UartPacket> packetsCache = mUartData.getPacketsCache();
        final int packetsCacheSize = packetsCache.size();
        if (mPacketsCacheLastSize != packetsCacheSize) {        // Only if the buffer has changed

            if (mIsTimestampDisplayMode) {

                mBufferItemAdapter.notifyDataSetChanged();
                final int bufferSize = mBufferItemAdapter.getCachedDataBufferSize();
                mBufferRecylerView.smoothScrollToPosition(Math.max(bufferSize - 1, 0));

            } else {
                if (packetsCacheSize > maxPacketsToPaintAsText) {
                    mPacketsCacheLastSize = packetsCacheSize - maxPacketsToPaintAsText;
                    mTextSpanBuffer.clear();
                    addTextToSpanBuffer(mTextSpanBuffer, getString(R.string.uart_text_dataomitted) + "\n", kInfoColor, false);
                }

                // Log.d(TAG, "update packets: "+(bufferSize-mPacketsCacheLastSize));
                for (int i = mPacketsCacheLastSize; i < packetsCacheSize; i++) {
                    final UartPacket packet = packetsCache.get(i);
                    onUartPacketText(packet);
                }

                mBufferTextView.setText(mTextSpanBuffer);


                mBufferTextView.setSelection(0, mTextSpanBuffer.length());        // to automatically scroll to the end

                if(activePredMode || localPredMode){
                    mBufferTextView.setText(predText);
                    mModifiedTextView.setText(debugText);
                }else{
                    mBufferTextView.setText(debugText);
                    mModifiedTextView.setText(modifiedText);
                }

            }

            mPacketsCacheLastSize = packetsCacheSize;
        }

        updateBytesUI();
    }


    private void onUartPacketText(UartPacket packet) {
        if (mIsEchoEnabled || packet.getMode() == UartPacket.TRANSFERMODE_RX) {
            final int color = colorForPacket(packet);
            final boolean isBold = isFontBoldForPacket(packet);
            final byte[] bytes = packet.getData();
         /*  int upperInt = Byte.toUnsignedInt(bytes[0]);
            int lowerInt = Byte.toUnsignedInt(bytes[1]);
            int unsInt = ( upperInt << 8 ) | lowerInt ;
            int connectedInt ;
            if(unsInt > 32767) {
                connectedInt = -32768 + (unsInt - 32768);
            }else{
                connectedInt = unsInt;
            }
            */

            int ct =0;
            int upperInt,lowerInt,unsInt,connectedInt;
            upperInt =0;

            boolean tapped = false;


            for (byte aByte : bytes) {
                if( ct < 240) {
                    if (ct % 2 == 0) {
                        upperInt = Byte.toUnsignedInt(aByte);
                    } else {
                        lowerInt = Byte.toUnsignedInt(aByte);
                        unsInt = (upperInt << 8) | lowerInt;

                        if (unsInt > 32767) {
                            connectedInt = -32768 + (unsInt - 32768);
                        } else {
                            connectedInt = unsInt;
                        }
                        modifiedText += "," + connectedInt;
                    }
                }//end of if ct < 240
                if( ct == 240 || ct == 241){
                    modifiedText += ","+ (int)aByte;
                }

                if( ct ==242){
                    modifiedText += ","+ (int)aByte;
                    if( (int)aByte == 1){
                        tapped=true;
                    }
                }

                if(ct==243){

                    Calendar calendarNow = Calendar.getInstance();
                    long diffTime = calendarNow.getTimeInMillis() - calendarPast.getTimeInMillis();

                    if(diffTime > 200 ){
                        groupCt++;
                    }

                    calendarPast = Calendar.getInstance();

                    if(groupCt > 10000){
                        groupCt = 0;
                    }

                   // modifiedText += ","+diffTime ;

                    modifiedText += ","+ (char)aByte;
                    if(tapped){
                        debugText +=  ","+ (char)aByte;
                        if(activePredMode) {
                            debugText += "," + (char) aByte;
                        }

                        if(localPredMode){
                            char tapChar = (char) aByte;
                            switch( tapChar){
                                case 't':
                                    localPredInt += 16;
                                    break;
                                case 'i':
                                    localPredInt += 8;
                                    break;
                                case 'm':
                                    localPredInt += 4;
                                    break;
                                case 'r':
                                    localPredInt += 2;
                                    break;
                                case 'p':
                                    localPredInt += 1;
                                    break;
                            }
                        }
                    }else{
                        debugText +=  ",("+ (char)aByte +")";
                    }
                }

                ct++;
            }



            modifiedText += "E";


            if(localPredMode) {
                if (tapCt % 5 == 4) {
debugText += "#";
                    String tmpStr = intToPredString(localPredInt);
                    if( tmpStr.length() < 2) {
                        predText += tmpStr;
                    }
                    localPredInt = 0;

tapCt = -1;
                }
            }
            if(activePredMode){
                if(tapCt % 5 ==4){

                    RequestQueue queue = Volley.newRequestQueue(tmpContext);

                    String posturl ="http://160.251.6.68/create";


                    StringRequest postRequest = new StringRequest(Request.Method.POST,posturl,
                            new Response.Listener<String>() {
                                @Override
                                public void onResponse(String response) {
                                    try {


                                        predText += response;
                                        mBufferTextView.setText( predText);

                                        modifiedText ="";

                                    } catch (Exception e) {
                                        // error
                                        mModifiedTextView.setText(e.toString());
                                    }
                                }
                            },
                            new Response.ErrorListener(){
                                @Override
                                public void onErrorResponse(VolleyError error){
                                    //error
                                    mModifiedTextView.setText(error.toString());
                                }
                            }){
                        @Override
                        protected Map<String,String> getParams(){
                            // パラメータ設定
                            Map<String,String> params = new HashMap<String,String>();
                            params.put("msg",modifiedText.substring(1));


                            return params;
                        }
                    };

                    // Add the request to the RequestQueue.
                    //  queue.add(stringRequest);

                    queue.add(postRequest);


                }
            }

            tapCt ++;

            final String formattedData = mShowDataInHexFormat ? BleUtils.bytesToHex2(bytes) : BleUtils.bytesToText(bytes, true);
            addTextToSpanBuffer(mTextSpanBuffer, formattedData, color, isBold);
        }
    }

    private String intToPredString( int inputInt ){
        String ret ="";
        switch(inputInt) {
            case 1:
                ret="u";
                break;
            case 2:
                ret="o";
                break;
            case 3:
                ret="s";
                break;
            case 4:
                ret="i";
                break;
            case 5:
                ret="z";
                break;
            case 6:
                ret="l";
                break;
            case 7:
                ret="switch";
                break;
            case 8:
                ret="e";
                break;
            case 9:
                ret="b";
                break;
            case 10:
                ret="m";
                break;
            case 11:
                ret="x";
                break;
            case 12:
                ret="t";
                break;
            case 13:
                ret="q";
                break;
            case 14:
                ret="backspace";
                break;
            case 15:
                ret="h";
                break;
            case 16:
                ret="a";
                break;
            case 17:
                ret="y";
                break;
            case 18:
                ret="k";
                break;
            case 19:
                ret="enter";
                break;
            case 20:
                ret="d";
                break;
            case 21:
                ret="w";
                break;
            case 22:
                ret="g";
                break;
            case 23:
                ret="c";
                break;
            case 24:
                ret="n";
                break;
            case 25:
                ret="p";
                break;
            case 26:
                ret="f";
                break;
            case 27:
                ret="v";
                break;
            case 28:
                ret="shift";
                break;
            case 29:
                ret="j";
                break;
            case 30:
                ret="r";
                break;
            case 31:
                ret=" ";
                break;

        }
        return ret;
    }

    private static SpannableString stringFromPacket(UartPacket packet, boolean useHexMode, int color, boolean isBold) {
        final byte[] bytes = packet.getData();
        final String formattedData = useHexMode ? BleUtils.bytesToHex2(bytes) : BleUtils.bytesToText(bytes, true);
        final SpannableString formattedString = new SpannableString(formattedData);
        formattedString.setSpan(new ForegroundColorSpan(color), 0, formattedString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (isBold) {
            formattedString.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, formattedString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return formattedString;
    }

    // endregion

    // region Mqtt UI
    private final Runnable mMqttMenuItemAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            updateMqttStatus();
            mMqttMenuItemAnimationHandler.postDelayed(mMqttMenuItemAnimationRunnable, 500);
        }
    };
    private int mMqttMenuItemAnimationFrame = 0;

    @MainThread
    private void updateMqttStatus() {
        if (mMqttMenuItem == null) {
            return;      // Hack: Sometimes this could have not been initialized so we don't update icons
        }

        MqttManager.MqqtConnectionStatus status = mMqttManager.getClientStatus();

        if (status == MqttManager.MqqtConnectionStatus.CONNECTING) {
            final int[] kConnectingAnimationDrawableIds = {R.drawable.mqtt_connecting1, R.drawable.mqtt_connecting2, R.drawable.mqtt_connecting3};
            mMqttMenuItem.setIcon(kConnectingAnimationDrawableIds[mMqttMenuItemAnimationFrame]);
            mMqttMenuItemAnimationFrame = (mMqttMenuItemAnimationFrame + 1) % kConnectingAnimationDrawableIds.length;
        } else if (status == MqttManager.MqqtConnectionStatus.CONNECTED) {
            mMqttMenuItem.setIcon(R.drawable.mqtt_connected);
            mMqttMenuItemAnimationHandler.removeCallbacks(mMqttMenuItemAnimationRunnable);
        } else {
            mMqttMenuItem.setIcon(R.drawable.mqtt_disconnected);
            mMqttMenuItemAnimationHandler.removeCallbacks(mMqttMenuItemAnimationRunnable);
        }
    }

    // endregion

    // region Eol

    private String getEolCharacters() {
        switch (mEolCharactersId) {
            case 1:
                return "\r";
            case 2:
                return "\n\r";
            case 3:
                return "\r\n";
            default:
                return "\n";
        }
    }

    private int getEolCharactersStringId() {
        switch (mEolCharactersId) {
            case 1:
                return R.string.uart_eolmode_r;
            case 2:
                return R.string.uart_eolmode_nr;
            case 3:
                return R.string.uart_eolmode_rn;
            default:
                return R.string.uart_eolmode_n;
        }
    }

    // endregion

    // region Export

    private void export() {
        List<UartPacket> packets = mUartData.getPacketsCache();
        if (packets.isEmpty()) {
            showDialogWarningNoTextToExport();
        } else {

            final int maxPacketsToExport = 1000;        // exportText uses a parcelable to send the text. If the text is too big a TransactionTooLargeException is thrown
            final int numPacketsToExport = Math.min(maxPacketsToExport, packets.size());
            List<UartPacket> packetsToExport = new ArrayList<>(numPacketsToExport);
            for (int i = Math.max(0, packets.size() - numPacketsToExport); i < packets.size(); i++) {
                UartPacket packet = packets.get(i);
                packetsToExport.add(new UartPacket(packet));
            }

            // Export format dialog
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle(R.string.uart_export_format_subtitle);

            final String[] formats = {"txt", "csv", "json"};
            builder.setItems(formats, (dialog, which) -> {
                switch (which) {
                    case 0: { // txt
                        String result = UartDataExport.packetsAsText(packetsToExport, mShowDataInHexFormat);
                        exportText(result);
                        break;
                    }
                    case 1: { // csv
                        String result = UartDataExport.packetsAsCsv(packetsToExport, mShowDataInHexFormat);
                        exportText(result);
                        break;
                    }
                    case 2: { // json
                        String result = UartDataExport.packetsAsJson(packetsToExport, mShowDataInHexFormat);
                        exportText(result);
                        break;
                    }
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    private void exportText(@Nullable String text) {
        // Note: text is sent in a parcelable. It shouldn't be too big to avoid TransactionTooLargeException
        if (text != null && !text.isEmpty()) {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, text);
            sendIntent.setType("text/plain");
            startActivity(Intent.createChooser(sendIntent, getResources().getText(R.string.uart_export_format_title)));
        } else {
            showDialogWarningNoTextToExport();
        }
    }


    private void showDialogWarningNoTextToExport() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        //builder.setTitle(R.string.);
        builder.setMessage(R.string.uart_export_nodata);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    // endregion

    // region UartPacketManagerBase.Listener

    @Override
    public void onUartPacket(UartPacket packet) {
        updateBytesUI();
    }

    // endregion

    // region MqttManagerListener

    @MainThread
    @Override
    public void onMqttConnected() {
        updateMqttStatus();
    }

    @MainThread
    @Override
    public void onMqttDisconnected() {
        updateMqttStatus();
    }

    // endregion

    // region Buffer Adapter

    class TimestampItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        // ViewHolder
        class ItemViewHolder extends RecyclerView.ViewHolder {
            ViewGroup mainViewGroup;
            TextView timestampTextView;
            TextView dataTextView;

            ItemViewHolder(View view) {
                super(view);

                mainViewGroup = view.findViewById(R.id.mainViewGroup);
                timestampTextView = view.findViewById(R.id.timestampTextView);
                dataTextView = view.findViewById(R.id.dataTextView);
            }
        }

        // Data
        private final Context mContext;
        private boolean mIsEchoEnabled;
        private boolean mShowDataInHexFormat;
        private UartPacketManagerBase mUartData;
        private List<UartPacket> mTableCachedDataBuffer;
        private final SimpleDateFormat mDateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        TimestampItemAdapter(@NonNull Context context) {
            super();
            mContext = context;
        }

        void setUartData(@Nullable UartPacketManagerBase uartData) {
            mUartData = uartData;
            notifyDataSetChanged();
        }

        int getCachedDataBufferSize() {
            return mTableCachedDataBuffer != null ? mTableCachedDataBuffer.size() : 0;
        }

        void setEchoEnabled(boolean isEchoEnabled) {
            mIsEchoEnabled = isEchoEnabled;
            notifyDataSetChanged();
        }

        void setShowDataInHexFormat(boolean showDataInHexFormat) {
            mShowDataInHexFormat = showDataInHexFormat;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_uart_packetitem, parent, false);
            return new TimestampItemAdapter.ItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ItemViewHolder itemViewHolder = (ItemViewHolder) holder;

            UartPacket packet = mTableCachedDataBuffer.get(position);
            final String currentDateTimeString = mDateFormat.format(new Date(packet.getTimestamp()));//DateFormat.getTimeInstance().format(new Date(packet.getTimestamp()));
            final String modeString = mContext.getString(packet.getMode() == UartPacket.TRANSFERMODE_RX ? R.string.uart_timestamp_direction_rx : R.string.uart_timestamp_direction_tx);
            final int color = colorForPacket(packet);
            final boolean isBold = isFontBoldForPacket(packet);

            itemViewHolder.timestampTextView.setText(String.format("%s %s", currentDateTimeString, modeString));

            SpannableString text = stringFromPacket(packet, mShowDataInHexFormat, color, isBold);
            itemViewHolder.dataTextView.setText(text);

            itemViewHolder.mainViewGroup.setBackgroundColor(position % 2 == 0 ? Color.WHITE : 0xeeeeee);
        }

        @Override
        public int getItemCount() {
            if (mUartData == null) {
                return 0;
            }

            if (mIsEchoEnabled) {
                mTableCachedDataBuffer = mUartData.getPacketsCache();
            } else {
                if (mTableCachedDataBuffer == null) {
                    mTableCachedDataBuffer = new ArrayList<>();
                } else {
                    mTableCachedDataBuffer.clear();
                }

                List<UartPacket> packets = mUartData.getPacketsCache();
                for (int i = 0; i < packets.size(); i++) {
                    UartPacket packet = packets.get(i);
                    if (packet != null && packet.getMode() == UartPacket.TRANSFERMODE_RX) {     // packet != null because crash found in google logs
                        mTableCachedDataBuffer.add(packet);
                    }
                }
            }

            return mTableCachedDataBuffer.size();
        }
    }

    // endregion
}