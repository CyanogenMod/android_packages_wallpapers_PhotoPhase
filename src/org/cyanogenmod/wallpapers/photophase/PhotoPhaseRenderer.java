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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources.NotFoundException;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.effect.EffectContext;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.util.Log;

import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil;
import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil.GLColor;
import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil.GLESTextureInfo;
import org.cyanogenmod.wallpapers.photophase.utils.Utils;
import org.cyanogenmod.wallpapers.photophase.preferences.PreferencesProvider;
import org.cyanogenmod.wallpapers.photophase.preferences.PreferencesProvider.Preferences;
import org.cyanogenmod.wallpapers.photophase.preferences.TouchAction;
import org.cyanogenmod.wallpapers.photophase.shapes.ColorShape;
import org.cyanogenmod.wallpapers.photophase.shapes.OopsShape;
import org.cyanogenmod.wallpapers.photophase.transitions.Transition;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The EGL renderer of PhotoPhase Live Wallpaper.
 */
public class PhotoPhaseRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "PhotoPhaseRenderer";

    private static final boolean DEBUG = false;

    private final long mInstance;
    private static long sInstances;

    private final boolean mIsPreview;
    boolean mIsPaused;
    boolean mRecreateWorld;

    final Context mContext;
    EffectContext mEffectContext;
    private final Handler mHandler;
    final GLESSurfaceDispatcher mDispatcher;
    TextureManager mTextureManager;

    final AlarmManager mAlarmManager;
    PendingIntent mRecreateDispositionPendingIntent;

    PhotoPhaseWallpaperWorld mWorld;
    ColorShape mOverlay;
    OopsShape mOopsShape;

    long mLastRunningTransition;
    long mLastTransition;

    private long mLastTouchTime;
    private static final long TOUCH_BARRIER_TIME = 1000L;

    int mWidth = -1;
    int mHeight = -1;
    private int mStatusBarHeight = 0;
    int mMeasuredHeight  = -1;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjMatrix = new float[16];
    private final float[] mVMatrix = new float[16];

    final Object mDrawing = new Object();

    final Object mMediaSync = new Object();
    private PendingIntent mMediaScanIntent;

    private final BroadcastReceiver mSettingsChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check what flags are been requested
            boolean recreateWorld = intent.getBooleanExtra(
                    PreferencesProvider.EXTRA_FLAG_RECREATE_WORLD, false);
            boolean redraw = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_REDRAW, false);
            boolean emptyTextureQueue = intent.getBooleanExtra(
                    PreferencesProvider.EXTRA_FLAG_EMPTY_TEXTURE_QUEUE, false);
            boolean mediaReload = intent.getBooleanExtra(
                    PreferencesProvider.EXTRA_FLAG_MEDIA_RELOAD, false);
            boolean mediaIntervalChanged = intent.getBooleanExtra(
                    PreferencesProvider.EXTRA_FLAG_MEDIA_INTERVAL_CHANGED, false);
            int dispositionInterval = intent.getIntExtra(
                    PreferencesProvider.EXTRA_FLAG_DISPOSITION_INTERVAL_CHANGED, -1);

            // Empty texture queue?
            if (emptyTextureQueue) {
                if (mTextureManager != null) {
                    mTextureManager.emptyTextureQueue(true);
                }
            }

            // Media reload. Purging resources and performs a media query
            if (mediaReload) {
                synchronized (mMediaSync) {
                    if (mTextureManager != null) {
                        boolean userReloadRequest = intent.getBooleanExtra(
                                PreferencesProvider.EXTRA_ACTION_MEDIA_USER_RELOAD_REQUEST, false);
                        mTextureManager.reloadMedia(userReloadRequest);
                        scheduleOrCancelMediaScan();
                    }
                }
            }

            // Media scan interval was changed. Reschedule
            if (mediaIntervalChanged) {
                scheduleOrCancelMediaScan();
            }

            // Media scan interval was changed. Reschedule
            if (dispositionInterval != -1) {
                scheduleDispositionRecreation();
            }

            // Recreate the whole world?
            if (recreateWorld && mWorld != null) {
                recreateWorld();
            }

            // Performs a redraw?
            if (redraw) {
                forceRedraw();
            }
        }
    };

    private final Runnable mTransitionThread = new Runnable() {
        @Override
        public void run() {
            // Run in GLES's thread
            mDispatcher.dispatch(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!mIsPaused) {
                            // Select a new transition
                            mWorld.selectRandomTransition();
                            mLastRunningTransition = System.currentTimeMillis();
                            mLastTransition = System.currentTimeMillis();

                            // Now force continuously render while transition is applied
                            mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                        }
                    } catch (Throwable ex) {
                        Log.e(TAG, "Something was wrong selecting the transition", ex);
                    }
                }
            });
        }
    };

    private final Runnable mEGLContextWatchDog = new Runnable() {
        @Override
        public void run() {
            // Restart the wallpaper
            AndroidHelper.restartWallpaper(mContext);
        }
    };

    /**
     * Constructor of <code>PhotoPhaseRenderer<code>
     *
     * @param ctx The current context
     * @param dispatcher The GLES dispatcher
     * @param isPreview Indicates if the renderer is in preview mode
     */
    public PhotoPhaseRenderer(Context ctx, GLESSurfaceDispatcher dispatcher, boolean isPreview) {
        super();
        mContext = ctx;
        mHandler = new Handler();
        mDispatcher = dispatcher;
        mInstance = sInstances;
        mIsPreview = isPreview;
        mIsPaused = true;
        mRecreateWorld = false;
        sInstances++;
        mAlarmManager = (AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (mInstance ^ (mInstance >>> 32));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PhotoPhaseRenderer other = (PhotoPhaseRenderer) obj;
        if (mInstance != other.mInstance)
            return false;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "PhotoPhaseRenderer [instance: " + mInstance + "]";
    }

    /**
     * Method called when renderer is created
     */
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate [" + mInstance + "]");
        // Register a receiver to listen for media reload request
        IntentFilter filter = new IntentFilter();
        filter.addAction(PreferencesProvider.ACTION_SETTINGS_CHANGED);
        mContext.registerReceiver(mSettingsChangedReceiver, filter);

        // Check whether the media scan is active
        int interval = Preferences.Media.getRefreshFrecuency();
        if (interval != Preferences.Media.MEDIA_RELOAD_DISABLED) {
            // Schedule a media scan
            scheduleMediaScan(interval);
        }
    }

    /**
     * Method called when renderer when GL context is destroyed
     */
    public void onGLContextDestroy() {
        if (DEBUG) Log.d(TAG, "onGLContextDestroy [" + mInstance + "]");
        recycle();
        if (mEffectContext != null) {
            mEffectContext.release();
        }
        mEffectContext = null;
        mWidth = -1;
        mHeight = -1;
        mMeasuredHeight = -1;
    }

    /**
     * Method called when renderer is destroyed
     */
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy [" + mInstance + "]");
        // Register a receiver to listen for media reload request
        mContext.unregisterReceiver(mSettingsChangedReceiver);
    }

    /**
     * Method called when system runs under low memory
     */
    public void onLowMemory() {
        if (mTextureManager != null) {
            mTextureManager.emptyTextureQueue(false);
        }
    }

    /**
     * Method called when the renderer should be paused
     */
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause [" + mInstance + "]");
        mIsPaused = true;
        mHandler.removeCallbacks(mTransitionThread);
        if (mTextureManager != null) {
            mTextureManager.setPause(true);
        }
    }

    /**
     * Method called when the renderer should be resumed
     */
    public void onResume() {
        if (DEBUG) Log.d(TAG, "onResume [" + mInstance + "]");
        if (mTextureManager != null) {
            mTextureManager.setPause(false);
        }
        mIsPaused = false;
        if (mRecreateWorld) {
            recreateWorld();
        } else {
            mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        }

        // Set a watchdog to detect EGL bad context and restart the wallpaper
        if (!mIsPreview) {
            mHandler.postDelayed(mEGLContextWatchDog, 15000L);
        }
    }

    /**
     * Method called when the renderer should process a touch event over the screen
     *
     * @param x The x coordinate
     * @param y The y coordinate
     */
    public void onTouch(float x , float y) {
        if (mWorld != null) {
            // Do user action
            TouchAction touchAction = Preferences.General.getTouchAction();
            if (touchAction.compareTo(TouchAction.NONE) == 0) {
                //Ignore
            } else {
                // Avoid to handle multiple touchs
                long touchTime = System.currentTimeMillis();
                long diff = touchTime - mLastTouchTime;
                mLastTouchTime = touchTime;
                if (diff < TOUCH_BARRIER_TIME) {
                    return;
                }

                // Retrieve the photo frame for its coordinates
                final PhotoFrame frame = mWorld.getFrameFromCoordinates(new PointF(x, y));
                if (frame == null) {
                    Log.w(TAG, "No frame from coordenates");
                    return;
                }

                // Apply the action
                if (touchAction.compareTo(TouchAction.TRANSITION) == 0) {
                    try {
                        // Select the frame with a transition
                        // Run in GLES's thread
                        mDispatcher.dispatch(new Runnable() {
                            @Override
                            public void run() {
                                // Select a new transition
                                deselectCurrentTransition();
                                mWorld.selectTransition(frame);
                                mLastRunningTransition = System.currentTimeMillis();

                                // Now force continuously render while transition is applied
                                mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                            }
                        });

                    } catch (NotFoundException ex) {
                        Log.e(TAG, "The frame not exists " + frame.getTextureInfo().path, ex);
                    }

                } else if (touchAction.compareTo(TouchAction.OPEN) == 0) {
                    // Open the image
                    try {
                        Uri uri = getUriFromFrame(frame);
                        if (uri != null) {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            intent.setDataAndType(uri, "image/*");
                            mContext.startActivity(intent);
                        }
                    } catch (ActivityNotFoundException ex) {
                        Log.e(TAG, "Open action not found for " + frame.getTextureInfo().path, ex);
                    }

                } else if (touchAction.compareTo(TouchAction.SHARE) == 0) {
                    // Send the image
                    try {
                        Uri uri = getUriFromFrame(frame);
                        if (uri != null) {
                            Intent intent = new Intent(Intent.ACTION_SEND);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            intent.setType("image/*");
                            intent.putExtra(Intent.EXTRA_STREAM, uri);
                            mContext.startActivity(intent);
                        }
                    } catch (ActivityNotFoundException ex) {
                        Log.e(TAG, "Send action not found for " + frame.getTextureInfo().path, ex);
                    }
                }
            }
        }
    }

    /**
     * Method that returns an Uri reference from a photo frame
     *
     * @param frame The photo frame
     * @return Uri The image uri
     */
    private static Uri getUriFromFrame(final PhotoFrame frame) {
        // Sanity checks
        GLESTextureInfo info = frame.getTextureInfo();
        if (info == null) {
            Log.e(TAG, "The frame has not a valid reference right now." +
                    "Touch action is not available.");
            return null;
        }
        if (info.path == null || !info.path.isFile()) {
            Log.e(TAG, "The image do not exists. Touch action is not available.");
            return null;
        }

        // Return the uri from the path
        return Uri.fromFile(frame.getTextureInfo().path);
    }

    /**
     * Method that deselect the current transition
     */
    synchronized void deselectCurrentTransition() {
        mHandler.removeCallbacks(mTransitionThread);
        mWorld.deselectTransition(mMVPMatrix);
        mLastRunningTransition = 0;
    }

    void scheduleOrCancelMediaScan() {
        // Ignored in preview mode
        if (mIsPreview) {
            return;
        }

        int interval = Preferences.Media.getRefreshFrecuency();
        if (interval != Preferences.Media.MEDIA_RELOAD_DISABLED) {
            scheduleMediaScan(interval);
        } else {
            cancelMediaScan();
        }
    }

    /**
     * Method that schedules a new media scan
     *
     * @param interval The new interval
     */
    private void scheduleMediaScan(int interval) {
        // Ignored in preview mode
        if (mIsPreview) {
            return;
        }

        Intent i = new Intent(PreferencesProvider.ACTION_SETTINGS_CHANGED);
        i.putExtra(PreferencesProvider.EXTRA_FLAG_MEDIA_RELOAD, Boolean.TRUE);
        mMediaScanIntent = PendingIntent.getBroadcast(
                mContext, 0, i, PendingIntent.FLAG_CANCEL_CURRENT);

        long milliseconds = Preferences.Media.getRefreshFrecuency() * 1000L;
        long nextTime = System.currentTimeMillis() + milliseconds;
        mAlarmManager.set(AlarmManager.RTC, nextTime, mMediaScanIntent);
    }

    /**
     * Method that cancels a pending media scan
     */
    private void cancelMediaScan() {
        if (mMediaScanIntent != null) {
            mAlarmManager.cancel(mMediaScanIntent);
            mMediaScanIntent = null;
        }
    }

    /**
     * Method that schedule a new recreation of the current disposition
     */
    void scheduleDispositionRecreation() {
        // Ignored in preview mode
        if (mIsPreview) {
            return;
        }

        // Cancel current alarm
        cancelDispositionRecreation();

        // Is random disposition enabled?
        if (!Preferences.Layout.isRandomDispositions()) {
            return;
        }

        // Schedule the next recreation if interval has been configured
        int interval = Preferences.Layout.getRandomDispositionsInterval();
        if (interval > 0) {
            // Created the intent
            Intent intent = new Intent(PreferencesProvider.ACTION_SETTINGS_CHANGED);
            intent.putExtra(PreferencesProvider.EXTRA_FLAG_RECREATE_WORLD, Boolean.TRUE);
            mRecreateDispositionPendingIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);

            // Schedule the pending intent
            long nextTime = System.currentTimeMillis() + interval;
            mAlarmManager.set(AlarmManager.RTC, nextTime, mRecreateDispositionPendingIntent);
        }
    }

    /**
     * Method that cancels a pending media scan
     */
    private void cancelDispositionRecreation() {
        // Cancel current alarm
        if (mRecreateDispositionPendingIntent != null) {
            mAlarmManager.cancel(mRecreateDispositionPendingIntent);
        }
    }

    /**
     * Recreate the world
     */
    void recreateWorld() {
        if (mIsPaused) {
            mRecreateWorld = true;
            return;
        }
        mRecreateWorld = false;

        // Recreate the wallpaper world (under a GLES context)
        mDispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (mDrawing) {
                        mLastRunningTransition = 0;
                        mWorld.recreateWorld(mWidth, mMeasuredHeight);
                    }
                } catch (GLException e) {
                    Log.e(TAG, "Cannot recreate the wallpaper world.", e);
                } finally {
                    mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                }
                scheduleDispositionRecreation();
            }
        });
    }

    /**
     * Force a redraw of the screen
     */
    void forceRedraw() {
        if (!mIsPaused) {
            mDispatcher.requestRender();
        }
    }

    /**
     * Method that destroy all the internal references
     */
    private void recycle() {
        if (DEBUG) Log.d(TAG, "recycle [" + mInstance + "]");
        synchronized (mDrawing) {
            // Remove any pending handle
            if (mHandler != null && mTransitionThread != null) {
                mHandler.removeCallbacks(mTransitionThread);
            }

            // Delete the world
            if (mWorld != null) mWorld.recycle(true);
            if (mTextureManager != null) mTextureManager.recycle();
            if (mOverlay != null) mOverlay.recycle();
            if (mOopsShape != null) mOopsShape.recycle();
            mWorld = null;
            mTextureManager = null;
            mOverlay = null;
            mOopsShape = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        if (DEBUG) Log.d(TAG, "onSurfaceCreated [" + mInstance + "]");

        mWidth = -1;
        mHeight = -1;
        mMeasuredHeight = -1;
        mStatusBarHeight = 0;

        mLastTransition = System.currentTimeMillis();

        // We have a 2d (fake) scenario, disable all unnecessary tests. Deep are
        // necessary for some 3d effects
        GLES20.glDisable(GL10.GL_DITHER);
        GLESUtil.glesCheckError("glDisable");
        GLES20.glDisable(GL10.GL_CULL_FACE);
        GLESUtil.glesCheckError("glDisable");
        GLES20.glEnable(GL10.GL_DEPTH_TEST);
        GLESUtil.glesCheckError("glEnable");
        GLES20.glDepthMask(false);
        GLESUtil.glesCheckError("glDepthMask");
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLESUtil.glesCheckError("glDepthFunc");

        // Create an effect context
        if (mEffectContext != null) {
            mEffectContext.release();
        }
        mEffectContext = EffectContext.createWithCurrentGlContext();

        // Create the texture manager and recycle the old one
        if (mTextureManager == null) {
            // Precalculate the window size for the TextureManager. In onSurfaceChanged
            // the best fixed size will be set. The disposition size is simple for a better
            // performance of the internal arrays
            final Configuration conf = mContext.getResources().getConfiguration();
            int orientation = mContext.getResources().getConfiguration().orientation;
            int w = (int) AndroidHelper.convertDpToPixel(mContext, conf.screenWidthDp);
            int h = (int) AndroidHelper.convertDpToPixel(mContext, conf.screenHeightDp);
            Rect dimensions = new Rect(0, 0, w, h);
            int cc = (orientation == Configuration.ORIENTATION_PORTRAIT)
                        ? Preferences.Layout.getPortraitDisposition().size()
                        : Preferences.Layout.getLandscapeDisposition().size();

            // Recycle the current texture manager and create a new one
            recycle();
            mTextureManager = new TextureManager(
                    mContext, mHandler, mEffectContext, mDispatcher, cc, dimensions);
        } else {
            mTextureManager.updateEffectContext(mEffectContext);
        }

        // Schedule dispositions random recreation (if need it)
        scheduleDispositionRecreation();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        if (DEBUG) Log.d(TAG, "onSurfaceChanged [" + mInstance + "," + width + "x" + height + "]");

        // Check if the size was changed
        if (mWidth == width && mHeight == height) {
            return;
        }

        // Save the width and height to avoid recreate the world
        mWidth = width;
        mHeight = height;
        mStatusBarHeight = AndroidHelper.calculateStatusBarHeight(mContext);
        mMeasuredHeight = mHeight + mStatusBarHeight;

        // Calculate a better fixed size for the pictures
        Rect dimensions = Utils.isTablet(mContext)
                             ? new Rect(0, 0, width / 2, height / 2)
                             : new Rect(0, 0, width / 4, height / 4);
        Rect screenDimensions = new Rect(0, AndroidHelper.isKitKat() ? 0 : mStatusBarHeight,
                width, AndroidHelper.isKitKat() ? height + mStatusBarHeight : height);
        mTextureManager.setDimensions(dimensions);
        mTextureManager.setScreenDimesions(screenDimensions);
        mTextureManager.setPause(false);

        // Create the wallpaper (destroy the previous world)
        if (mWorld != null) {
            mWorld.recycle(false);
        }
        mWorld = new PhotoPhaseWallpaperWorld(mContext, mTextureManager);

        // Create the overlay shape
        final float[] vertex = {
                                -1.0f, -1.0f,
                                 1.0f, -1.0f,
                                -1.0f,  1.0f,
                                 1.0f,  1.0f
                               };
        mOverlay = new ColorShape(mContext, vertex, Colors.getOverlay());

        // Create the Oops shape
        mOopsShape = new OopsShape(mContext, R.string.no_pictures_oops_msg);

        // Set the viewport and the fustrum
        GLES20.glViewport(0, AndroidHelper.isKitKat() ? 0 : -mStatusBarHeight, mWidth,
                AndroidHelper.isKitKat() ? mHeight + mStatusBarHeight : mHeight);
        GLESUtil.glesCheckError("glViewport");
        Matrix.frustumM(mProjMatrix, 0, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 2.0f);

        // Recreate the wallpaper world
        try {
            mWorld.recreateWorld(width, mMeasuredHeight);
        } catch (GLException e) {
            Log.e(TAG, "Cannot recreate the wallpaper world.", e);
        }

        // Force an immediate redraw of the screen (draw thread could be in dirty mode only)
        deselectCurrentTransition();
        mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDrawFrame(GL10 glUnused) {
        // Remove the EGL context watchdog
        if (!mIsPreview) {
            mHandler.removeCallbacks(mEGLContextWatchDog);
        }

        // Set the projection, view and model
        GLES20.glViewport(0, -mStatusBarHeight, mWidth, mHeight);
        Matrix.setLookAtM(mVMatrix, 0, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mVMatrix, 0);

        if (mTextureManager != null) {
            if (mTextureManager.getStatus() == 1 && mTextureManager.isEmpty()) {
                // Advise the user and stop
                drawOops();
                mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            } else {
                // Draw the background
                drawBackground();

                if (!mIsPaused && mWorld != null) {
                    // Now draw the world (all the photo frames with effects)
                    mWorld.draw(mMVPMatrix);

                    // Check if we have some pending transition or transition has
                    // exceed its timeout
                    synchronized (mDrawing) {
                        final int interval = Preferences.General.Transitions.getTransitionInterval();
                        if (interval > 0) {
                            if (!mWorld.hasRunningTransition() || isTransitionTimeoutFired()) {
                                mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

                                // Now start a delayed thread to generate the next effect
                                deselectCurrentTransition();
                                long diff = System.currentTimeMillis() - mLastTransition;
                                long delay = Math.max(200, interval - diff);
                                mHandler.postDelayed(mTransitionThread, delay);
                            }
                        } else {
                            // Just display the initial frames and never make transitions
                            if (!mWorld.hasRunningTransition() || isTransitionTimeoutFired()) {
                                mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                            }
                        }
                    }
                } else {
                    if (mWorld != null) {
                        // Just draw the world before notify GLView to goto sleep
                        mWorld.draw(mMVPMatrix);
                    }
                    mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                }

                // Draw the overlay
                drawOverlay();
            }
        }
    }

    /**
     * Check whether the transition has exceed the timeout
     *
     * @return boolean if the transition has exceed the timeout
     */
    private boolean isTransitionTimeoutFired() {
        long now = System.currentTimeMillis();
        long diff = now - mLastRunningTransition;
        return mLastRunningTransition != 0 && diff > Transition.MAX_TRANSTION_TIME;
    }

    /**
     * Method that draws the background of the wallpaper
     */
    private static void drawBackground() {
        GLColor bg = Colors.getBackground();
        GLES20.glClearColor(bg.r, bg.g, bg.b, bg.a);
        GLESUtil.glesCheckError("glClearColor");
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLESUtil.glesCheckError("glClear");
    }

    /**
     * Method that draws the overlay of the wallpaper
     */
    private void drawOverlay() {
        if (mOverlay != null) {
            mOverlay.setAlpha(Preferences.General.getWallpaperDim() / 100.0f);
            mOverlay.draw(mMVPMatrix);
        }
    }

    /**
     * Method that draws the oops message
     */
    private void drawOops() {
        if (mOopsShape != null) {
            mOopsShape.draw(mMVPMatrix);
        }
    }

}