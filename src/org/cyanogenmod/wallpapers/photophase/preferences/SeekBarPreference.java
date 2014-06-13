/*
 * Copyright (C) 2013 The Android Open Source Project
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

package org.cyanogenmod.wallpapers.photophase.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import org.cyanogenmod.wallpapers.photophase.R;

/**
 * A preference with a seekbar widget
 */
public class SeekBarPreference extends Preference implements OnSeekBarChangeListener {

    private int mProgress;
    private int mMax;
    private boolean mTrackingTouch;

    /**
     * Constructor of <code>SeekBarPreference</code>
     *
     * @param context The current context
     * @param attrs The attributes of the view
     * @param defStyle The resource with the style
     */
    public SeekBarPreference(
            Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setMax(100);
        setLayoutResource(R.layout.preference_widget_seekbar);
    }

    /**
     * Constructor of <code>SeekBarPreference</code>
     *
     * @param context The current context
     * @param attrs The attributes of the view
     */
    public SeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Constructor of <code>SeekBarPreference</code>
     *
     * @param context The current context
     */
    public SeekBarPreference(Context context) {
        this(context, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        SeekBar seekBar = (SeekBar) view.findViewById(R.id.seekbar);
        seekBar.setOnSeekBarChangeListener(this);
        seekBar.setMax(mMax);
        seekBar.setProgress(mProgress);
        seekBar.setEnabled(isEnabled());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("boxing")
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        setProgress(restoreValue ? getPersistedInt(mProgress) : (Integer) defaultValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("boxing")
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, 0);
    }

    /**
     * Allows a Preference to intercept key events without having focus.
     * For example, SeekBarPreference uses this to intercept +/- to adjust
     * the progress.
     *
     * @param v The view
     * @param keyCode The key code
     * @param event The key event
     * @return True if the Preference handled the key. Returns false by default.
     */
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_PLUS
                    || keyCode == KeyEvent.KEYCODE_EQUALS) {
                setProgress(getProgress() + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MINUS) {
                setProgress(getProgress() - 1);
                return true;
            }
        }
        return false;
    }

    /**
     * Method that set the maximum progress
     *
     * @param max The maximum progress
     */
    public void setMax(int max) {
        if (max != mMax) {
            mMax = max;
            notifyChanged();
        }
    }

    /**
     * Method that set the actual progress
     *
     * @param progress The actual progress
     */
    public void setProgress(int progress) {
        setProgress(progress, true);
    }

    /**
     * Method that set the actual progress
     *
     * @param progress The actual progress
     * @param notifyChanged Whether notify if the progress was changed
     */
    protected void setProgress(int progress, boolean notifyChanged) {
        int p = progress;
        if (p > mMax) {
            p = mMax;
        }
        if (p < 0) {
            p = 0;
        }
        if (p != mProgress) {
            mProgress = p;
            persistInt(p);
            if (notifyChanged) {
                notifyChanged();
            }
        }
    }

    /**
     * Method that returns the current progress
     *
     * @return int The current progress
     */
    public int getProgress() {
        return mProgress;
    }

    /**
     * Persist the seekBar's progress value if callChangeListener
     *
     * returns boolean True, otherwise set the seekBar's progress to the stored value
     */
    @SuppressWarnings("boxing")
    void syncProgress(SeekBar seekBar) {
        int progress = seekBar.getProgress();
        if (progress != mProgress) {
            if (callChangeListener(progress)) {
                setProgress(progress, false);
            } else {
                seekBar.setProgress(mProgress);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser && !mTrackingTouch) {
            syncProgress(seekBar);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mTrackingTouch = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        mTrackingTouch = false;
        if (seekBar.getProgress() != mProgress) {
            syncProgress(seekBar);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        /*
         * Suppose a client uses this preference type without persisting. We
         * must save the instance state so it is able to, for example, survive
         * orientation changes.
         */

        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) {
            // No need to save instance state since it's persistent
            return superState;
        }

        // Save the instance state
        final SavedState myState = new SavedState(superState);
        myState.progress = mProgress;
        myState.max = mMax;
        return myState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }

        // Restore the instance state
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        mProgress = myState.progress;
        mMax = myState.max;
        notifyChanged();
    }

    /**
     * SavedState, a subclass of {@link BaseSavedState}, will store the state
     * of MyPreference, a subclass of Preference.
     * <p>
     * It is important to always call through to super methods.
     */
    private static class SavedState extends BaseSavedState {
        int progress;
        int max;

        public SavedState(Parcel source) {
            super(source);

            // Restore the click counter
            progress = source.readInt();
            max = source.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);

            // Save the click counter
            dest.writeInt(progress);
            dest.writeInt(max);
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @SuppressWarnings({"unused", "hiding"})
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
