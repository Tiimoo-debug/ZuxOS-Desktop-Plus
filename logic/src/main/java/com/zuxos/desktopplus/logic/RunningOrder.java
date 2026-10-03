package com.zuxos.desktopplus.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What goes in the taskbar's row of open apps, and in what order.
 *
 * <p>Separated from the views because both halves of it have been wrong on the device and neither
 * needs a device to be wrong: the order came straight out of the activity manager, which is
 * most-recent-first, so every icon changed place every three seconds; and nothing checked whether
 * the row still fitted, so it ran underneath the clock.
 */
public final class RunningOrder {

    /** What {@link #fits} answers when there is not enough measured yet to say. */
    public static final int UNKNOWN = -1;

    private RunningOrder() {
    }

    /**
     * The same apps, in an order that holds still.
     *
     * <p>The ones already on screen keep their places, and apps that have opened since join at the
     * end. Following the source order instead is what made the row shuffle itself.
     *
     * @param shown what is on screen now, in the order it is on screen
     * @param open  what is open, in whatever order it arrived
     */
    public static List<String> inOrder(List<String> shown, Collection<String> open) {
        List<String> out = new ArrayList<>();
        if (shown != null) {
            for (String pkg : shown) {
                if (open.contains(pkg) && !out.contains(pkg)) {
                    out.add(pkg);
                }
            }
        }
        for (String pkg : open) {
            if (!out.contains(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /**
     * How many icons fit in the room there is.
     *
     * <p>The gap only falls between icons, so n icons take n sizes and n-1 gaps - which is why the
     * room has a gap added to it before dividing rather than one taken off each icon.
     *
     * @return the count, at least one, or {@link #UNKNOWN} when nothing has been measured yet
     */
    public static int fits(int room, int size, int gap) {
        int step = size + Math.max(0, gap);
        if (room <= 0 || size <= 0 || step <= 0) {
            return UNKNOWN;
        }
        return Math.max(1, (room + Math.max(0, gap)) / step);
    }

    /**
     * The list cut down to what fits, from the end.
     *
     * <p>The front of the row is the apps that have been open longest and have kept their places,
     * so the ones to drop are the newest. Returns a new list; the one passed in is not touched.
     */
    public static List<String> trimToFit(List<String> wanted, int room, int size, int gap) {
        List<String> out = new ArrayList<>(wanted);
        int fits = fits(room, size, gap);
        if (fits == UNKNOWN) {
            // Nothing measured yet. The next layout places the row and asks again; dropping icons
            // on the strength of a zero would empty the row on the first pass every time.
            return out;
        }
        while (out.size() > fits) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
