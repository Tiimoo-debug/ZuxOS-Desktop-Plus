package com.zuxos.desktopplus.core.theme;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.PixelIcons;
import com.zuxos.desktopplus.logic.BevelMath;

/** Windows 98's small parts that more than one Retro surface uses. */
public final class RetroParts {

    /** The title bar of a window not in front: the face's shadow, with its text the face. */
    public static final int INACTIVE_TITLE = BevelMath.shadow(BevelMath.FACE);
    public static final int INACTIVE_TITLE_TEXT = BevelMath.FACE;
    /** The title bar of the window in front: navy, with white text. */
    public static final int ACTIVE_TITLE_TEXT = BevelMath.textOn(Theme.NAVY);

    private RetroParts() {
    }

    /** A title bar's close button: small, raised, a black pixel X, pressed in under a press. */
    public static View closeButton(Context ctx) {
        ImageView x = new ImageView(ctx);
        x.setImageDrawable(PixelIcons.close(Theme.RETRO.text()));
        int inset = Ui.dp(ctx, 3);
        x.setPadding(inset, inset, inset, inset);
        x.setBackground(Bevel.button(ctx));
        x.setContentDescription("Close");
        return x;
    }
}
