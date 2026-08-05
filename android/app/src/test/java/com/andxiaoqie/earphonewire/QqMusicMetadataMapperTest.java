package com.andxiaoqie.earphonewire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class QqMusicMetadataMapperTest {
    @Test public void currentDeviceRegressionUsesDisplayTitleToNormalizeTheCombinedArtist() {
        QqMusicMetadataMapper.Result result = map(
                "Fine Again", null, "a changing lyric line", "Fine Again-Seether", null, null, null);
        assertEquals("Fine Again", result.title);
        assertEquals("Seether", result.artist);
    }

    @Test public void changingLyricsDoNotChangeTheNormalizedSongOrArtist() {
        QqMusicMetadataMapper.Result first = map(
                "Fine Again", null, "first scrolling lyric", "Fine Again-Seether", null, null, null);
        QqMusicMetadataMapper.Result second = map(
                "Fine Again", null, "second scrolling lyric", "Fine Again-Seether", null, null, null);
        assertEquals(first.title, second.title);
        assertEquals(first.artist, second.artist);
    }

    @Test public void standardIndependentTitleAndArtistRemainTheFallbackWithoutDisplayFields() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Fallback Song", "Fallback Artist", null, null, null);
        assertEquals("Fallback Song", result.title);
        assertEquals("Fallback Artist", result.artist);
    }

    @Test public void displayFieldsTakePriorityOverRawTitleAndArtist() {
        QqMusicMetadataMapper.Result result = map(
                "Display Song", "Display Artist", "Raw title", "Raw artist", "Album artist", null, null);
        assertEquals("Display Song", result.title);
        assertEquals("Display Artist", result.artist);
    }

    @Test public void hyphenInSongTitleIsPreservedWhenKnownArtistConfirmsOnlyTheSuffix() {
        QqMusicMetadataMapper.Result result = map(
                null, "Artist", "Rock-n-Roll-Artist", null, null, null, null);
        assertEquals("Rock-n-Roll", result.title);
        assertEquals("Artist", result.artist);
    }

    @Test public void hyphenInArtistIsPreservedWhenKnownTitleConfirmsOnlyThePrefix() {
        QqMusicMetadataMapper.Result result = map(
                "Song", null, "raw changing text", "Song-Jay-Z", null, null, null);
        assertEquals("Song", result.title);
        assertEquals("Jay-Z", result.artist);
    }

    @Test public void combinedTextWithoutIndependentEvidenceIsNeverGuessedApart() {
        QqMusicMetadataMapper.Result result = map(
                null, null, null, "Uncertain-Value", null, null, null);
        assertNull(result.title);
        assertEquals("Uncertain-Value", result.artist);
    }

    @Test public void descriptionFallbackIsNotIndependentEvidenceForSplitting() {
        QqMusicMetadataMapper.Result result = map(
                null, null, null, "Known-Artist", null, "Known", null);
        assertEquals("Known", result.title);
        assertEquals("Known-Artist", result.artist);
    }

    @Test public void nullEmptyAndWhitespaceCandidatesAreIgnored() {
        QqMusicMetadataMapper.Result result = map(
                "  ", "\t", null, "  ", "\n", " ", "\r\n");
        assertNull(result.title);
        assertNull(result.artist);
    }

    @Test public void theSingleMapperProducesTheSameResultForHomeAndServiceInputs() {
        QqMusicMetadataMapper.Candidates candidates = new QqMusicMetadataMapper.Candidates(
                "Shared Song", null, "raw text", "Shared Song-Shared Artist", null, null, null);
        QqMusicMetadataMapper.Result home = QqMusicMetadataMapper.map(candidates);
        QqMusicMetadataMapper.Result service = QqMusicMetadataMapper.map(candidates);
        assertEquals(home.title, service.title);
        assertEquals(home.artist, service.artist);
    }

    private static QqMusicMetadataMapper.Result map(String displayTitle, String displaySubtitle,
                                                     String title, String artist, String albumArtist,
                                                     String descriptionTitle, String descriptionSubtitle) {
        return QqMusicMetadataMapper.map(new QqMusicMetadataMapper.Candidates(
                displayTitle, displaySubtitle, title, artist, albumArtist, descriptionTitle, descriptionSubtitle));
    }
}
