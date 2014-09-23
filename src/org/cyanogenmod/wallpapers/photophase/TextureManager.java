/*
 * Copyright (C) 2014 The CyanogenMod Project
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

package org.cyanogenmod.wallpapers.photophase;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ThumbnailUtils;
import android.media.effect.Effect;
import android.media.effect.EffectContext;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import org.cyanogenmod.wallpapers.photophase.FixedQueue.EmptyQueueException;
import org.cyanogenmod.wallpapers.photophase.preferences.PreferencesProvider.Preferences;
import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil;
import org.cyanogenmod.wallpapers.photophase.utils.Utils;
import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil.GLESTextureInfo;
import org.cyanogenmod.wallpapers.photophase.MediaPictureDiscoverer.OnMediaPictureDiscoveredListener;
import org.cyanogenmod.wallpapers.photophase.effects.Effects;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A class that manages the acquisition of new textures.
 */
public class TextureManager implements OnMediaPictureDiscoveredListener {

    private static final String TAG = "TextureManager";

    private static final int QUEUE_SIZE = 8;

    final Context mContext;
    final Handler mHandler;
    Effects mEffects;
    final Object mSync;
    final List<TextureRequestor> mPendingRequests;
    final FixedQueue<GLESTextureInfo> mQueue = new FixedQueue<GLESTextureInfo>(QUEUE_SIZE);
    BackgroundPictureLoaderThread mBackgroundTask;
    /*protected*/ final MediaPictureDiscoverer mPictureDiscoverer;

    Rect mScreenDimensions;
    Rect mDimensions;

    final GLESSurfaceDispatcher mDispatcher;

    // The status of the texture manager:
    // 0 - Loading
    // 1 - Loaded
    // 2 - Error
    private byte mStatus;

    /**
     * A private runnable that will run in the GLThread
     */
    class PictureDispatcher implements Runnable {
        File mImage;
        GLESTextureInfo ti = null;
        final Object mWait = new Object();

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            try {
                Effect effect = null;
                synchronized (mEffects) {
                    effect = mEffects.getNextEffect();
                }

                boolean enqueue = false;
                synchronized (mSync) {
                    enqueue = mPendingRequests.size() == 0;
                }

                // Load and bind to the GLES context. The effect is applied when the image
                // is associated to the destination target (only if aspect ratio will be applied)
                if (!Preferences.General.isFixAspectRatio()) {
                    ti = GLESUtil.loadTexture(
                            mImage, mDimensions, effect, mDimensions, false);
                } else {
                    ti = GLESUtil.loadTexture(mImage, mDimensions, null, null, false);
                    ti.effect = effect;
                }

                synchronized (mSync) {
                    // Notify the new images to all pending frames
                    if (!enqueue) {
                        // Invalid textures are also reported, so requestor can handle it
                        TextureRequestor requestor = mPendingRequests.remove(0);
                        fixAspectRatio(requestor, ti);
                        requestor.setTextureHandle(ti);

                        // Clean up memory
                        if (ti.bitmap != null) {
                            ti.bitmap.recycle();
                            ti.bitmap = null;
                        }

                    } else {
                        // Add to the queue (only valid textures)
                        if (ti.handle > 0) {
                            mQueue.insert(ti);
                        }
                    }
                }

            } catch (Throwable e) {
                Log.e(TAG, "Something was wrong loading the texture: " +
                        mImage.getAbsolutePath(), e);

            } finally {
                // Notify that we have a new image
                synchronized (mWait) {
                    mWait.notify();
                }
            }
        }
    }

    /**
     * Constructor of <code>TextureManager</code>
     *
     * @param ctx The current context
     * @param effectCtx The current effect context
     * @param dispatcher The GLES dispatcher
     * @param requestors The number of requestors
     * @param screenDimensions The screen dimensions
     */
    public TextureManager(final Context ctx, final Handler handler, final EffectContext effectCtx,
                        GLESSurfaceDispatcher dispatcher, int requestors, Rect screenDimensions) {
        super();
        mContext = ctx;
        mHandler = handler;
        mEffects = new Effects(effectCtx);
        mDispatcher = dispatcher;
        mScreenDimensions = screenDimensions;
        mDimensions = screenDimensions; // For now, use the screen dimensions as the preferred dimensions for bitmaps
        mSync = new Object();
        mPendingRequests = new ArrayList<TextureRequestor>(requestors);
        mPictureDiscoverer = new MediaPictureDiscoverer(mContext, this);

        // Run the media discovery thread
        mBackgroundTask = new BackgroundPictureLoaderThread();
        mBackgroundTask.mTaskPaused = false;
        reloadMedia(false);
    }

    /**
     * Method that update the effect context if the EGL context change
     *
     * @param effectCtx The new effect context
     */
    protected void updateEffectContext(final EffectContext effectCtx) {
        synchronized (mEffects) {
            if (mEffects != null) {
                mEffects.release();
                mEffects = null;
            }
            mEffects = new Effects(effectCtx);
        }
        emptyTextureQueue(true);
    }

    /**
     * Method that allow to change the preferred dimensions of the bitmaps loaded
     *
     * @param dimensions The new dimensions
     */
    public void setDimensions(Rect dimensions) {
        mDimensions = dimensions;
    }

    /**
     * Method that allow to change the screen dimensions
     *
     * @param dimensions The new dimensions
     */
    public void setScreenDimesions(Rect dimensions) {
        mScreenDimensions = dimensions;
    }

    /**
     * Method that returns if the texture manager is paused
     *
     * @return boolean whether the texture manager is paused
     */
    public boolean isPaused() {
        return mBackgroundTask != null && mBackgroundTask.mTaskPaused;
    }

    /**
     * Method that pauses the internal threads
     *
     * @param pause If the thread is paused (true) or resumed (false)
     */
    public synchronized void setPause(boolean pause) {
        synchronized (mBackgroundTask.mLoadSync) {
            mBackgroundTask.mTaskPaused = pause;
            if (!mBackgroundTask.mTaskPaused) {
                mBackgroundTask.mLoadSync.notify();
            }
        }
    }

    /**
     * Method that reload the references of media pictures
     *
     * @param userRequest If the request was generated by the user
     */
    void reloadMedia(final boolean userRequest) {
        Log.d(TAG, "Reload media picture data");
        // Discovery new media
        // GLThread doesn't run in the UI thread and AsyncThread can't create a
        // valid handler in ICS (it's fixed in JB+) so we force to run the async
        // thread in a valid UI thread
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPictureDiscoverer.discover(userRequest);
            }
        });
    }

    /**
     * Method that request a new picture for the {@link TextureRequestor}
     *
     * @param requestor The requestor of the texture
     */
    public void request(TextureRequestor requestor) {
        synchronized (mSync) {
            try {
                GLESTextureInfo ti = mQueue.remove();
                fixAspectRatio(requestor, ti);
                requestor.setTextureHandle(ti);

                // Clean up memory
                if (ti.bitmap != null) {
                    ti.bitmap.recycle();
                    ti.bitmap = null;
                }

            } catch (EmptyQueueException eqex) {
                // Add to queue of pending request to be notified when
                // we have a new bitmap in the queue
                mPendingRequests.add(requestor);
            }
        }

        synchronized (mBackgroundTask.mLoadSync) {
            mBackgroundTask.mLoadSync.notify();
        }
    }

    /**
     * Method that removes all the textures from the queue
     *
     * @param reload Forces a reload of the queue
     */
    public void emptyTextureQueue(boolean reload) {
        synchronized (mSync) {
            // Recycle the textures
            try {
                List<GLESTextureInfo> all = mQueue.removeAll();
                for (GLESTextureInfo info : all) {
                    if (GLES20.glIsTexture(info.handle)) {
                        int[] textures = new int[] {info.handle};
                        if (GLESUtil.DEBUG_GL_MEMOBJS) {
                            Log.d(GLESUtil.DEBUG_GL_MEMOBJS_DEL_TAG, "glDeleteTextures: ["
                                    + info.handle + "]");
                        }
                        GLES20.glDeleteTextures(1, textures, 0);
                        GLESUtil.glesCheckError("glDeleteTextures");
                    }
                    // Return the bitmap
                    info.bitmap.recycle();
                    info.bitmap = null;
                }
            } catch (EmptyQueueException eqex) {
                // Ignore
            }

            // Remove all pictures in the queue
            try {
                mQueue.removeAll();
            } catch (EmptyQueueException ex) {
                // Ignore
            }

            // Reload the queue
            if (reload) {
                synchronized (mBackgroundTask.mLoadSync) {
                    mBackgroundTask.resetAvailableImages();
                    mBackgroundTask.mLoadSync.notify();
                }
            }
        }
    }

    /**
     * Method that cancels a request did it previously.
     *
     * @param requestor The requestor of the texture
     */
    public void cancelRequest(TextureRequestor requestor) {
        synchronized (mSync) {
            if (mPendingRequests.contains(requestor)) {
                mPendingRequests.remove(requestor);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStartMediaDiscovered(boolean userRequest) {
        // No images but thread should start here to received partial data
        this.mStatus = 0; // Loading
        if (mBackgroundTask != null) {
            mBackgroundTask.setAvailableImages(new File[]{});
            if (!mBackgroundTask.mRun) {
                mBackgroundTask.start();
            } else {
                synchronized (mBackgroundTask.mLoadSync) {
                    mBackgroundTask.mLoadSync.notify();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPartialMediaDiscovered(File[] images, boolean userRequest) {
        if (mBackgroundTask != null) {
            mBackgroundTask.setPartialAvailableImages(images);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("boxing")
    public void onEndMediaDiscovered(File[] images, boolean userRequest) {
        // Now we have the paths of the images to use. Notify to the thread to
        // load pictures in background
        if (mBackgroundTask != null) {
            mBackgroundTask.setAvailableImages(images);
            synchronized (mBackgroundTask.mLoadSync) {
                mBackgroundTask.mLoadSync.notify();
            }
            this.mStatus = 1; // Loaded

            // Audit
            int found = images == null ? 0 : images.length;
            Log.d(TAG, "Media picture data reloaded: " + found + " images found.");
            if (userRequest) {
                CharSequence msg =
                        String.format(mContext.getResources().getQuantityText(
                                R.plurals.msg_media_reload_complete, found).toString(), found);
                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
            }
        } else {
            this.mStatus = 2; // Error
        }
    }

    /**
     * Method that destroy the references of this class
     */
    public void recycle() {
        // Destroy the media discovery task
        mPictureDiscoverer.recycle();
        mEffects.release();

        // Destroy the background task
        if (mBackgroundTask != null) {
            mBackgroundTask.mRun = false;
            try {
                synchronized (mBackgroundTask.mLoadSync) {
                    mBackgroundTask.interrupt();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        mBackgroundTask = null;
    }


    /**
     * Returns the status of the texture manager
     *
     * @return byte The status
     */
    public byte getStatus() {
        return mStatus;
    }

    /**
     * Returns if the texture manager is empty
     *
     * @return boolean If the texture manager is empty
     */
    public boolean isEmpty() {
        return mBackgroundTask != null && mBackgroundTask.mEmpty;
    }

    /**
     * Method that fix the aspect ratio of a image to fit the destination target
     *
     * @param request The requestor target
     * @param ti The original texture information
     * @param effect The effect to apply to the destination picture
     */
    void fixAspectRatio(TextureRequestor requestor, GLESTextureInfo ti) {
        // Check if we have to apply any correction to the image
        if (Preferences.General.isFixAspectRatio()) {
            // Transform requestor dimensions to screen dimensions
            RectF dimens = requestor.getRequestorDimensions();
            Rect pixels = new Rect(
                                0,
                                0,
                                (int)(mScreenDimensions.width() * dimens.width() / 2),
                                (int)(mScreenDimensions.height() * dimens.height() / 2));

            // Create a thumbnail of the image
            Bitmap thumb = ThumbnailUtils.extractThumbnail(
                                    ti.bitmap,
                                    pixels.width(),
                                    pixels.height(),
                                    ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
            GLESTextureInfo dst = GLESUtil.loadTexture(thumb, ti.effect, pixels);

            // Destroy references
            int[] textures = new int[]{ti.handle};
            if (GLESUtil.DEBUG_GL_MEMOBJS) {
                Log.d(GLESUtil.DEBUG_GL_MEMOBJS_DEL_TAG, "glDeleteTextures: ["
                        + ti.handle + "]");
            }
            GLES20.glDeleteTextures(1, textures, 0);
            GLESUtil.glesCheckError("glDeleteTextures");
            if (ti.bitmap != null) {
                ti.bitmap.recycle();
                ti.bitmap = null;
            }

            // Swap references
            ti.bitmap = dst.bitmap;
            ti.handle = dst.handle;
            ti.effect = null;
        }
    }

    /**
     * An internal thread to load pictures in background
     */
    private class BackgroundPictureLoaderThread extends Thread {

        final Object mLoadSync = new Object();
        boolean mRun;
        boolean mTaskPaused;

        boolean mEmpty;
        private final List<File> mNewImages;
        private final List<File> mUsedImages;

        /**
         * Constructor of <code>BackgroundPictureLoaderThread</code>.
         */
        public BackgroundPictureLoaderThread() {
            super();
            mNewImages = new ArrayList<File>();
            mUsedImages = new ArrayList<File>();
        }

        /**
         * Method that sets the current available images.
         *
         * @param images The current images
         */
        public void setAvailableImages(File[] images) {
            synchronized (mLoadSync) {
                mNewImages.clear();
                mNewImages.addAll(Arrays.asList(images));

                // Retain used images
                int count = mUsedImages.size() - 1;
                for (int i = count; i >= 0; i--) {
                    File image = mUsedImages.get(i);
                    if (!mNewImages.contains(image)) {
                        mUsedImages.remove(image);
                    } else {
                        mNewImages.remove(image);
                    }
                }

                mEmpty = images.length == 0;
            }
        }

        /**
         * Method that adds some available images.
         *
         * @param images The current images
         */
        public void setPartialAvailableImages(File[] images) {
            synchronized (mLoadSync) {
                mNewImages.addAll(Arrays.asList(images));
                mEmpty = images.length == 0;
            }
        }

        /**
         * Method that reset the current available images queue.
         */
        public void resetAvailableImages() {
            synchronized (mLoadSync) {
                mNewImages.addAll(mUsedImages);
                mUsedImages.clear();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            mRun = true;
            while (mRun) {
                // Check if we need to load more images
                while (!mTaskPaused && TextureManager.this.mQueue.items() < TextureManager.this.mQueue.size()) {
                    File image = null;
                    synchronized (mLoadSync) {
                        // Swap arrays if needed
                        if (mNewImages.size() == 0) {
                            mNewImages.addAll(mUsedImages);
                            mUsedImages.clear();
                        }
                        if (mNewImages.size() == 0) {
                            if (!mEmpty) {
                                reloadMedia(false);
                            }
                            break;
                        }

                        // Extract a random image
                        int low = 0;
                        int high = mNewImages.size() - 1;
                        image = mNewImages.remove(Utils.getNextRandom(low, high));
                    }

                    // Run commands in the GLThread
                    if (!mRun) break;
                    PictureDispatcher pd = new PictureDispatcher();
                    pd.mImage = image;
                    mDispatcher.dispatch(pd);

                    // Wait until the texture is loaded
                    try {
                        synchronized (pd.mWait) {
                            pd.mWait.wait();
                        }
                    } catch (Exception e) {
                        // Ignore
                    }

                    // Add to used images
                    mUsedImages.add(image);
                }

                // Wait for new request
                synchronized (mLoadSync) {
                    try {
                        mLoadSync.wait();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }

    }
}
