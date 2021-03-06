package com.github.andlyticsproject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.view.Window;
import com.github.andlyticsproject.Preferences.StatsMode;
import com.github.andlyticsproject.Preferences.Timeframe;
import com.github.andlyticsproject.admob.AdmobRequest;
import com.github.andlyticsproject.dialog.ExportDialog;
import com.github.andlyticsproject.dialog.ImportDialog;
import com.github.andlyticsproject.exception.AuthenticationException;
import com.github.andlyticsproject.exception.InvalidJSONResponseException;
import com.github.andlyticsproject.exception.NetworkException;
import com.github.andlyticsproject.io.ServiceExceptoin;
import com.github.andlyticsproject.io.StatsCsvReaderWriter;
import com.github.andlyticsproject.model.Admob;
import com.github.andlyticsproject.model.AppInfo;
import com.github.andlyticsproject.sync.AutosyncHandler;
import com.github.andlyticsproject.sync.AutosyncHandlerFactory;
import com.github.andlyticsproject.sync.NotificationHandler;
import com.github.andlyticsproject.util.ChangelogBuilder;
import com.github.andlyticsproject.util.Utils;

public class Main extends BaseActivity implements AuthenticationCallback {

	/** Key for latest version code preference. */
	private static final String LAST_VERSION_CODE_KEY = "last_version_code";

    public static final String TAG = Main.class.getSimpleName();
    private boolean cancelRequested;
    private ListView mainListView;
    private ContentAdapter db;
    private TextView statusText;
    private ViewSwitcher mainViewSwitcher;
    private MainListAdapter adapter;
    public boolean dotracking;
    private View footer;

    private boolean isAuthenticationRetry;
    public Animation aniPrevIn;
    private View statsModeToggle;
    private StatsMode currentStatsMode;
    private TextView statsModeText;
    private ImageView statsModeIcon;
    public ExportDialog exportDialog;
    public ImportDialog importDialog;

    private static final int FEEDBACK_DIALOG = 0;



    /** Called when the activity is first created. */
    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        setSupportProgressBarIndeterminateVisibility(false);


        db = getDbAdapter();
        LayoutInflater layoutInflater = getLayoutInflater();

        // setup main list
        mainListView = (ListView) findViewById(R.id.main_app_list);
        mainListView.addHeaderView(layoutInflater.inflate(R.layout.main_list_header, null), null, false);
        footer = layoutInflater.inflate(R.layout.main_list_footer, null);
        footer.setVisibility(View.INVISIBLE);
        TextView accountNameTextView = (TextView) footer.findViewById(R.id.main_app_account_name);
        accountNameTextView.setText(accountname);
        mainListView.addFooterView(footer, null, false);
        adapter = new MainListAdapter(this, accountname, db, currentStatsMode);
        mainListView.setAdapter(adapter);
        mainViewSwitcher = (ViewSwitcher) findViewById(R.id.main_viewswitcher);

        // status & progess bar
        statusText = (TextView) findViewById(R.id.main_app_status_line);

        statsModeToggle = (View) findViewById(R.id.main_button_statsmode);
        statsModeText = (TextView) findViewById(R.id.main_button_statsmode_text);
        statsModeIcon = (ImageView) findViewById(R.id.main_button_statsmode_icon);

        aniPrevIn = AnimationUtils.loadAnimation(Main.this, R.anim.activity_fade_in);

        statsModeToggle.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                if (currentStatsMode.equals(StatsMode.PERCENT)) {
                    currentStatsMode = StatsMode.DAY_CHANGES;
                } else {
                    currentStatsMode = StatsMode.PERCENT;
                }

                updateStatsMode();

            }
        });

        View buttonLogout = (View) findViewById(R.id.main_button_logout);
        buttonLogout.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Preferences.removeAccountName(Main.this);
                Preferences.saveSkipAutoLogin(Main.this, true);
                Intent intent = new Intent(Main.this, LoginActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.activity_fade_in, R.anim.activity_fade_out);
            }
        });

        dotracking = true;
        isAuthenticationRetry = false;

        currentStatsMode = Preferences.getStatsMode(this);
        updateStatsMode();

        final List<AppInfo> lastAppList = (List<AppInfo>) getLastNonConfigurationInstance();
        if (lastAppList != null) {
            getAndlyticsApplication().setSkipMainReload(true);

        }

     // show changelog
		if (isUpdate()) {
			showChangelog();
		}
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean mainSkipDataReload = getAndlyticsApplication().isSkipMainReload();

        if (!mainSkipDataReload) {
            new LoadDbEntries().execute(true);
        } else {
            new LoadDbEntries().execute(false);
        }

        getAndlyticsApplication().setSkipMainReload(false);
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getSupportMenuInflater().inflate(R.menu.main_menu, menu);
		return true;
	}

	/**
	 * Called if item in option menu is selected.
	 *
	 * @param item
	 *            The chosen menu item
	 * @return boolean true/false
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.itemMainmenuRefresh:
			authenticateAccountFromPreferences(false, Main.this);
			break;
		case R.id.itemMainmenuImport:
			(new LoadImportDialog()).execute();
			break;
		case R.id.itemMainmenuExport:
			(new LoadExportDialog()).execute();
			break;
		case R.id.itemMainmenuFeedback:
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AndlyticsProject/andlytics/issues")));
			break;
		case R.id.itemMainmenuPreferences:
			Intent i = new Intent(this, PreferenceActivity.class);
			i.putExtra(Constants.AUTH_ACCOUNT_NAME, accountname);
			startActivity(i);
			break;
		default:
			return false;
		}
		return true;
	}

    @Override
    public Object onRetainNonConfigurationInstance() {
        return adapter.getAppInfos();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void updateMainList(List<AppInfo> apps) {

        if (apps != null) {

            if (apps.size() > 0) {
                footer.setVisibility(View.VISIBLE);

                String autosyncSet = Preferences.getAutosyncSet(Main.this, accountname);
                if (autosyncSet == null) {

                    // set autosync default value
                    AutosyncHandlerFactory.getInstance(Main.this).setAutosyncPeriod(accountname,
                                    AutosyncHandler.DEFAULT_PERIOD);

                    Preferences.saveAutosyncSet(Main.this, accountname);
                }
            }

            adapter.setAppInfos(apps);
            adapter.notifyDataSetChanged();

            Date lastUpdateDate = null;

            for (int i = 0; i < apps.size(); i++) {
                Date dateObject = apps.get(i).getLastUpdate();
                if (lastUpdateDate == null || lastUpdateDate.before(dateObject)) {
                    lastUpdateDate = dateObject;
                }
            }

            if (lastUpdateDate != null) {
                statusText.setText(this.getString(R.string.last_update) + ": " + ContentAdapter.formatDate(lastUpdateDate));
            }

        }

        if (!(R.id.main_app_list == mainViewSwitcher.getCurrentView().getId())) {
            mainViewSwitcher.showNext();
        }

    }

    private class LoadRemoteEntries extends AsyncTask<String, Integer, Exception> {

        @SuppressWarnings("unchecked")
        @Override
        protected Exception doInBackground(String... params) {

            // authentication failed before, retry with token invalidation

            Exception exception = null;



                String authtoken = ((AndlyticsApp) getApplication()).getAuthToken();


                List<AppInfo> appDownloadInfos = null;
                try {

                    DeveloperConsole console = new DeveloperConsole(Main.this);
                    appDownloadInfos = console.getAppDownloadInfos(authtoken, accountname);

                    if (cancelRequested) {
                        cancelRequested = false;
                        return null;
                    }

                    Map<String, List<String>> admobAccountSiteMap = new HashMap<String, List<String>>();

                    List<AppStatsDiff> diffs = new ArrayList<AppStatsDiff>();

                    for (AppInfo appDownloadInfo : appDownloadInfos) {
                        // update in database and check for diffs
                        diffs.add(db.insertOrUpdateStats(appDownloadInfo));
                        String admobSiteId = Preferences.getAdmobSiteId(Main.this, appDownloadInfo.getPackageName());
                        if(admobSiteId != null) {
                            String admobAccount = Preferences.getAdmobAccount(Main.this, admobSiteId);
                            if(admobAccount != null) {
                                List<String> siteList = admobAccountSiteMap.get(admobAccount);
                                if(siteList == null) {
                                    siteList = new ArrayList<String>();
                                }
                                siteList.add(admobSiteId);
                                admobAccountSiteMap.put(admobAccount, siteList);
                            }
                        }
                    }

                    // check for notifications
                    NotificationHandler.handleNotificaions(Main.this, diffs, accountname);

                    // sync admob accounts
                    Set<String> admobAccuntKeySet = admobAccountSiteMap.keySet();
                    for (String admobAccount : admobAccuntKeySet) {

                        AdmobRequest.syncSiteStats(admobAccount, Main.this, admobAccountSiteMap.get(admobAccount), null);
                    }

                    new LoadIconInCache().execute(appDownloadInfos);

                } catch (Exception e) {

                    if(e instanceof IOException) {
                        e = new NetworkException(e);
                    }

                    exception = e;

                    Log.e(TAG, "error while requesting developer console", e);
                }

                if (dotracking == true) {
                    int size = 0;
                    if (appDownloadInfos != null) {
                        size = appDownloadInfos.size();
                    }
                    // TODO endless loop in case of exception!!!
                    if (exception == null) {
                        Map<String, String> map = new HashMap<String, String>();
                        map.put("num", size + "");
                    } else {
                    }
                    dotracking = false;
                }



            return exception;
        }

        /*
         * (non-Javadoc)
         *
         * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
         */
        @Override
        protected void onPostExecute(Exception e) {

            setSupportProgressBarIndeterminateVisibility(false);

            if (e != null) {

                if ((e instanceof InvalidJSONResponseException || e instanceof AuthenticationException)
                                && !isAuthenticationRetry) {
                    Log.w("Andlytics", "authentication faild, retry with new token");
                    isAuthenticationRetry = true;
                    authenticateAccountFromPreferences(true, Main.this);


                } else {
                    handleUserVisibleException(e);
                    new LoadDbEntries().execute(false);
                }

            } else {
                new LoadDbEntries().execute(false);
            }

        }

        /*
         * (non-Javadoc)
         *
         * @see android.os.AsyncTask#onPreExecute()
         */
        @Override
        protected void onPreExecute() {
            setSupportProgressBarIndeterminateVisibility(true);
        }

    }

    private class LoadDbEntries extends AsyncTask<Boolean, Void, Boolean> {

        private List<AppInfo> allStats = new ArrayList<AppInfo>();
        private List<AppInfo> filteredStats = new ArrayList<AppInfo>();

        private Boolean triggerRemoteCall;

        @Override
        protected Boolean doInBackground(Boolean... params) {

            allStats = db.getAllAppsLatestStats(accountname);

            for (AppInfo appInfo : allStats) {

                if (!appInfo.isGhost()) {
                    String admobSiteId = Preferences.getAdmobSiteId(Main.this, appInfo.getPackageName());
                    if(admobSiteId != null) {
                        List<Admob> admobStats = db.getAdmobStats(admobSiteId, Timeframe.LAST_TWO_DAYS).getAdmobs();
                        if(admobStats.size() > 0) {
                            Admob admob = admobStats.get(admobStats.size() -1);
                            appInfo.setAdmobStats(admob);
                        }
                    }
                    filteredStats.add(appInfo);
                }

            }


            triggerRemoteCall = params[0];

            return null;
        }

        @Override
        protected void onPostExecute(Boolean result) {

            updateMainList(filteredStats);

            if (triggerRemoteCall) {
                authenticateAccountFromPreferences(false, Main.this);

            } else {

                if (allStats.size() == 0) {
                    Toast.makeText(Main.this, R.string.no_published_apps, Toast.LENGTH_LONG).show();
                }
            }

        }

    }

    private class LoadIconInCache extends AsyncTask<List<AppInfo>, Void, Boolean> {

        @Override
        protected Boolean doInBackground(List<AppInfo>... params) {

            List<AppInfo> appInfos = params[0];

            Boolean success = false;

            for (AppInfo appInfo : appInfos) {

                String iconUrl = appInfo.getIconUrl();

                if (iconUrl != null) {

                    File iconFile = new File(getCacheDir() + "/" + appInfo.getIconName());
                    if (!iconFile.exists()) {

                        try {
                            iconFile.createNewFile();
                            URL url = new URL(iconUrl);
                            HttpURLConnection c = (HttpURLConnection) url.openConnection();
                            c.setRequestMethod("GET");
                            //c.setDoOutput(true);
                            c.connect();

                            FileOutputStream fos = new FileOutputStream(iconFile);

                            InputStream is = c.getInputStream();

                            byte[] buffer = new byte[1024];
                            int len1 = 0;
                            while ((len1 = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, len1);
                            }
                            fos.close();
                            is.close();

                            success = true;
                        } catch (IOException e) {

                            if (iconFile.exists()) {
                                iconFile.delete();
                            }

                            Log.d("log_tag", "Error: " + e);
                        }

                    }
                }

            }

            return success;

        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                adapter.notifyDataSetChanged();
            }
        }

    }

    private class LoadExportDialog extends AsyncTask<Boolean, Void, Boolean> {

        private List<AppInfo> allStats;

        @Override
        protected Boolean doInBackground(Boolean... params) {

            allStats = db.getAllAppsLatestStats(accountname);

            return null;
        }

        @Override
        protected void onPostExecute(Boolean result) {

            if (!isFinishing()) {
                exportDialog = new ExportDialog(Main.this, allStats, accountname);
                exportDialog.show();
            }
        }

    }

    private class LoadImportDialog extends AsyncTask<Boolean, Void, Boolean> {

        private List<String> fileNames;

        @Override
        protected Boolean doInBackground(Boolean... params) {

            if (android.os.Environment.getExternalStorageState().equals(
                            android.os.Environment.MEDIA_MOUNTED)) {

                List<AppInfo> allStats = db.getAllAppsLatestStats(accountname);
                try {
                    fileNames = StatsCsvReaderWriter.getImportFileNames(accountname, allStats);
                } catch (ServiceExceptoin e) {
                    e.printStackTrace();
                    return false;
                }
                return true;

            } else {

                return false;
            }

        }

        @Override
        protected void onPostExecute(Boolean result) {

            if (!isFinishing()) {

                if(result) {
                    importDialog = new ImportDialog(Main.this, fileNames, accountname);
                    importDialog.show();
                } else {
                    Toast.makeText(Main.this, "SD-Card not mounted or invalid file format, can't import!", Toast.LENGTH_LONG).show();
                }
            }
        }

    }

    @Override
    public void onBackPressed() {
        Preferences.removeAccountName(Main.this);
        super.onBackPressed();
    }


    @Override
    protected void onStop() {
        super.onStop();
    }

    private void updateStatsMode() {
        switch (currentStatsMode) {
        case PERCENT:
            statsModeText.setText(this.getString(R.string.daily));
            statsModeIcon.setImageDrawable(getResources().getDrawable(R.drawable.icon_plusminus));
            break;

        case DAY_CHANGES:
            statsModeText.setText(this.getString(R.string.percentage));
            statsModeIcon.setImageDrawable(getResources().getDrawable(R.drawable.icon_percent));
            break;

        default:
            break;
        }

        adapter.setStatsMode(currentStatsMode);
        adapter.notifyDataSetChanged();
        Preferences.saveStatsMode(currentStatsMode, Main.this);
    }
    /*
    @SuppressWarnings("unchecked")
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_I) {
            Intent intent = new Intent(this, DemoDataActivity.class);
            intent.putExtra(Constants.AUTH_ACCOUNT_NAME, accountname);
            startActivity(intent);
            return true;
        } else if ((keyCode == KeyEvent.KEYCODE_BACK)) {
            Preferences.removeAccountName(Main.this);
            Intent intent = new Intent(Main.this, LoginActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.activity_fade_in, R.anim.activity_fade_out);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_D) {

            try {
                //List<AppInfo> allAppsLatestStats = db.getAllAppsLatestStats(accountname);
                //for (AppInfo appInfo : allAppsLatestStats) {
                    //db.deleteAllForPackageName(appInfo.getPackageName());
                //}
            } catch (Exception e) {
                showCrashDialog(e);
            }
            return true;
        } else {

            try {
                Integer.parseInt(event.getNumber() + "");

                DeveloperConsole console = new DeveloperConsole(Main.this);
                List<AppInfo> appDownloadInfos;
                try {
                    appDownloadInfos = console.parseAppStatisticsResponse(DemoDataActivity.readTestData(event.getNumber()),
                            accountname);

                    for (AppInfo appDownloadInfo : appDownloadInfos) {
                        // update in database

                        db.insertOrUpdateStats(appDownloadInfo);

                        new LoadIconInCache().execute(appDownloadInfos);

                    }
                } catch (AuthenticationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

            } catch (NumberFormatException e) {
                e.printStackTrace();
            } catch (DeveloperConsoleException e) {
                e.printStackTrace();
            } catch (InvalidJSONResponseException e) {
                e.printStackTrace();
            }

        }
        return false;
    }*/

    //TODO remove...
    @Override
    protected Dialog onCreateDialog(int id) {

        Dialog dialog = null;

        switch (id) {
        case FEEDBACK_DIALOG:
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AndlyticsProject/andlytics/issues")));
/*
            FeedbackDialog.FeedbackDialogBuilder builder = new FeedbackDialogBuilder(Main.this);
            builder.setTitle(this.getString(R.string.feedback));

            builder.setMessage(this.getString(R.string.help_us));
            builder.setPositiveButton(this.getString(R.string.send), new DialogInterface.OnClickListener() {

                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }

            });
            builder.setNegativeButton(this.getString(R.string.cancel), new DialogInterface.OnClickListener() {

                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }

            });

            dialog = builder.create(accountname + "\n\n", getApplication());
*/
            break;

        default:
            break;
        }

        return dialog;
    }

    @Override
    public void authenticationSuccess() {
        new LoadRemoteEntries().execute();
    }


    //FIXME isUpdate

	/**
	 * checks if the app is started for the first time (after an update).
	 *
	 * @return <code>true</code> if this is the first start (after an update)
	 *         else <code>false</code>
	 */
	private boolean isUpdate() {
		// Get the versionCode of the Package, which must be different
		// (incremented) in each release on the market in the
		// AndroidManifest.xml
		final int versionCode = Utils.getActualVersionCode(this);

		final SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);
		final long lastVersionCode = prefs.getLong(LAST_VERSION_CODE_KEY, 0);

		if (versionCode != lastVersionCode) {
			Log.i(TAG, "versionCode " + versionCode
					+ " is different from the last known version "
					+ lastVersionCode);
			return true;
		} else {
			Log.i(TAG, "versionCode " + versionCode + " is already known");
			return false;
		}
	}

	private void showChangelog() {
		final int versionCode = Utils.getActualVersionCode(this);
		final SharedPreferences sp = PreferenceManager
				.getDefaultSharedPreferences(this);
		ChangelogBuilder.create(this, new Dialog.OnClickListener() {

			public void onClick(DialogInterface dialogInterface, int i) {
				// Mark this version as read
				sp.edit().putLong(LAST_VERSION_CODE_KEY, versionCode).commit();

				dialogInterface.dismiss();
			}
		}).show();
	}

}
