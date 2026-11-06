package com.cinemax.nexus.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;

import com.cinemax.nexus.R;

/**
 * The player's timeline: a slim buffered gradient track with a generous touch area.
 *
 * <p>Everything is drawn here with plain {@link Paint}s - a rounded track,
 * the buffered window behind the played portion, saved study and A-B loop markers, and a round
 * thumb that stays fully visible at both ends (the drawable region is inset by the thumb
 * radius, the same convention the platform SeekBar uses). No drawables, no subviews, no
 * dependencies; dragging it only touches this view's canvas.
 *
 * <p>The platform still handles touch, keyboard and accessibility input; drawing the
 * timeline here keeps its appearance consistent across Android versions.
 */
public class BufferedSeekBar extends AppCompatSeekBar {

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBufferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float gradientLeft = Float.NaN, gradientRight = Float.NaN;
    private long[] mChapters = new long[0];

    private long mBufferedMs = 0L;
    private long mDurationMs = 0L;

    private long mLoopAMs = C_UNSET;
    private long mLoopBMs = C_UNSET;

    /** Sentinel meaning "no marker". */
    public static final long C_UNSET = Long.MIN_VALUE;

    public BufferedSeekBar(@NonNull Context context) {
        this(context, null);
    }

    public BufferedSeekBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BufferedSeekBar(@NonNull Context context, @Nullable AttributeSet attrs,
                          int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int accent = ContextCompat.getColor(context, R.color.nexus_accent);
        mTrackPaint.setColor(0x30FFFFFF);
        mBufferPaint.setColor(0x70FFFFFF);
        mProgressPaint.setColor(accent);       // played portion - the solid fill
        mThumbPaint.setColor(0xFFFFFFFF);      // knob, white so it pops on the scrim
        mMarkerPaint.setColor(0xFFFFFFFF);     // A-B flags stay readable over the fill
        mMarkerPaint.setStrokeWidth(dp(2f));
        mMarkerPaint.setStyle(Paint.Style.STROKE);
        mMarkerPaint.setStrokeCap(Paint.Cap.ROUND);
        mHaloPaint.setColor(0x3573E3CB);
        setSplitTrack(false);
    }

    public void setChapters(long[] positions) {
        mChapters = positions == null ? new long[0] : positions.clone();
        invalidate();
    }

    public void setBuffered(long bufferedMs, long durationMs) {
        mBufferedMs = Math.max(0, bufferedMs);
        mDurationMs = Math.max(0, durationMs);
        invalidate();
    }

    public void setLoopMarkers(long aMs, long bMs) {
        mLoopAMs = aMs;
        mLoopBMs = bMs;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(@NonNull Canvas canvas) {
        float midY = getHeight() / 2f;
        float half = trackHeight() / 2f;
        float inset = thumbRadius();
        float left = getPaddingLeft() + inset;
        float right = getWidth() - getPaddingRight() - inset;
        float span = right - left;
        if (span <= 0) return;

        float progress = getMax() > 0 ? clamp(getProgress() / (float) getMax()) : 0f;

        // 1. remaining timeline
        mRect.set(left, midY - half, right, midY + half);
        canvas.drawRoundRect(mRect, half, half, mTrackPaint);

        // 2. buffered window
        if (mDurationMs > 0 && mBufferedMs > 0) {
            float f = Math.min(1f, mBufferedMs / (float) mDurationMs);
            mRect.set(left, midY - half, left + span * f, midY + half);
            canvas.drawRoundRect(mRect, half, half, mBufferPaint);
        }

        // 3. played portion - the solid bar itself
        if (progress > 0f) {
            if (left != gradientLeft || right != gradientRight) updateGradient(left, right);
            mRect.set(left, midY - half, left + span * progress, midY + half);
            canvas.drawRoundRect(mRect, half, half, mProgressPaint);
        }

        drawMarkers(canvas, left, span, midY, half);
        if (mDurationMs > 0) {
            for (long chapter : mChapters) {
                if (chapter < 0 || chapter > mDurationMs) continue;
                canvas.drawCircle(left + span * chapter / mDurationMs, midY, dp(1.6f), mThumbPaint);
            }
        }

        // 5. thumb - slightly larger while pressed so the drag feels tactile
        float cx = left + span * progress;
        float r = isPressed() ? dp(7f) : dp(4.5f);
        if (isPressed() || isFocused()) canvas.drawCircle(cx, midY, dp(13f), mHaloPaint);
        canvas.drawCircle(cx, midY, r, mThumbPaint);
    }

    private void drawMarkers(@NonNull Canvas canvas, float left, float span,
                             float midY, float half) {
        if (mDurationMs <= 0) return;
        if (mLoopAMs != C_UNSET) {
            float x = left + span * clamp(mLoopAMs / (float) mDurationMs);
            canvas.drawLine(x, midY - half - dp(4), x, midY + half + dp(4), mMarkerPaint);
        }
        if (mLoopBMs != C_UNSET) {
            float x = left + span * clamp(mLoopBMs / (float) mDurationMs);
            canvas.drawLine(x, midY - half - dp(4), x, midY + half + dp(4), mMarkerPaint);
        }
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private void updateGradient(float left, float right) {
        gradientLeft = left; gradientRight = right;
        mProgressPaint.setShader(new LinearGradient(left, 0, right, 0,
                0xFF31BDAA, 0xFF97F3DB, Shader.TileMode.CLAMP));
    }

    /** Solid track thickness - reads as a bar, not a hairline, but stays light. */
    private float trackHeight() {
        return dp(isPressed() ? 4f : 2.5f);
    }

    private float thumbRadius() {
        return dp(8f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
