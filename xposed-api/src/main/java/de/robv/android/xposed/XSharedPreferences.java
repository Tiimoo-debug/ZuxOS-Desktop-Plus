package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Stub. See xposed-api/build.gradle.
 *
 * <p>The real class implements {@code android.content.SharedPreferences}; this stub does not,
 * so module code must use the {@code XSharedPreferences} type directly rather than assigning
 * to a {@code SharedPreferences} variable.
 */
public final class XSharedPreferences {
    public XSharedPreferences(String packageName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public XSharedPreferences(File prefFile) {
        throw new UnsupportedOperationException("Stub!");
    }

    public File getFile() {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean makeWorldReadable() {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean hasFileChanged() {
        throw new UnsupportedOperationException("Stub!");
    }

    public void reload() {
        throw new UnsupportedOperationException("Stub!");
    }

    public Map<String, ?> getAll() {
        throw new UnsupportedOperationException("Stub!");
    }

    public String getString(String key, String defValue) {
        throw new UnsupportedOperationException("Stub!");
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        throw new UnsupportedOperationException("Stub!");
    }

    public int getInt(String key, int defValue) {
        throw new UnsupportedOperationException("Stub!");
    }

    public long getLong(String key, long defValue) {
        throw new UnsupportedOperationException("Stub!");
    }

    public float getFloat(String key, float defValue) {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean getBoolean(String key, boolean defValue) {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean contains(String key) {
        throw new UnsupportedOperationException("Stub!");
    }
}
