package com.zuxos.desktopplus.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Which view in a window is the pane to put glass on.
 *
 * <p>This decision went wrong silently three times on the device - the log said the drawer's sheet
 * was in the window and nothing was ever glazed - because it was buried in a view walk where the
 * only way to see what it had rejected was to flash another build. It is arithmetic over four
 * numbers, so it belongs where it can be checked in a second.
 *
 * <p>The rules, and what each is for:
 * <ul>
 *   <li>big enough - a search box and a tab strip have backgrounds too, and glazing one of those
 *       leaves the sheet behind it exactly as opaque as before;</li>
 *   <li>not the whole window - a drawer's window also holds a scrim across all of it, and glazing
 *       that blurs the entire screen, which is the one thing this module must not do;</li>
 *   <li>opaque - a background you can already see through is not what is hiding the blur.</li>
 * </ul>
 */
public final class GlassPick {

    /** Smallest share of the window a view can cover and still be the pane. */
    public static final float MIN_SHARE = 0.25f;
    /** Largest. Above this it is the scrim, not the sheet. */
    public static final float MAX_SHARE = 0.95f;
    /** How opaque a background has to be to be worth replacing. */
    public static final int MIN_ALPHA = 0x80;

    private GlassPick() {
    }

    /** One view, reduced to the four numbers the decision needs. */
    public static final class Pane {

        /** Position in the window's draw order; higher is drawn later, so painted on top. */
        public final int order;
        public final long area;
        public final int argb;

        public Pane(int order, int width, int height, int argb) {
            this.order = order;
            this.area = (long) Math.max(0, width) * Math.max(0, height);
            this.argb = argb;
        }

        public boolean bigEnough(long window) {
            return window > 0 && area >= window * MIN_SHARE && area <= window * MAX_SHARE;
        }

        public boolean opaque() {
            return ((argb >>> 24) & 0xFF) >= MIN_ALPHA;
        }
    }

    /**
     * The pane to glaze, or null when this window does not hold one yet.
     *
     * <p>The biggest that passes, and on a tie the one drawn last - that is the one you can see.
     */
    public static Pane sheet(List<Pane> panes, long window) {
        Pane best = null;
        if (panes == null || window <= 0) {
            return null;
        }
        for (Pane pane : panes) {
            if (!pane.bigEnough(window) || !pane.opaque()) {
                continue;
            }
            if (best == null || pane.area > best.area
                    || (pane.area == best.area && pane.order > best.order)) {
                best = pane;
            }
        }
        return best;
    }

    /**
     * The panes that would paint over the glass once it is on.
     *
     * <p>Only what is drawn after the sheet counts: its own children and the views after it in the
     * window. Anything behind it is hidden by the glass anyway and is left alone - the drawer's
     * scrim among it, which is the launcher's own dimming and not ours to remove.
     *
     * <p>Glazing one layer while an opaque one on top of it still paints is indistinguishable from
     * doing nothing at all, which is what three rounds of this looked like from the sofa.
     */
    public static List<Pane> drawnOver(List<Pane> panes, Pane sheet, long window) {
        List<Pane> out = new ArrayList<>();
        if (panes == null || sheet == null || window <= 0) {
            return out;
        }
        for (Pane pane : panes) {
            if (pane == sheet || pane.order <= sheet.order || !pane.opaque()) {
                continue;
            }
            if (pane.area < window * MIN_SHARE) {
                // A search box or a tab strip. Those are meant to be solid.
                continue;
            }
            out.add(pane);
        }
        return out;
    }
}
