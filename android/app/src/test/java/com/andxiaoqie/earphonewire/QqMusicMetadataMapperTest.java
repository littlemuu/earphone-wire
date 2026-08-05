package com.andxiaoqie.earphonewire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class QqMusicMetadataMapperTest {
    @Test public void qqMusicUsesTitleInsteadOfItsRollingDisplayLyric() {
        QqMusicMetadataMapper.Result result = map(
                "Summer has come and passed", null,
                "Wake Me Up When September Ends",
                "Wake Me Up When September Ends-Green Day", null, null, null);
        assertEquals("Wake Me Up When September Ends", result.title);
        assertEquals("Green Day", result.artist);
    }

    @Test public void changingDisplayLyricsDoNotChangeTheNormalizedSongOrArtist() {
        QqMusicMetadataMapper.Result first = map(
                "Lyrics by：Billie Joe Armstrong", null,
                "Wake Me Up When September Ends",
                "Wake Me Up When September Ends-Green Day", null, null, null);
        QqMusicMetadataMapper.Result second = map(
                "The innocent can never last", null,
                "Wake Me Up When September Ends",
                "Wake Me Up When September Ends-Green Day", null, null, null);
        assertEquals(first.title, second.title);
        assertEquals(first.artist, second.artist);
    }

    @Test public void titleAndCombinedArtistAreNormalizedWithoutDisplayFields() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Fine Again", "Fine Again-Seether", null, null, null);
        assertEquals("Fine Again", result.title);
        assertEquals("Seether", result.artist);
    }

    @Test public void standardIndependentTitleAndArtistRemainTheFallbackWithoutDisplayFields() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Fallback Song", "Fallback Artist", null, null, null);
        assertEquals("Fallback Song", result.title);
        assertEquals("Fallback Artist", result.artist);
    }

    @Test public void stableTitleTakesPriorityOverDisplayTitleAndArtistFieldsRemainIndependent() {
        QqMusicMetadataMapper.Result result = map(
                "Scrolling lyric", "Display artist", "Stable song", "Stable artist", "Album artist", null, null);
        assertEquals("Stable song", result.title);
        assertEquals("Stable artist", result.artist);
    }

    @Test public void displayTitleRemainsTheFallbackWhenTitleIsMissing() {
        QqMusicMetadataMapper.Result result = map(
                "Display song", null, null, "Independent artist", null, null, null);
        assertEquals("Display song", result.title);
        assertEquals("Independent artist", result.artist);
    }

    @Test public void hyphenInSongTitleIsPreservedWhenExactTitlePrefixConfirmsTheArtist() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Rock-n-Roll", "Rock-n-Roll-Artist", null, null, null);
        assertEquals("Rock-n-Roll", result.title);
        assertEquals("Artist", result.artist);
    }

    @Test public void hyphenInArtistIsPreservedAfterExactTitlePrefixIsRemoved() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Song", "Song-Jay-Z", null, null, null);
        assertEquals("Song", result.title);
        assertEquals("Jay-Z", result.artist);
    }

    @Test public void nonMatchingArtistTextIsNeverGuessedApart() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Known", "Uncertain-Value", null, null, null);
        assertEquals("Known", result.title);
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
                "A rolling lyric", null, "Shared Song", "Shared Song-Shared Artist", null, null, null);
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
