/*
 * Copyright (c) 2017. Shaleen Jain
 */

package com.shaleenjain.ola.play.data.model;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import com.shaleenjain.ola.play.R;
import com.shaleenjain.ola.play.data.DataManager;
import com.shaleenjain.ola.play.utils.LogHelper;
import com.shaleenjain.ola.play.utils.MediaIDHelper;
import com.shaleenjain.ola.play.utils.RxUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

import static com.shaleenjain.ola.play.utils.MediaIDHelper.MEDIA_ID_ALL;
import static com.shaleenjain.ola.play.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE;
import static com.shaleenjain.ola.play.utils.MediaIDHelper.MEDIA_ID_PLAYLIST;
import static com.shaleenjain.ola.play.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.shaleenjain.ola.play.utils.MediaIDHelper.createMediaID;

/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    DataManager mDataManager;
    private Disposable mDisposable;

    // Categorized caches for music track data:
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;
    private List<MediaBrowserCompat.MediaItem> Myplaylist = new ArrayList<>();

    private final Set<String> mFavoriteTracks;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider(DataManager dataManager) {
        mDataManager = dataManager;
        mMusicListById = new ConcurrentHashMap<>();
        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    }

    /**
     * Get an iterator over a shuffled collection of all songs
     */
    public Iterable<MediaMetadataCompat> getShuffledMusic() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        List<MediaMetadataCompat> shuffled = new ArrayList<>(mMusicListById.size());
        for (MutableMediaMetadata mutableMetadata: mMusicListById.values()) {
            shuffled.add(mutableMetadata.metadata);
        }
        Collections.shuffle(shuffled);
        return shuffled;
    }

    public Iterable<MediaMetadataCompat> getMusicList() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        List<MediaMetadataCompat> shuffled = new ArrayList<>(mMusicListById.size());
        for (MutableMediaMetadata mutableMetadata: mMusicListById.values()) {
            shuffled.add(mutableMetadata.metadata);
        }
        return shuffled;
    }


    /**
     * Very basic implementation of a search that filter music tracks with title containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicBySongTitle(String query) {
        Timber.d("Called!!!");
        List<MediaMetadataCompat> queue = searchMusic(MediaMetadataCompat.METADATA_KEY_TITLE,
                query);
//        mMusicListById = queue;
        return queue;
    }

    /**
     * Very basic implementation of a search that filter music tracks with album containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByAlbum(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ALBUM, query);
    }

    /**
     * Very basic implementation of a search that filter music tracks with artist containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByArtist(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ARTIST, query);
    }

    /**
     * Very basic implementation of a search that filter music tracks with a genre containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByGenre(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_GENRE, query);
    }

    private List<MediaMetadataCompat> searchMusic(String metadataField, String query) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadataCompat> result = new ArrayList<>();
        query = query.toLowerCase(Locale.US);
        for (MutableMediaMetadata track : mMusicListById.values()) {
            if (track.metadata.getString(metadataField).toLowerCase(Locale.US)
                .contains(query)) {
                result.add(track.metadata);
            }
        }
        return result;
    }

    /**
     * Return the MediaMetadataCompat for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public MediaMetadataCompat getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId).metadata : null;
    }

    public synchronized void updateMusicArt(String musicId, Bitmap albumArt, Bitmap icon) {
        MediaMetadataCompat metadata = getMusic(musicId);
        metadata = new MediaMetadataCompat.Builder(metadata)

                // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is used, for
                // example, on the lockscreen background when the media session is active.
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)

                // set small version of the album art in the DISPLAY_ICON. This is used on
                // the MediaDescription and thus it should be small to be serialized if
                // necessary
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)

                .build();

        MutableMediaMetadata mutableMetadata = mMusicListById.get(musicId);
        if (mutableMetadata == null) {
            throw new IllegalStateException("Unexpected error: Inconsistent data structures in " +
                    "MusicProvider");
        }

        mutableMetadata.metadata = metadata;
    }

    public void setFavorite(String musicId, boolean favorite) {
        if (favorite) {
            mFavoriteTracks.add(musicId);
        } else {
            mFavoriteTracks.remove(musicId);
        }
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    public boolean isFavorite(String musicId) {
        return mFavoriteTracks.contains(musicId);
    }

    private MediaMetadataCompat createMetaDataFromTrack(Track track) {
        MediaMetadataCompat item = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id())
                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE, track.url())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artists())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.cover_image())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.song())
                .build();
        return item;
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        Timber.d( "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            if (callback != null) {
                // Nothing to do, execute callback immediately
                callback.onMusicCatalogReady(true);
            }
            return;
        }

        RxUtil.dispose(mDisposable);
        mDataManager.syncTracks()
                .subscribeOn(Schedulers.io())
                .subscribe(new Observer<Track>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        mDisposable = d;
                        if (mCurrentState == State.NON_INITIALIZED)
                            mCurrentState = State.INITIALIZING;
                    }

                    @Override
                    public void onNext(@NonNull Track track) {
                        MediaMetadataCompat item = createMetaDataFromTrack(track);

                        mMusicListById.put(track.id(), new MutableMediaMetadata(track.id(), item));
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.w(e, "Error syncing.");
                        mCurrentState = State.NON_INITIALIZED;
                        if (callback != null) {
                            callback.onMusicCatalogReady(false);
                        }
                    }

                    @Override
                    public void onComplete() {
                        Timber.i("Synced successfully!");
                        mCurrentState = State.INITIALIZED;
                        if (callback != null) {
                            callback.onMusicCatalogReady(true);
                        }
                    }
                });
    }

    public List<MediaBrowserCompat.MediaItem> getChildren(String mediaId, Resources resources) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        if (!MediaIDHelper.isBrowseable(mediaId)) {
            return mediaItems;
        }

        Timber.i("Childern %s", mediaId);

        if (MEDIA_ID_ALL.equals(mediaId)) {
            for (MediaMetadataCompat metadata : getMusicList() ) {
                mediaItems.add(createMediaItem(metadata , MEDIA_ID_ALL));
            }

        } else if (MEDIA_ID_PLAYLIST.equals(mediaId)) {
            return getPlaylist();
        }
        else if (MEDIA_ID_ROOT.equals(mediaId)) {
            mediaItems.add(createBrowsableMediaItemForRoot(resources));
            mediaItems.add(createBrowsablePlaylistMediaItemForRoot(resources));

        } else {
            LogHelper.w(TAG, "Skipping unmatched mediaId: ", mediaId);
        }
        return mediaItems;
    }

    private List<MediaBrowserCompat.MediaItem> getPlaylist() {
        Timber.i("Running ?");
        mDataManager.getPlaylist()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Playlist>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(Playlist playlist) {
                        Timber.i("Running ?");
                        for (String id : playlist.mediaids()) {
                            if (id == null) break;
                            mDataManager.getTrack(id)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(track -> {

                                        Timber.i("Running : %s", track.id());
                                        MediaMetadataCompat meta = createMetaDataFromTrack(track);
                                        Myplaylist.add(createMediaItem(meta, MEDIA_ID_PLAYLIST));
                                    });
                        }
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
        return Myplaylist;
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForRoot(Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_ALL)
                .setTitle("All songs")
                .setSubtitle("Songs by Title")
                .setIconUri(Uri.parse("android.resource://" +
                        "com.shaleenjain.ola.play/drawable/ic_by_genre"))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createBrowsablePlaylistMediaItemForRoot(Resources
                                                                                       resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_PLAYLIST)
                .setTitle("Playlist")
                .setIconUri(Uri.parse("android.resource://" +
                        "com.shaleenjain.ola.play/drawable/ic_by_genre"))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForGenre(String genre,
                                                                    Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_MUSICS_BY_GENRE, genre))
                .setTitle(genre)
                .setSubtitle(resources.getString(
                        R.string.browse_musics_by_genre_subtitle, genre))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    public static MediaBrowserCompat.MediaItem createMediaItem(MediaMetadataCompat metadata, String
            MEDIA_ID) {
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)

        String hierarchyAwareMediaID = null;

        if (Objects.equals(MEDIA_ID, MEDIA_ID_MUSICS_BY_GENRE)) {
            String genre = metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE);
            hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                    metadata.getDescription().getMediaId(), MEDIA_ID, genre);
        } else if(Objects.equals(MEDIA_ID, MEDIA_ID_ALL)) {
            hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                    metadata.getDescription().getMediaId(), MEDIA_ID, MEDIA_ID);
        } else if(Objects.equals(MEDIA_ID, MEDIA_ID_PLAYLIST)) {
            hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                    metadata.getDescription().getMediaId(), MEDIA_ID, "0");
        }

        MediaMetadataCompat copy = new MediaMetadataCompat.Builder(metadata)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .build();
        return new MediaBrowserCompat.MediaItem(copy.getDescription(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);

    }

}
