package com.andxiaoqie.earphonewire;

import android.media.MediaDescription;
import android.media.MediaMetadata;

/** Normalizes QQ Music metadata without treating description fallbacks as independent evidence. */
final class QqMusicMetadataMapper {
    enum Source {
        DISPLAY_TITLE,
        DISPLAY_SUBTITLE,
        TITLE,
        ARTIST,
        ALBUM_ARTIST,
        DESCRIPTION_TITLE,
        DESCRIPTION_SUBTITLE
    }

    static final class Candidate {
        final Source source;
        final String value;

        Candidate(Source source, CharSequence value) {
            this.source = source;
            this.value = normalize(value);
        }

        boolean present() {
            return value != null;
        }
    }

    static final class Candidates {
        final Candidate displayTitle;
        final Candidate displaySubtitle;
        final Candidate title;
        final Candidate artist;
        final Candidate albumArtist;
        final Candidate descriptionTitle;
        final Candidate descriptionSubtitle;

        Candidates(CharSequence displayTitle, CharSequence displaySubtitle, CharSequence title,
                   CharSequence artist, CharSequence albumArtist, CharSequence descriptionTitle,
                   CharSequence descriptionSubtitle) {
            this.displayTitle = new Candidate(Source.DISPLAY_TITLE, displayTitle);
            this.displaySubtitle = new Candidate(Source.DISPLAY_SUBTITLE, displaySubtitle);
            this.title = new Candidate(Source.TITLE, title);
            this.artist = new Candidate(Source.ARTIST, artist);
            this.albumArtist = new Candidate(Source.ALBUM_ARTIST, albumArtist);
            this.descriptionTitle = new Candidate(Source.DESCRIPTION_TITLE, descriptionTitle);
            this.descriptionSubtitle = new Candidate(Source.DESCRIPTION_SUBTITLE, descriptionSubtitle);
        }

        static Candidates from(MediaMetadata metadata) {
            if (metadata == null) return new Candidates(null, null, null, null, null, null, null);
            MediaDescription description = metadata.getDescription();
            return new Candidates(
                    metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    metadata.getText(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getText(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    description == null ? null : description.getTitle(),
                    description == null ? null : description.getSubtitle());
        }
    }

    static final class Result {
        final String title;
        final String artist;

        Result(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }
    }

    private QqMusicMetadataMapper() {
    }

    static Result map(MediaMetadata metadata) {
        return map(Candidates.from(metadata));
    }

    static Result map(Candidates values) {
        String combinedTitle = titleFromExactAlbumArtistSuffix(values.artist, values.albumArtist);
        if (combinedTitle != null) {
            return new Result(combinedTitle, values.albumArtist.value);
        }

        // When no QQ Music combined field is proven, retain the normal metadata fallback order.
        Candidate title = first(values.title, values.displayTitle, values.descriptionTitle);
        Candidate artist = first(values.artist, values.albumArtist, values.displaySubtitle,
                values.descriptionSubtitle);

        return new Result(title == null ? null : title.value, artist == null ? null : artist.value);
    }

    private static Candidate first(Candidate... candidates) {
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.present()) return candidate;
        }
        return null;
    }

    private static String titleFromExactAlbumArtistSuffix(Candidate rawArtist,
                                                           Candidate albumArtist) {
        if (rawArtist == null || !rawArtist.present()
                || albumArtist == null || !albumArtist.present()) return null;
        String suffix = "-" + albumArtist.value;
        if (!rawArtist.value.endsWith(suffix)) return null;
        return normalize(rawArtist.value.substring(0, rawArtist.value.length() - suffix.length()));
    }

    private static String normalize(CharSequence value) {
        if (value == null) return null;
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
