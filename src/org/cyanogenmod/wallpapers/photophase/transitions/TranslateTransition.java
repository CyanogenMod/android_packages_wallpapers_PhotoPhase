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

package org.cyanogenmod.wallpapers.photophase.transitions;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.opengl.Matrix;
import android.os.SystemClock;

import org.cyanogenmod.wallpapers.photophase.utils.GLESUtil;
import org.cyanogenmod.wallpapers.photophase.utils.Utils;
import org.cyanogenmod.wallpapers.photophase.PhotoFrame;
import org.cyanogenmod.wallpapers.photophase.R;
import org.cyanogenmod.wallpapers.photophase.TextureManager;
import org.cyanogenmod.wallpapers.photophase.transitions.Transitions.TRANSITIONS;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A transition that applies a translation transition to the picture.
 */
public class TranslateTransition extends Transition {

    /**
     * The enumeration of all possibles translations movements
     */
    public enum TRANSLATE_MODES {
        /**
         * Translate the picture from left to right
         */
        LEFT_TO_RIGHT,
        /**
         * Translate the picture from right to left
         */
        RIGHT_TO_LEFT,
        /**
         * Translate the picture from up to down
         */
        UP_TO_DOWN,
        /**
         * Translate the picture from down to up
         */
        DOWN_TO_UP
    }

    private static final int[] VERTEX_SHADER = {R.raw.default_vertex_shader, R.raw.default_vertex_shader};
    private static final int[] FRAGMENT_SHADER = {R.raw.default_fragment_shader, R.raw.default_fragment_shader};

    private static final float TRANSITION_TIME = 600.0f;

    private TRANSLATE_MODES mMode;

    private boolean mRunning;
    private long mTime;

    /**
     * Constructor of <code>TranslateTransition</code>
     *
     * @param ctx The current context
     * @param tm The texture manager
     */
    public TranslateTransition(Context ctx, TextureManager tm) {
        super(ctx, tm, VERTEX_SHADER, FRAGMENT_SHADER);

        // Initialized
        reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TRANSITIONS getType() {
        return TRANSITIONS.TRANSLATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasTransitionTarget() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRunning() {
        return mRunning;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void select(PhotoFrame target) {
        super.select(target);

        // Discard all non-supported modes
        List<TRANSLATE_MODES> modes =
                new ArrayList<TranslateTransition.TRANSLATE_MODES>(
                        Arrays.asList(TRANSLATE_MODES.values()));
        float[] vertex = target.getFrameVertex();
        if (vertex[4] != -1.0f) {
            modes.remove(TRANSLATE_MODES.RIGHT_TO_LEFT);
        }
        if (vertex[6] != 1.0f) {
            modes.remove(TRANSLATE_MODES.LEFT_TO_RIGHT);
        }
        if (vertex[5] != 1.0f) {
            modes.remove(TRANSLATE_MODES.DOWN_TO_UP);
        }
        if (vertex[1] != -1.0f) {
            modes.remove(TRANSLATE_MODES.UP_TO_DOWN);
        }

        // Random mode
        int low = 0;
        int high = modes.size() - 1;
        mMode = modes.get(Utils.getNextRandom(low, high));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSelectable(PhotoFrame frame) {
        float[] vertex = frame.getFrameVertex();
        if (vertex[4] == -1.0f || vertex[6] == 1.0f ||
            vertex[5] == 1.0f || vertex[1] == -1.0f) {
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        mTime = -1;
        mRunning = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(float[] matrix) throws GLException {
        // Check internal vars
        if (mTarget == null ||
            mTarget.getPositionBuffer() == null ||
            mTarget.getTextureBuffer() == null) {
            return;
        }
        if (mTransitionTarget == null ||
            mTransitionTarget.getPositionBuffer() == null ||
            mTransitionTarget.getTextureBuffer() == null) {
            return;
        }

        // Set the time the first time
        if (mTime == -1) {
            mTime = SystemClock.uptimeMillis();
        }

        // Calculate the delta time
        final float delta = Math.min(SystemClock.uptimeMillis() - mTime, TRANSITION_TIME) / TRANSITION_TIME;

        // Apply the transition
        applyTransitionToDst(delta, matrix);
        if (delta < 1) {
            applyTransitionToSrc(delta, matrix);
        }

        // Transition ending
        if (delta == 1) {
            mRunning = false;
        }
    }

    /**
     * Apply the transition to the source frame
     *
     * @param delta The delta time
     * @param matrix The model-view-projection matrix
     */
    private void applyTransitionToSrc(float delta, float[] matrix) {
        // Bind default FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLESUtil.glesCheckError("glBindFramebuffer");

        // Set the program
        useProgram(0);

        // Disable blending
        GLES20.glDisable(GLES20.GL_BLEND);
        GLESUtil.glesCheckError("glDisable");

        // Set the input texture
        int textureHandle = mTarget.getTextureHandle();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLESUtil.glesCheckError("glActiveTexture");
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);
        GLESUtil.glesCheckError("glBindTexture");
        GLES20.glUniform1i(mTextureHandlers[0], 0);
        GLESUtil.glesCheckError("glBindTexture");

        // Texture
        FloatBuffer textureBuffer = mTarget.getTextureBuffer();
        textureBuffer.position(0);
        GLES20.glVertexAttribPointer(mTextureCoordHandlers[0], 2, GLES20.GL_FLOAT, false, 0, textureBuffer);
        GLESUtil.glesCheckError("glVertexAttribPointer");
        GLES20.glEnableVertexAttribArray(mTextureCoordHandlers[0]);
        GLESUtil.glesCheckError("glEnableVertexAttribArray");

        // Position
        FloatBuffer positionBuffer = mTarget.getPositionBuffer();
        positionBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandlers[0], 2, GLES20.GL_FLOAT, false, 0, positionBuffer);
        GLESUtil.glesCheckError("glVertexAttribPointer");
        GLES20.glEnableVertexAttribArray(mPositionHandlers[0]);
        GLESUtil.glesCheckError("glEnableVertexAttribArray");

        // Calculate the delta distance
        float distance =
           (mMode.compareTo(TRANSLATE_MODES.LEFT_TO_RIGHT) == 0 || mMode.compareTo(TRANSLATE_MODES.RIGHT_TO_LEFT) == 0)
           ? mTarget.getFrameWidth()
           : mTarget.getFrameHeight();
        if (mMode.compareTo(TRANSLATE_MODES.RIGHT_TO_LEFT) == 0 || mMode.compareTo(TRANSLATE_MODES.DOWN_TO_UP) == 0) {
            distance *= -1;
        }
        distance *= delta;
        boolean vertical = (mMode.compareTo(TRANSLATE_MODES.UP_TO_DOWN) == 0 || mMode.compareTo(TRANSLATE_MODES.DOWN_TO_UP) == 0);

        // Apply the projection and view transformation
        float[] translationMatrix = new float[16];
        if (vertical) {
            Matrix.translateM(translationMatrix, 0, matrix, 0, 0.0f, distance, 0.0f);
        } else {
            Matrix.translateM(translationMatrix, 0, matrix, 0, distance, 0.0f, 0.0f);
        }
        GLES20.glUniformMatrix4fv(mMVPMatrixHandlers[0], 1, false, translationMatrix, 0);
        GLESUtil.glesCheckError("glUniformMatrix4fv");

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLESUtil.glesCheckError("glDrawElements");

        // Disable attributes
        GLES20.glDisableVertexAttribArray(mPositionHandlers[0]);
        GLESUtil.glesCheckError("glDisableVertexAttribArray");
        GLES20.glDisableVertexAttribArray(mTextureCoordHandlers[0]);
        GLESUtil.glesCheckError("glDisableVertexAttribArray");
    }

    /**
     * Apply the transition to the destination frame
     *
     * @param delta The delta time
     * @param matrix The model-view-projection matrix
     */
    private void applyTransitionToDst(float delta, float[] matrix) {
        // Bind default FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLESUtil.glesCheckError("glBindFramebuffer");

        // Set the program
        useProgram(1);

        // Disable blending
        GLES20.glDisable(GLES20.GL_BLEND);
        GLESUtil.glesCheckError("glDisable");

        // Set the input texture
        int textureHandle = mTransitionTarget.getTextureHandle();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLESUtil.glesCheckError("glActiveTexture");
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);
        GLESUtil.glesCheckError("glBindTexture");
        GLES20.glUniform1i(mTextureHandlers[1], 0);
        GLESUtil.glesCheckError("glUniform1i");

        // Texture
        FloatBuffer textureBuffer = mTransitionTarget.getTextureBuffer();
        textureBuffer.position(0);
        GLES20.glVertexAttribPointer(mTextureCoordHandlers[1], 2, GLES20.GL_FLOAT, false, 0, textureBuffer);
        GLESUtil.glesCheckError("glVertexAttribPointer");
        GLES20.glEnableVertexAttribArray(mTextureCoordHandlers[1]);
        GLESUtil.glesCheckError("glEnableVertexAttribArray");

        // Position
        FloatBuffer positionBuffer = mTransitionTarget.getPositionBuffer();
        positionBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandlers[1], 2, GLES20.GL_FLOAT, false, 0, positionBuffer);
        GLESUtil.glesCheckError("glVertexAttribPointer");
        GLES20.glEnableVertexAttribArray(mPositionHandlers[1]);
        GLESUtil.glesCheckError("glEnableVertexAttribArray");

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(mMVPMatrixHandlers[1], 1, false, matrix, 0);
        GLESUtil.glesCheckError("glUniformMatrix4fv");

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLESUtil.glesCheckError("glDrawElements");

        // Disable attributes
        GLES20.glDisableVertexAttribArray(mPositionHandlers[1]);
        GLESUtil.glesCheckError("glDisableVertexAttribArray");
        GLES20.glDisableVertexAttribArray(mTextureCoordHandlers[1]);
        GLESUtil.glesCheckError("glDisableVertexAttribArray");
    }

}
