package com.zuxos.desktopplus.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.UserHandle;

import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection that survives obfuscation.
 *
 * <p>ZuxOS ships a minified launcher: the class names are intact but every field is renamed to
 * {@code a}, {@code b}, {@code c}... so nothing can be looked up by name. Types, however, are
 * not renamed, so fields are identified by what they hold - a {@code ComponentName}, a
 * {@code UserHandle}, a {@code List} of app entries - and, where a type is ambiguous, by
 * matching against a value we already know.
 */
public final class Mirror {

    private Mirror() {
    }

    /** All declared fields of a class and its superclasses, instance fields first. */
    public static List<Field> fields(Class<?> clazz) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    /** First field holding a value of exactly this type. */
    public static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Field f : fields(owner)) {
            if (type.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        return null;
    }

    /** First field whose declared type's name contains {@code fragment} (e.g. "BitmapInfo"). */
    public static Field fieldOfTypeNamed(Class<?> owner, String fragment) {
        for (Field f : fields(owner)) {
            if (f.getType().getName().contains(fragment)) {
                return f;
            }
        }
        return null;
    }

    /**
     * The field holding a {@code List} whose elements are instances of {@code elementType}.
     *
     * <p>Distinguishes the app list from the adapter-item and fast-scroll lists next to it,
     * none of which can be told apart by name any more.
     */
    public static Field listFieldOf(Object owner, Class<?> elementType) {
        for (Field f : fields(owner.getClass())) {
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                Object value = f.get(owner);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                if (list.isEmpty()) {
                    continue;
                }
                Object first = list.get(0);
                if (first != null && elementType.isInstance(first)) {
                    return f;
                }
            } catch (Throwable ignored) {
                // Unreadable field: just try the next one.
            }
        }
        return null;
    }

    public static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            L.d("could not set " + f + ": " + t);
            return false;
        }
    }

    /** The fields of Launcher3's AppInfo, located by type rather than by name. */
    public static final class AppInfoShape {
        public final Field component;
        public final Field user;
        public final Field title;
        public final Field intent;
        public final Field bitmap;

        private AppInfoShape(Field component, Field user, Field title, Field intent, Field bitmap) {
            this.component = component;
            this.user = user;
            this.title = title;
            this.intent = intent;
            this.bitmap = bitmap;
        }

        public boolean usable() {
            return component != null && title != null;
        }

        @Override
        public String toString() {
            return "component=" + name(component) + " user=" + name(user) + " title=" + name(title)
                    + " intent=" + name(intent) + " bitmap=" + name(bitmap);
        }

        private static String name(Field f) {
            return f != null ? f.getName() : "?";
        }
    }

    /** Supplies the label the system reports for an entry, used to identify the title field. */
    public interface LabelResolver {
        CharSequence labelOf(Object entry);
    }

    /**
     * Learns the shape of an app entry from real instances.
     *
     * <p>The label is the one field that cannot be found by type - an entry holds several
     * CharSequences, and {@code contentDescription} usually mirrors the title closely enough to
     * fool a single-sample guess. So: prefer a field actually called {@code title} when the
     * obfuscator left the name intact, otherwise score every candidate against the labels the
     * system reports, across many entries, and take the one that agrees most often.
     */
    public static AppInfoShape learnAppInfo(List<Object> samples, LabelResolver labels) {
        if (samples.isEmpty()) {
            return new AppInfoShape(null, null, null, null, null);
        }
        Object first = samples.get(0);
        Class<?> cls = first.getClass();

        Field component = null;
        Field user = null;
        Field intent = null;
        Field bitmap = null;
        List<Field> textFields = new ArrayList<>();

        for (Field f : fields(cls)) {
            Object value = get(f, first);
            if (component == null && ComponentName.class.isAssignableFrom(f.getType())) {
                component = f;
            } else if (user == null && UserHandle.class.isAssignableFrom(f.getType())) {
                user = f;
            } else if (intent == null && Intent.class.isAssignableFrom(f.getType())) {
                intent = f;
            } else if (bitmap == null && f.getType().getName().contains("BitmapInfo")) {
                bitmap = f;
            } else if (CharSequence.class.isAssignableFrom(f.getType())
                    || value instanceof CharSequence) {
                textFields.add(f);
            }
        }

        Field title = byName(textFields, "title");
        if (title == null) {
            title = bestLabelField(textFields, samples, labels);
        }
        if (title == null) {
            // Last resort: anything but the accessibility text, which is not the label.
            for (Field f : textFields) {
                if (!"contentDescription".equals(f.getName())) {
                    title = f;
                    break;
                }
            }
        }
        return new AppInfoShape(component, user, title, intent, bitmap);
    }

    private static Field byName(List<Field> candidates, String name) {
        for (Field f : candidates) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** The CharSequence field that matches the system's label for the most entries. */
    private static Field bestLabelField(List<Field> candidates, List<Object> samples,
            LabelResolver labels) {
        Field best = null;
        int bestScore = 0;
        for (Field f : candidates) {
            if ("contentDescription".equals(f.getName())) {
                // Mirrors the title often enough to win by luck; only used as a fallback.
                continue;
            }
            int score = 0;
            int checked = 0;
            for (Object sample : samples) {
                if (checked >= 25) {
                    break;
                }
                CharSequence expected = labels.labelOf(sample);
                if (expected == null) {
                    continue;
                }
                checked++;
                Object value = get(f, sample);
                if (value instanceof CharSequence
                        && expected.toString().contentEquals((CharSequence) value)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /** Every CharSequence field named like a label, so a folder's name lands wherever it is read. */
    public static List<Field> labelFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields(cls)) {
            String name = f.getName();
            if (CharSequence.class.isAssignableFrom(f.getType())
                    && (name.equals("title") || name.equals("contentDescription"))) {
                out.add(f);
            }
        }
        return out;
    }

    /** Creates an AppInfo: no-arg constructor if there is one, otherwise a copy of a sample. */
    public static Object instantiateLike(Object sample) {
        Class<?> cls = sample.getClass();
        try {
            Constructor<?> empty = cls.getDeclaredConstructor();
            empty.setAccessible(true);
            return empty.newInstance();
        } catch (Throwable ignored) {
            // Minified builds often drop the unused no-arg constructor.
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(sample)) {
                try {
                    c.setAccessible(true);
                    return c.newInstance(sample);
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        L.w("native drawer: cannot create an entry like " + cls.getName());
        return null;
    }
}
