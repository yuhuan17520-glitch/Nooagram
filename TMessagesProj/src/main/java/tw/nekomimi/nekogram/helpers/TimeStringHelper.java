package tw.nekomimi.nekogram.helpers;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ColoredImageSpan;
import static org.telegram.messenger.LocaleController.getString;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import java.util.Objects;

public class TimeStringHelper {
    public static final int[] DELETED_COLORS = {
            0xFFFF0000,
            0xFFDC2626,
            0xFFDB2777,
            0xFFC026D3,
            0xFF9333EA,
            0xFF4F46E5,
            0xFF2563EB
    };
    public static SpannableStringBuilder deletedSpan;
    public static Drawable deletedDrawable;
    private static int cachedDeletedStyle=Integer.MIN_VALUE, cachedDeletedColor=Integer.MIN_VALUE;
    public static int getDeletedIconStyle() {
        int style = com.radolyn.ayugram.AyuConfig.INSTANCE.getDeletedIconStyle().Int();
        if (style >= 0) {
            return style;
        }
        return tw.nekomimi.nekogram.NekoConfig.preferences.getBoolean("UseDeletedIcon", true) ? 1 : 0;
    }

    public static boolean isDeletedIconEnabled() {
        return getDeletedIconStyle() != 0;
    }

    public static String getDeletedStringLabel() {
        String deletedStr = com.radolyn.ayugram.AyuConfig.INSTANCE.getCustomDeletedMark().String();
        return deletedStr.isEmpty() ? getString(R.string.DeletedMessage) : deletedStr;
    }

    public static void invalidateDeletedStyle() {
        deletedDrawable = null;
        deletedSpan = null;
        cachedDeletedStyle = Integer.MIN_VALUE;
        cachedDeletedColor = Integer.MIN_VALUE;
    }

    public static Drawable getDeletedPreviewDrawable() {
        Drawable drawable = createDeletedDrawable(true);
        if (drawable != null) {
            drawable.mutate();
        }
        return drawable;
    }

    private static int getDeletedColorValue(int colorId) {
        if (colorId <= 0 || colorId > DELETED_COLORS.length) {
            return 0;
        }
        return DELETED_COLORS[colorId - 1];
    }

    private static int getDeletedDrawableTint(boolean preview, int colorId) {
        int tintColor = getDeletedColorValue(colorId);
        if (tintColor != 0) {
            return tintColor;
        }
        return preview ? Theme.getColor(Theme.key_chat_inTimeText) : 0;
    }

    private static int getDeletedDrawableResId(boolean preview) {
        return switch (getDeletedIconStyle()) {
            case 1 -> preview ? R.drawable.ayu_trash_bin_preview : R.drawable.ayu_trash_bin;
            case 2 -> preview ? R.drawable.ayu_cross_preview : R.drawable.ayu_cross;
            case 3 -> preview ? R.drawable.ayu_eye_crossed_preview : R.drawable.ayu_eye_crossed;
            default -> 0;
        };
    }

    private static Drawable createDeletedDrawable(boolean preview) {
        int resId = getDeletedDrawableResId(preview);
        if (resId == 0) {
            return null;
        }
        Drawable drawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, resId)).mutate();
        int tintColor = getDeletedDrawableTint(preview, com.radolyn.ayugram.AyuConfig.INSTANCE.getDeletedIconColor().Int());
        if (tintColor != 0) {
            drawable.setTint(tintColor);
        }
        return drawable;
    }

    private static void ensureDeletedSpan() {
        int style = getDeletedIconStyle();
        int colorId = com.radolyn.ayugram.AyuConfig.INSTANCE.getDeletedIconColor().Int();
        if (deletedSpan != null && deletedDrawable != null && cachedDeletedStyle == style && cachedDeletedColor == colorId) {
            return;
        }
        cachedDeletedStyle = style;
        cachedDeletedColor = colorId;
        deletedDrawable = createDeletedDrawable(false);
        deletedSpan = null;
        if (deletedDrawable != null) {
            deletedSpan = new SpannableStringBuilder("\u200B");
            ColoredImageSpan span = new ColoredImageSpan(deletedDrawable, ColoredImageSpan.ALIGN_CENTER);
            if (colorId > 0) {
                span.recolorDrawable = false;
            }
            if (style == 3) {
                span.setTranslateX(AndroidUtilities.dp(-1));
            }
            deletedSpan.setSpan(span, 0, 1, 0);
        }
    }


    public static CharSequence prependDeletedMark(CharSequence time) {
        ensureDeletedSpan();
        return new SpannableStringBuilder().append(isDeletedIconEnabled() && deletedSpan != null ? deletedSpan : getDeletedStringLabel()).append("  ").append(time);
    }

    public static Drawable forwardsDrawable;
    public static Drawable channelDrawable;

    public static Drawable getForwardsDrawable() {
        if (forwardsDrawable == null) {
            forwardsDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.forwards_solar)).mutate();
        }
        return forwardsDrawable;
    }

    public static Drawable getChannelDrawable() {
        if (channelDrawable == null) {
            channelDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.menu_broadcast)).mutate();
        }
        return channelDrawable;
    }
}
