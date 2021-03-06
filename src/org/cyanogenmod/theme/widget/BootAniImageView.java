/*
 * Copyright (C) 2016 Cyanogen, Inc.
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.theme.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

import java.io.IOException;
import java.util.List;
import java.util.zip.ZipFile;

import libcore.io.IoUtils;

import org.cyanogenmod.theme.util.BootAnimationHelper;

public class BootAniImageView extends ImageView {
    private static final String TAG = BootAniImageView.class.getName();

    private static final boolean DEBUG = false;

    private static final int MAX_BUFFERS = 2;

    private Bitmap[] mBuffers = new Bitmap[MAX_BUFFERS];
    private int mReadBufferIndex = 0;
    private int mWriteBufferIndex = 0;
    private ZipFile mBootAniZip;

    private List<BootAnimationHelper.AnimationPart> mAnimationParts;
    private int mCurrentPart;
    private int mCurrentFrame;
    private int mCurrentPartPlayCount;
    private int mFrameDuration;

    private boolean mActive = false;

    public BootAniImageView(Context context) {
        this(context, null);
    }

    public BootAniImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BootAniImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            if (mBootAniZip != null) start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // In case we end up in the mid dle of onDraw while the buffers are being recycled
        // we catch the exception and just let frame not be rendered.
        try {
            super.onDraw(canvas);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to draw boot animation frame.");
        }
    }

    public synchronized boolean setBootAnimation(ZipFile bootAni) {
        if (bootAni == null) {
            Log.w(TAG, "Boot animation ZipFile is null.");
            return false;
        }
        // make sure we are stopped first
        stop();

        if (mBootAniZip != null) {
            IoUtils.closeQuietly(mBootAniZip);
            // This boot animation may be a different size than the previous
            // one so clear out the buffers so they can be recreated with
            // the correct size.
            for (int i = 0; i < MAX_BUFFERS; i++) {
                if (mBuffers[i] != null) {
                    mBuffers[i].recycle();
                    mBuffers[i] = null;
                }
            }
        }
        mBootAniZip = bootAni;

        try {
            mAnimationParts = BootAnimationHelper.parseAnimation(mBootAniZip);
        } catch (Exception e) {
            // "Gotta catch 'em all"
            Log.e(TAG, "Unable to set boot animation", e);
            mAnimationParts = null;
            mBootAniZip = null;
            return false;
        }

        if (mAnimationParts == null || mAnimationParts.size() == 0) {
            return false;
        }

        final BootAnimationHelper.AnimationPart part = mAnimationParts.get(0);
        mCurrentPart = 0;
        mCurrentPartPlayCount = part.playCount;
        mFrameDuration = part.frameRateMillis;
        mWriteBufferIndex = mReadBufferIndex = 0;
        mCurrentFrame = 0;

        getNextFrame();

        return true;
    }

    private void getNextFrame() {
        if (mAnimationParts == null || mAnimationParts.size() == 0) return;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inBitmap = mBuffers[mWriteBufferIndex];
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        opts.inMutable = true;
        final BootAnimationHelper.AnimationPart part = mAnimationParts.get(mCurrentPart);
        try {
            mBuffers[mWriteBufferIndex] =
                    BitmapFactory.decodeStream(mBootAniZip.getInputStream(mBootAniZip.getEntry(
                            part.frames.get(mCurrentFrame++))), null, opts);
        } catch (IllegalArgumentException iae) {
            // In case we're here because the bitmap could not be re-used, try creating a new one
            opts.inBitmap = null;
            try {
                if (DEBUG) {
                    Log.d(TAG, "Trying to load frame without reusing existing bitmap", iae);
                }
                if (mBuffers[mWriteBufferIndex] != null) {
                    // clean up our old bitmap
                    mBuffers[mWriteBufferIndex].recycle();
                    mBuffers[mWriteBufferIndex] = null;
                }
                mBuffers[mWriteBufferIndex] =
                        BitmapFactory.decodeStream(mBootAniZip.getInputStream(mBootAniZip.getEntry(
                                part.frames.get(mCurrentFrame++))), null, opts);
            } catch (Exception e) {
                // Still failling?  Let's log it and carry on.
                Log.w(TAG, "Unable to get next frame", e);
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to get next frame", e);
        }
        mWriteBufferIndex = (mWriteBufferIndex + 1) % MAX_BUFFERS;
        if (mCurrentFrame >= part.frames.size()) {
            if (mCurrentPartPlayCount > 0) {
                if (--mCurrentPartPlayCount == 0) {
                    mCurrentPart++;
                    if (mCurrentPart >= mAnimationParts.size()) mCurrentPart = 0;
                    mCurrentFrame = 0;
                    mCurrentPartPlayCount = mAnimationParts.get(mCurrentPart).playCount;
                } else {
                    mCurrentFrame = 0;
                }
            } else {
                mCurrentFrame = 0;
            }
        }
    }

    public void start() {
        if (mAnimationParts == null) return;

        mActive = true;
        post(mUpdateAnimationRunnable);
    }

    public void stop() {
        mActive = false;
        removeCallbacks(mUpdateImageRunnable);
        removeCallbacks(mUpdateAnimationRunnable);
    }

    private Runnable mUpdateAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mActive) return;
            BootAniImageView.this.postDelayed(mUpdateAnimationRunnable, mFrameDuration);
            if (!isOffScreen()) {
                BootAniImageView.this.post(mUpdateImageRunnable);
                mReadBufferIndex = (mReadBufferIndex + 1) % MAX_BUFFERS;
                getNextFrame();
            }
        }
    };

    private Runnable mUpdateImageRunnable = new Runnable() {
        @Override
        public void run() {
            setImageBitmap(mBuffers[mReadBufferIndex]);
        }
    };

    private boolean isOffScreen() {
        int[] pos = new int[2];
        getLocationOnScreen(pos);
        return pos[1] >= mContext.getResources().getDisplayMetrics().heightPixels;
    }
}
