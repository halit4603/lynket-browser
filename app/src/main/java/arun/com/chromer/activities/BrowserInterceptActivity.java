package arun.com.chromer.activities;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import java.util.List;

import arun.com.chromer.R;
import arun.com.chromer.db.BlacklistedApps;
import arun.com.chromer.preferences.Preferences;
import arun.com.chromer.services.AppDetectService;
import arun.com.chromer.util.Constants;
import arun.com.chromer.util.Util;
import arun.com.chromer.webheads.helper.WebHeadLauncherActivity;
import timber.log.Timber;

public class BrowserInterceptActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() == null || getIntent().getData() == null) {
            Toast.makeText(this, getString(R.string.unsupported_link), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        boolean isFromNewTab = getIntent().getBooleanExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, false);

        // Check if we should blacklist the launching app
        if (Preferences.blacklist(this)) {
            if (AppDetectService.getInstance() != null) {
                String lastApp = AppDetectService.getInstance().getLastApp();
                if (lastApp.length() > 0) {
                    Timber.d("Checking if %s should be blacklisted", lastApp);
                    List<BlacklistedApps> blacklisted = BlacklistedApps.find(BlacklistedApps.class, "package_name = ?", lastApp);
                    if (blacklisted.size() > 0) {
                        // The calling app was found in blacklisted table in DB, attempt to launch in secondary browser,
                        // if failed then show a chooser
                        performBlacklistAction();
                        // End this
                        finish();
                        return;
                    }
                }
            } else {
                // App detect service was not running. So let's start it.
                startService(new Intent(this, AppDetectService.class));
            }
        }

        // If user prefers to open in bubbles, then start the web head service which will take care
        // of pre fetching and loading the bubble. We don't need this activity anymore, so we will
        // finish this silently.
        if (Preferences.webHeads(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.web_head_permission_toast), Toast.LENGTH_LONG).show();
                    final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    launchWebHead(isFromNewTab);
                }
            } else {
                launchWebHead(isFromNewTab);
            }
        } else {
            Intent customTabActivity = new Intent(this, CustomTabActivity.class);
            customTabActivity.setData(getIntent().getData());
            customTabActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (isFromNewTab) {
                customTabActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            } else {
                customTabActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            }
            customTabActivity.putExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, isFromNewTab);
            startActivity(customTabActivity);
        }

        finish();
    }

    private void performBlacklistAction() {
        String componentFlatten = Preferences.secondaryBrowserComponent(this);
        if (componentFlatten != null && Util.isPackageInstalled(this, Preferences.secondaryBrowserPackage(this))) {
            final Intent originalIntent = getIntent();
            Intent webIntentExplicit = getOriginalIntentCopy(originalIntent);
            webIntentExplicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            webIntentExplicit.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ComponentName cN = ComponentName.unflattenFromString(componentFlatten);
            webIntentExplicit.setComponent(cN);

            try {
                startActivity(webIntentExplicit);
            } catch (ActivityNotFoundException e) {
                launchSecondaryBrowserWithIteration();
            }
        } else showIntentChooser();
    }

    private void showIntentChooser() {
        Toast.makeText(this, getString(R.string.blacklist_message), Toast.LENGTH_LONG).show();
        Intent defaultIntent = getOriginalIntentCopy(getIntent());
        Intent chooserIntent = Intent.createChooser(defaultIntent, getString(R.string.open_with));
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(chooserIntent);
    }

    private void launchWebHead(boolean isNewTab) {
        Intent webHeadLauncher = new Intent(this, WebHeadLauncherActivity.class);
        webHeadLauncher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!isNewTab)
            webHeadLauncher.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

        webHeadLauncher.putExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, isNewTab);
        webHeadLauncher.setData(getIntent().getData());
        startActivity(webHeadLauncher);
    }

    private void launchSecondaryBrowserWithIteration() {
        Intent webIntentImplicit = getOriginalIntentCopy(getIntent());
        webIntentImplicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        webIntentImplicit.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        List<ResolveInfo> resolvedActivityList = getApplicationContext().getPackageManager()
                .queryIntentActivities(webIntentImplicit, PackageManager.MATCH_ALL);

        String secondaryPackage = Preferences.secondaryBrowserPackage(this);

        if (secondaryPackage != null) {
            boolean found = false;
            for (ResolveInfo info : resolvedActivityList) {
                if (info.activityInfo.packageName.equalsIgnoreCase(secondaryPackage)) {
                    found = true;

                    ComponentName componentName = new ComponentName(info.activityInfo.packageName,
                            info.activityInfo.name);
                    webIntentImplicit.setComponent(componentName);

                    // This will be the new component, so write it to preferences
                    Preferences.secondaryBrowserComponent(this, componentName.flattenToString());

                    startActivity(webIntentImplicit);
                }
            }
            if (!found) showIntentChooser();
        }
    }

    @NonNull
    private Intent getOriginalIntentCopy(@NonNull Intent originalIntent) {
        Intent copy = new Intent(Intent.ACTION_VIEW, originalIntent.getData());
        if (originalIntent.getExtras() != null) {
            copy.putExtras(originalIntent.getExtras());
        }
        return copy;
    }
}
