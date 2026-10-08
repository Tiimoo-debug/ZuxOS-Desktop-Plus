package com.zuxos.desktopplus.core.motion;

import android.animation.TimeInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The module's motion, in one place.
 *
 * <p>Modelled on how iOS moves things: anything that appears or settles does so on a damped
 * spring - fast off the mark, a touch past the target, then still - and anything that simply
 * slides uses the same long, soft ease-out iOS uses for its sheets. Plain decelerate curves stop
 * too abruptly and linear ones look mechanical; these are what make motion read as physical.
 */
public final class Motion {

    /** The iOS sheet curve: a quick start and a long, soft landing. */
    public static final TimeInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    /** For things leaving: they accelerate away rather than easing to a stop. */
    public static final TimeInterpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);

    /*
     * Two of the springs SwiftUI names, which is what iOS motion is made of:
     *   smooth - critically damped, no overshoot: for moving and resizing;
     *   snappy - one barely-there settle: for appearing, hovering, pressing.
     * SwiftUI's third, bouncy, is not used: a visible wobble reads as a toy on a desktop.
     */
    public static final TimeInterpolator SMOOTH = spring(0.99f);
    public static final TimeInterpolator SNAPPY = spring(0.86f);

    /** For things appearing and landing. */
    public static final TimeInterpolator SPRING = SNAPPY;

    /** For things that move but must not wobble. */
    public static final TimeInterpolator SPRING_FIRM = SMOOTH;

    /** iOS's default spring, as the hover and preview use it. */
    public static final TimeInterpolator IOS = SNAPPY;

    /** Durations. Springs need a little longer than eases to settle. */
    public static final long SHORT = 160L;
    public static final long MEDIUM = 280L;
    public static final long SPRING_MS = 420L;
    /** A smooth spring needs a little longer to land than a snappy one. */
    public static final long SMOOTH_MS = 460L;
    /** How long {@link #IOS} takes to land: iOS's response of about 0.38s. */
    public static final long IOS_MS = 380L;

    private Motion() {
    }

    /**
     * A damped spring, as an interpolator over the animation's duration.
     *
     * <p>The classic under-damped solution {@code 1 - e^(-zwt) (cos(wd t) + zw/wd sin(wd t))},
     * with the natural frequency chosen so the spring has all but settled when the animation
     * ends - which is what lets it be used with an ordinary duration.
     *
     * <p>Scaled so it lands on exactly 1 at the end. Unscaled, the near-critical spring stopped
     * 1.2% short and snapped the rest in the last frame - seven pixels on a sheet sliding 600.
     *
     * @param damping the damping ratio, 0 to 1: lower bounces more
     */
    public static TimeInterpolator spring(float damping) {
        final double zeta = Math.max(0.1, Math.min(0.99, damping));
        final double omega = 6.2 / zeta;
        final double omegaD = omega * Math.sqrt(1 - zeta * zeta);
        final double end = raw(1.0, zeta, omega, omegaD);
        return t -> t >= 1f ? 1f : (float) (raw(t, zeta, omega, omegaD) / end);
    }

    private static double raw(double t, double zeta, double omega, double omegaD) {
        double decay = Math.exp(-zeta * omega * t);
        return 1 - decay * (Math.cos(omegaD * t)
                + (zeta * omega / omegaD) * Math.sin(omegaD * t));
    }
}
