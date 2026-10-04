package com.zuxos.desktopplus.core;

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

    /** A spring with a small, visible settle - for things appearing and landing. */
    public static final TimeInterpolator SPRING = spring(0.72f);

    /** A firmer spring, no visible overshoot - for things that move but must not wobble. */
    public static final TimeInterpolator SPRING_FIRM = spring(0.9f);

    /** Durations. Springs need a little longer than eases to settle. */
    public static final long SHORT = 160L;
    public static final long MEDIUM = 280L;
    public static final long SPRING_MS = 420L;

    private Motion() {
    }

    /**
     * A damped spring, as an interpolator over the animation's duration.
     *
     * <p>The classic under-damped solution {@code 1 - e^(-zwt) (cos(wd t) + zw/wd sin(wd t))},
     * with the natural frequency chosen so the spring has settled (to within 0.2%) exactly when
     * the animation ends - which is what lets it be used with an ordinary duration.
     *
     * @param damping the damping ratio, 0 to 1: lower bounces more
     */
    public static TimeInterpolator spring(float damping) {
        final double zeta = Math.max(0.1, Math.min(0.99, damping));
        final double omega = 6.2 / zeta;
        final double omegaD = omega * Math.sqrt(1 - zeta * zeta);
        return t -> {
            if (t >= 1f) {
                return 1f;
            }
            double decay = Math.exp(-zeta * omega * t);
            double value = 1 - decay * (Math.cos(omegaD * t)
                    + (zeta * omega / omegaD) * Math.sin(omegaD * t));
            return (float) value;
        };
    }
}
