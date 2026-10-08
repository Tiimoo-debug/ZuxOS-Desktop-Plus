package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.ComponentName;

import com.zuxos.desktopplus.core.Reflect;

/**
 * What the module reads off a task the system reports: its app and its screen.
 *
 * <p>Safe in every process the module runs in, the system's included: plain reads, nothing that
 * starts or holds anything.
 */
public final class Tasks {

    /** {@link #displayOf} on a build whose tasks do not say which screen they are on. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private Tasks() {
    }

    /** The app the task belongs to: its first activity's package, else its top one's. */
    public static String packageOf(ActivityManager.RunningTaskInfo task) {
        ComponentName c = task.baseIntent != null && task.baseIntent.getComponent() != null
                ? task.baseIntent.getComponent()
                : task.topActivity != null ? task.topActivity : task.baseActivity;
        return c == null ? null : c.getPackageName();
    }

    /** The display a running or recent task is on, or {@link #UNKNOWN}. */
    public static int displayOf(Object task) {
        Object d = Reflect.field(task, "displayId");
        return d instanceof Integer ? (Integer) d : UNKNOWN;
    }
}
