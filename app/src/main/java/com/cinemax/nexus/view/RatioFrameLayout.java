package com.cinemax.nexus.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A {@link FrameLayout} whose height follows its width at a fixed ratio.
 *
 * <p>The video cards live in a grid whose column count the viewer can change, so a
 * hard-coded thumbnail height would crop every thumbnail differently depending on how many
 * columns happen to be on screen. Measuring the height from the width keeps every frame at
 * 16:9 no matter how narrow the column gets, and it costs a handful of lines instead of a
 * whole layout library the app would otherwise have to carry.
 */
public class RatioFrameLayout extends FrameLayout {

    /** Width to height. Video is 16:9, and the duration badge sits inside that corner. */
    private static final float DEFAULT_RATIO = 16f / 9f;

    private float mRatio = DEFAULT_RATIO;

    public RatioFrameLayout(@NonNull Context context) {
        super(context);
    }

    public RatioFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0 && mRatio > 0f) {
            // Padding belongs inside the ratio, otherwise a padded frame comes out short.
            int height = (int) (width / mRatio) + getPaddingTop() + getPaddingBottom();
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** Sets the width-to-height ratio, for example {@code 16f / 9f}. */
    public void setRatio(float ratio) {
        if (mRatio == ratio) return;
        mRatio = ratio;
        requestLayout();
    }
}
