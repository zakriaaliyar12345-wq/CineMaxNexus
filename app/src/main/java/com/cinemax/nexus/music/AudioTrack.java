package com.cinemax.nexus.music;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

/** Metadata reference; the music file remains in its original provider. */
public final class AudioTrack {
    public final String id, title, artist, album;
    public final Uri uri, artworkUri;
    public final long durationMs;

    public AudioTrack(String id, Uri uri, String title, String artist, String album,
                      long durationMs, Uri artworkUri) {
        this.id = id;
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.artworkUri = artworkUri;
    }

    public MediaItem mediaItem() {
        return new MediaItem.Builder().setMediaId(id).setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
                        .setArtist(artist).setAlbumTitle(album).setArtworkUri(artworkUri)
                        .setIsPlayable(true).build()).build();
    }
}
