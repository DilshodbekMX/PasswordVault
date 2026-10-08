package io.github.dilshodbekmx.passwordvault.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

/**
 * A place that cannot be reached: the provider of an app that is installed and whose process does
 * not come up. It has a process of its own in the test APK ({@code :unreachable}), and its
 * {@code onCreate} never returns, so the provider is never published; after 10 seconds the activity
 * manager gives up, ends that process and tells whoever asked for the provider that there is none
 * (no crash, so no dialog). It stands for the app of a place that crashes when it starts, or that
 * the phone cannot start in time.
 */
public final class TestUnreachableProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        while (true) SystemClock.sleep(60_000);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new IllegalStateException("never reached");
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
