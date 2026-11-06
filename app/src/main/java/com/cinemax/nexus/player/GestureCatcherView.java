package com.cinemax.nexus.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Invisible surface that owns every player gesture, in the same spirit as MX Player:
 *
 * <ul>
 *   <li>Single tap — show/hide the controls</li>
 *   <li>Double tap left/right — rewind / fast-forward by one seek step</li>
 *   <li>Double tap centre — play / pause</li>
 *   <li>Press and hold — fast forward while held</li>
 *   <li>Vertical swipe on the left half — brightness</li>
 *   <li>Vertical swipe on the right half — volume</li>
 *   <li>Horizontal swipe — scrub</li>
 *   <li>Pinch — zoom</li>
 * </ul>
 *
 * Raw drag math is done here so {@link PlayerActivity} only deals in meaningful
 * deltas; the view itself stays free of player knowledge.
 */
public class GestureCatcherView extends View {

    /** Ratio of screen height to one full brightness/volume sweep. */
    private static final float VERTICAL_SENSITIVITY = 1.1f;

    public interface Host {
        void onToggleControls();

        /** side is the tap x fraction, 0f = left edge, 1f = right edge. */
        void onDoubleTap(float xFraction);

        void onLongPressStart();

        void onLongPressEnd();

        void onDragStart();

        /** fractionDelta > 0 means the finger moved down. */
        void onVerticalDrag(boolean leftHalf, float fractionDelta);

        void onSeekDragStart();

        /** Positive fractionDelta means the finger moved right (seek forward). */
        void onSeekDrag(float fractionDelta, boolean commit);

        void onPinch(float scaleFactor);

        void onGestureEnd();
    }

    private final GestureDetector mTapDetector;
    private final ScaleGestureDetector mScaleDetector;

    @Nullable private Host mHost;

    private boolean mEnabled = true;
    private boolean mLongPressing;

    /** Which drag is currently in progress, if any. */
    private int mDragMode = DRAG_NONE;
    private static final int DRAG_NONE = 0;
    private static final int DRAG_SEEK = 1;
    private static final int DRAG_VERTICAL = 2;

    private final int mTouchSlop;

    public GestureCatcherView(@NonNull Context context) {
        this(context, null);
    }

    public GestureCatcherView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GestureCatcherView(@NonNull Context context, @Nullable AttributeSet attrs,
                              int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mTapDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        if (mHost != null) mHost.onToggleControls();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (mHost != null) {
                            float frac = getWidth() > 0 ? e.getX() / getWidth() : 0.5f;
                            mHost.onDoubleTap(frac);
                        }
                        return true;
                    }

                    @Override
                    public void onLongPress(@NonNull MotionEvent e) {
                        if (mHost != null && mDragMode == DRAG_NONE) {
                            mLongPressing = true;
                            mHost.onLongPressStart();
                        }
                    }

                    @Override
                    public boolean onScroll(@NonNull MotionEvent down,
                                            @NonNull MotionEvent e,
                                            float distanceX, float distanceY) {
                        if (mHost == null) return false;
                        if (mLongPressing) return false;

                        if (mDragMode == DRAG_NONE) {
                            float dx = Math.abs(down.getX() - e.getX());
                            float dy = Math.abs(down.getY() - e.getY());
                            if (dx < mTouchSlop && dy < mTouchSlop) return false;
                            if (dx >= dy) {
                                mDragMode = DRAG_SEEK;
                                mHost.onSeekDragStart();
                            } else {
                                mDragMode = DRAG_VERTICAL;
                                mHost.onDragStart();
                                mHost.onVerticalDrag(down.getX() < getWidth() / 2f, 0f);
                            }
                        }

                        if (getWidth() <= 0 || getHeight() <= 0) return false;
                        // GestureDetector reports distance since the previous event.
                        if (mDragMode == DRAG_SEEK) {
                            mHost.onSeekDrag(-distanceX / getWidth(), false);
                        } else {
                            boolean leftHalf = down.getX() < getWidth() / 2f;
                            mHost.onVerticalDrag(leftHalf,
                                    distanceY / getHeight() * VERTICAL_SENSITIVITY);
                        }
                        return true;
                    }
                });

        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        if (mHost != null) mHost.onPinch(detector.getScaleFactor());
                        return true;
                    }

                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        mDragMode = DRAG_NONE;
                        return mHost != null;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                        if (mHost != null) mHost.onGestureEnd();
                    }
                });
    }

    public void setHost(@Nullable Host host) {
        mHost = host;
    }

    /** Disables gesture handling (used while the screen is locked). */
    public void setGesturesEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!mEnabled) return false;

        mScaleDetector.onTouchEvent(event);
        mTapDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragMode = DRAG_NONE;
                mLongPressing = false;
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mLongPressing && mHost != null) mHost.onLongPressEnd();
                if (mHost != null) {
                    if (mDragMode == DRAG_SEEK) {
                        mHost.onSeekDrag(0f, true);
                    }
                    if (mDragMode != DRAG_NONE || mLongPressing) mHost.onGestureEnd();
                }
                mDragMode = DRAG_NONE;
                mLongPressing = false;
                break;

            default:
                break;
        }
        // Always consume so a lone tap is never interpreted as a tap-through.
        return true;
    }
}
