package hideji.hayakawa.ponto;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main activity demonstrating how to pass extra parameters to an activity that
 * recognizes text.
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final int AUTHORIZATION_CODE = 1993;
    private static final int ACCOUNT_CODE = 1601;

    private AuthPreferences authPreferences;
    private AccountManager accountManager;

    // Use a compound button so either checkbox or switch widgets work.
    private CompoundButton autoFocus;
    private CompoundButton useFlash;
    private TextView statusMessage;
    private TextView textValue;
    private Button btnNow;
    private MenuItem item;

    private static final int RC_OCR_CAPTURE = 9003;
    private static final String TAG = "MainActivity";

    private HttpTransport transport = AndroidHttp.newCompatibleTransport();
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    SharedPreferences shared;

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        shared = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        statusMessage = findViewById(R.id.status_message);
        textValue = findViewById(R.id.text_value);

        autoFocus = findViewById(R.id.auto_focus);
        useFlash = findViewById(R.id.use_flash);

        findViewById(R.id.read_text).setOnClickListener(this);

        btnNow = findViewById(R.id.btnNow);
        btnNow.setOnClickListener(new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                  SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm"),
                          dateFormat = new SimpleDateFormat("dd/MM");
                  Calendar calendar = Calendar.getInstance();

                  SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

                  if (shared.getBoolean("writeSheet", false)) {
                      String spreadsheetId = shared.getString("sheetId", "");

                      if(!spreadsheetId.isEmpty()){
                          boolean success = writeToSpreadSheet(spreadsheetId, timeFormat.format(calendar.getTime()),
                                  dateFormat.format(calendar.getTime()));
                          Toast toast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_SHORT);
                          toast.setGravity(Gravity.CENTER, 0, 0);

                          toast.setText(success ? "valor inserido" : "data não encontrada ou sem espaço");
                          toast.show();
                      }
                  }

                  int hour = LocalTime.now().getHour();
                  if (hour < Integer.parseInt(shared.getString("maxHour", "10"))) {
                      calendar.add(Calendar.HOUR_OF_DAY, Integer.parseInt(shared.getString("hoursPerDay", "8")));
                  }
                  calendar.add(Calendar.MINUTE, 55);
                  setAlarm(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
              }
            });

        if (shared.getBoolean("writeSheet", false)) {
            accountManager = AccountManager.get(this);

            authPreferences = new AuthPreferences(this);
            if (authPreferences.getUser() != null
                    && authPreferences.getToken() != null) {
                refreshToken();
            } else {
                chooseAccount();
            }
        }
    }

    public boolean openSettingsActivity(MenuItem item){
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        return true;
    }

    private void chooseAccount() {
        // use https://github.com/frakbot/Android-AccountChooser for
        // compatibility with older devices
        Intent intent = AccountManager.newChooseAccountIntent(null, null,
                new String[] { "com.google" }, false, null, null, null, null);
        startActivityForResult(intent, ACCOUNT_CODE);
    }

    private void requestToken() {
        Account userAccount = null;
        String user = authPreferences.getUser();
        for (Account account : accountManager.getAccountsByType("com.google")) {
            if (account.name.equals(user)) {
                userAccount = account;

                break;
            }
        }

        accountManager.getAuthToken(userAccount, "oauth2:https://www.googleapis.com/auth/spreadsheets", null, this,
                new OnTokenAcquired(), null);
    }

    /**
     * call this method if your token expired, or you want to request a new
     * token for whatever reason. call requestToken() again afterwards in order
     * to get a new token.
     */
    private void invalidateToken() {
        AccountManager accountManager = AccountManager.get(this);
        accountManager.invalidateAuthToken("com.google",
                authPreferences.getToken());

        authPreferences.setToken(null);
    }

    private class OnTokenAcquired implements AccountManagerCallback<Bundle> {

        @Override
        public void run(AccountManagerFuture<Bundle> result) {
            try {
                Bundle bundle = result.getResult();

                Intent launch = (Intent) bundle.get(AccountManager.KEY_INTENT);
                if (launch != null) {
                    startActivityForResult(launch, AUTHORIZATION_CODE);
                } else {
                    String token = bundle
                            .getString(AccountManager.KEY_AUTHTOKEN);

                    authPreferences.setToken(token);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Called when a view has been clicked.
     *
     * @param v The view that was clicked.
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.read_text) {
            // launch Ocr capture activity.
            Intent intent = new Intent(this, OcrCaptureActivity.class);
            intent.putExtra(OcrCaptureActivity.AutoFocus, autoFocus.isChecked());
            intent.putExtra(OcrCaptureActivity.UseFlash, useFlash.isChecked());

            startActivityForResult(intent, RC_OCR_CAPTURE);
        }
    }

    /**
     * Called when an activity you launched exits, giving you the requestCode
     * you started it with, the resultCode it returned, and any additional
     * data from it.  The <var>resultCode</var> will be
     * {@link #RESULT_CANCELED} if the activity explicitly returned that,
     * didn't return any result, or crashed during its operation.
     * <p/>
     * <p>You will receive this call immediately before onResume() when your
     * activity is re-starting.
     * <p/>
     *
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode  The integer result code returned by the child activity
     *                    through its setResult().
     * @param data        An Intent, which can return result data to the caller
     *                    (various data can be attached to Intent "extras").
     * @see #startActivityForResult
     * @see #createPendingResult
     * @see #setResult(int)
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == RC_OCR_CAPTURE) {
            if (resultCode == CommonStatusCodes.SUCCESS) {
                if (data != null) {
                    String text = data.getStringExtra(OcrCaptureActivity.TextBlockObject);
                    statusMessage.setText(R.string.ocr_success);
                    textValue.setText("");

                    String pattern = "(\\d\\d/\\d\\d)/\\d\\d\\d\\d (\\d\\d:\\d\\d)";

                    Pattern r = Pattern.compile(pattern);

                    Matcher m = r.matcher(text);

                    if (m.find() && m.groupCount()==2) {
                        final String day = m.group(1), time = m.group(2);

                        textValue.setText(day + " " + time);
                        String spreadsheetId = shared.getString("sheetId", "");

                        if(!spreadsheetId.isEmpty() && shared.getBoolean("writeSheet", false)) {
                            writeToSpreadSheet(spreadsheetId, time, day);
                        }
                    }
                } else {
                    statusMessage.setText(R.string.ocr_failure);
                    Log.d(TAG, "No Text captured, intent data is null");
                }
            }
            else {
                statusMessage.setText(String.format(getString(R.string.ocr_error),
                        CommonStatusCodes.getStatusCodeString(resultCode)));
            }
        }
        else if (requestCode == AUTHORIZATION_CODE) {
            requestToken();
        } else if (requestCode == ACCOUNT_CODE) {
            String accountName = data
                    .getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            authPreferences.setUser(accountName);

            // invalidate old tokens which might be cached. we want a fresh
            // one, which is guaranteed to work
            invalidateToken();

            requestToken();
        }
        else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private GoogleCredential getCredential()
    {
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(new NetHttpTransport())
                .setJsonFactory(JacksonFactory.getDefaultInstance())
                .build();
        credential.setAccessToken(authPreferences.getToken());

        return credential;
    }

    private void refreshToken()
    {
        new Thread() {
            @Override
            public void run() {
                Sheets sheetsService;
                sheetsService = new Sheets.Builder(transport, JSON_FACTORY, getCredential())
                        .setApplicationName("Ponto")
                        .build();

                String range = "Corrente!A1:E31";
                ValueRange result = null;
                try {
                    String spreadsheetId = shared.getString("sheetId", "");

                    if(!spreadsheetId.isEmpty()) {
                        result = sheetsService.spreadsheets().values()
                                .get(spreadsheetId, range)
                                .execute();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (result == null)
                    chooseAccount();
            }
        }.start();
    }

    private boolean writeToSpreadSheet(final String spreadsheetId, final String value, final String day)
    {
        final boolean[] success = new boolean[]{ false };
        final CountDownLatch latch = new CountDownLatch(1);

        new Thread() {
            @Override
            public void run() {
                Sheets sheetsService;
                sheetsService = new Sheets.Builder(transport, JSON_FACTORY, getCredential())
                        .setApplicationName("Ponto")
                        .build();

                String range = "Corrente!A1:E31";
                ValueRange result = null;
                try {
                    result = sheetsService.spreadsheets().values()
                            .get(spreadsheetId, range)
                            .execute();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (result == null)
                {
                    chooseAccount();
                }
                else {
                    List<List<Object>> values = result.getValues();
                    List<List<Object>> updateValue = new ArrayList<List<Object>>();
                    updateValue.add(new ArrayList<>());

                    for (List<Object> item :
                            values) {
                        if (item.size() < 5 && item.size() > 0 && item.get(0).equals(day)) {
                            updateValue.get(0).add(value);
                            try {
                                sheetsService.spreadsheets().values()
                                        .update(spreadsheetId,
                                                "Corrente!" + (char) ('A' + item.size()) + (1 + values.indexOf(item)),
                                                new ValueRange().setValues(updateValue))
                                        .setValueInputOption("USER_ENTERED")
                                        .execute();
                                success[0] = true;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                        }
                    }
                    latch.countDown();
                }
            }
        }.start();

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return success[0];
    }

    private void setAlarm(int hours, int minutes)
    {
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, hours);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minutes);
        startActivity(intent);
    }
}