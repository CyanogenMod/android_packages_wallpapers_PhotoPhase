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

package org.cyanogenmod.wallpapers.photophase.widgets;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.cyanogenmod.wallpapers.photophase.R;
import org.cyanogenmod.wallpapers.photophase.model.Album;
import org.cyanogenmod.wallpapers.photophase.tasks.AsyncPictureLoaderTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A view that contains the view of the info of an album
 */
public class AlbumInfoView extends RelativeLayout
        implements OnClickListener, OnMenuItemClickListener {

    /**
     * A convenient listener for receive events of the AlbumPictures class
     *
     */
    public interface CallbacksListener {
        /**
         * Invoked when an album was selected
         *
         * @param album The album
         */
        void onAlbumSelected(Album album);

        /**
         * Invoked when an album was deselected
         *
         * @param album The album
         */
        void onAlbumDeselected(Album album);

        /**
         * Invoked when an all of the picture of the album were selected
         *
         * @param album The album
         */
        void onAllPicturesSelected(Album album);

        /**
         * Invoked when an all of the picture of the album were deselected
         *
         * @param album The album
         */
        void onAllPicturesDeselected(Album album);
    }

    private class OnPictureLoaded extends AsyncPictureLoaderTask.OnPictureLoaded {
        public OnPictureLoaded(Album album) {
            super(new Object[]{album});
        }

        @Override
        public void onPictureLoaded(Object o, Drawable drawable) {
            ((Album)o).setIcon(drawable);
        }
    }

    private List<CallbacksListener> mCallbacks;

    private Album mAlbum;

    private AsyncPictureLoaderTask mTask;

    private ImageView mIcon;
    private TextView mSelectedItems;
    private TextView mName;
    private TextView mItems;
    private View mOverflowButton;

    private boolean mAlbumMode;

    /**
     * Constructor of <code>AlbumInfoView</code>.
     *
     * @param context The current context
     */
    public AlbumInfoView(Context context) {
        super(context);
        init();
    }

    /**
     * Constructor of <code>AlbumInfoView</code>.
     *
     * @param context The current context
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public AlbumInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Constructor of <code>AlbumInfoView</code>.
     *
     * @param context The current context
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyle The default style to apply to this view. If 0, no style
     *        will be applied (beyond what is included in the theme). This may
     *        either be an attribute resource, whose value will be retrieved
     *        from the current theme, or an explicit style resource.
     */
    public AlbumInfoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    /**
     * Method that initializes the internal references
     */
    private void init() {
        mCallbacks = new ArrayList<AlbumInfoView.CallbacksListener>();
        mAlbumMode = true;
    }

    /**
     * Method that set the album mode
     *
     * @param albumMode The album mode
     */
    public void setAlbumMode(boolean albumMode) {
        this.mAlbumMode = albumMode;
    }

    /**
     * Method that adds the class that will be listen for events of this class
     *
     * @param callback The callback class
     */
    public void addCallBackListener(CallbacksListener callback) {
        this.mCallbacks.add(callback);
    }

    /**
     * Method that removes the class from the current callbacks
     *
     * @param callback The callback class
     */
    public void removeCallBackListener(CallbacksListener callback) {
        this.mCallbacks.remove(callback);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        updateView(mAlbum);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // Cancel pending tasks
        if (mTask != null && mTask.getStatus().compareTo(Status.PENDING) == 0) {
            mTask.cancel(true);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClick(View v) {
        if (v.equals(mOverflowButton)) {
            PopupMenu popup = new PopupMenu(getContext(), v);
            MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.album_actions, popup.getMenu());
            onPreparePopupMenu(popup.getMenu());
            popup.setOnMenuItemClickListener(this);
            popup.show();
        }
    }

    /**
     * Method called prior to show the popup menu
     *
     * @param popup The popup menu
     */
    public void onPreparePopupMenu(Menu popup) {
        if (isSelected()) {
            popup.findItem(R.id.mnu_select_album).setVisible(false);
        } else {
            popup.findItem(R.id.mnu_deselect_album).setVisible(false);
        }
        if (mAlbumMode) {
            popup.findItem(R.id.mnu_select_all).setVisible(false);
            popup.findItem(R.id.mnu_deselect_all).setVisible(false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mnu_select_album:
                doSelection(true);
                break;

            case R.id.mnu_deselect_album:
                doSelection(false);
                break;

            case R.id.mnu_select_all:
                notifyPictureSelectionChanged(true);
                break;

            case R.id.mnu_deselect_all:
                notifyPictureSelectionChanged(false);
                break;

            default:
                return false;
        }
        return true;
    }

    /**
     * Method that select/deselect the album
     *
     * @param selected whether the album is selected
     */
    public void doSelection(boolean selected) {
        setSelected(selected);
        mAlbum.setSelected(selected);
        mAlbum.setSelectedItems(new ArrayList<String>());
        notifySelectionChanged();
        updateView(mAlbum);
    }

    /**
     * Method that notifies to all the registered callbacks that the selection
     * was changed
     */
    private void notifySelectionChanged() {
        for (CallbacksListener callback : mCallbacks) {
            if (mAlbum.isSelected()) {
                callback.onAlbumSelected(mAlbum);
            } else {
                callback.onAlbumDeselected(mAlbum);
            }
        }
    }

    /**
     * Method that notifies to all the registered callbacks that the selection
     * was changed
     */
    private void notifyPictureSelectionChanged(boolean selected) {
        for (CallbacksListener callback : mCallbacks) {
            if (selected) {
                callback.onAllPicturesSelected(mAlbum);
            } else {
                callback.onAllPicturesDeselected(mAlbum);
            }
        }
    }

    /**
     * Method that sets the album
     *
     * @param album The album
     */
    public void setAlbum(Album album) {
        mAlbum = album;
    }

    /**
     * Method that updates the view
     *
     * @param album The album data
     */
    @SuppressWarnings("boxing")
    public void updateView(Album album) {
        // Destroy the update drawable task
        if (mTask != null && (mTask.getStatus() == AsyncTask.Status.RUNNING ||
                mTask.getStatus() == AsyncTask.Status.PENDING)) {
            mTask.cancel(true);
        }

        // Retrieve the views references
        if (mIcon == null) {
            mIcon = (ImageView)findViewById(R.id.album_thumbnail);
            mSelectedItems = (TextView)findViewById(R.id.album_selected_items);
            mName = (TextView)findViewById(R.id.album_name);
            mItems = (TextView)findViewById(R.id.album_items);
            mOverflowButton = findViewById(R.id.overflow_button);
            mOverflowButton.setOnClickListener(this);
        }

        // Update the views
        if (album != null) {
            Resources res = getContext().getResources();

            setAlbum(album);

            int selectedItems = album.getSelectedItems().size();
            String count = String.valueOf(selectedItems);
            if (selectedItems > 99) {
                count = "99+";
            }
            mSelectedItems.setText(count);
            mSelectedItems.setVisibility(album.isSelected() ? View.INVISIBLE : View.VISIBLE);
            mName.setText(album.getName());
            int items = album.getItems().size();
            mItems.setText(String.format(res.getQuantityText(
                    R.plurals.album_number_of_pictures, items).toString(), items));
            setSelected(album.isSelected());

            Drawable dw = album.getIcon();
            mIcon.setImageDrawable(dw);
            if (dw == null) {
                mIcon.setImageDrawable(null);

                // Show as icon, the first picture
                mTask = new AsyncPictureLoaderTask(getContext(), mIcon, new OnPictureLoaded(album));
                mTask.execute(new File(album.getItems().get(0).getPath()));
            }
        }
    }

}
