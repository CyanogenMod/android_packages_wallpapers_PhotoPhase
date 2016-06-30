package org.cyanogenmod.wallpapers.photophase;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import org.cyanogenmod.wallpapers.photophase.preferences.PreferencesProvider;

public class RequestPermissionsActivity extends Activity {

    public static final int REQUEST_STORAGE_PERMISSION = 1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!hasRequestedPermissions(this)) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_STORAGE_PERMISSION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    Intent intent = new Intent(PreferencesProvider.ACTION_SETTINGS_CHANGED);
                    intent.putExtra(PreferencesProvider.EXTRA_FLAG_REDRAW, Boolean.TRUE);
                    intent.putExtra(PreferencesProvider.EXTRA_FLAG_RECREATE_WORLD, Boolean.TRUE);
                    intent.putExtra(PreferencesProvider.EXTRA_FLAG_MEDIA_RELOAD, Boolean.TRUE);
                    intent.putExtra(PreferencesProvider.EXTRA_ACTION_MEDIA_USER_RELOAD_REQUEST,
                            Boolean.FALSE);
                    sendBroadcast(intent);
                    setResult(Activity.RESULT_OK);
                } else {
                    Toast.makeText(this, R.string.runtime_permission_warning, Toast.LENGTH_SHORT)
                            .show();
                    setResult(Activity.RESULT_CANCELED);
                }
                finish();
                return;
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public static boolean hasRequestedPermissions(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED;
    }

}
