package com.andxiaoqie.earphonewire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class QqMusicMetadataMapperTest {
    @Test public void albumArtistSuffixRestoresTheSongDuringRollingLyrics() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Ohareh yocrehzy", "トルキア-菅野洋子", "菅野洋子", null, null);
        assertEquals("トルキア", result.title);
        assertEquals("菅野洋子", result.artist);
    }

    @Test public void changingRawLyricsDoNotChangeTheAlbumArtistSuffixResult() {
        String[] lyrics = {
                "Eh meh essteena", "K'uureh duhula", "Ah tii yoraum",
                "Dajuuh gah fah", "Hey yah uh hah gloti yuh", "Hey lah huh hah bindy huh"
        };
        for (String lyric : lyrics) {
            QqMusicMetadataMapper.Result result = map(
                    null, null, lyric, "トルキア-菅野洋子", "菅野洋子", null, null);
            assertEquals("トルキア", result.title);
            assertEquals("菅野洋子", result.artist);
        }
    }

    @Test public void instrumentalPromptIsReplacedByTheProvenCombinedField() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "此歌曲为没有填词的纯音乐，请您欣赏", "不回去了-文雀", "文雀", null, null);
        assertEquals("不回去了", result.title);
        assertEquals("文雀", result.artist);
    }

    @Test public void independentlyReportedAlbumArtistConfirmsTheExistingCombinedFieldCase() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Fine Again", "Fine Again-Seether", "Seether", null, null);
        assertEquals("Fine Again", result.title);
        assertEquals("Seether", result.artist);
    }

    @Test public void normalTitleAndArtistAreRetainedWhenArtistIsNotCombined() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "不回去了", "文雀", "文雀", null, null);
        assertEquals("不回去了", result.title);
        assertEquals("文雀", result.artist);
    }

    @Test public void combinedArtistWinsWhenTheRawTitleIsATransitionLabel() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Song - Artist", "Song-Artist", "Artist", null, null);
        assertEquals("Song", result.title);
        assertEquals("Artist", result.artist);
    }

    @Test public void hyphensInTheSongTitleArePreservedByTheExactSuffixRule() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "A lyric", "Wake-Me-Up-Green Day", "Green Day", null, null);
        assertEquals("Wake-Me-Up", result.title);
        assertEquals("Green Day", result.artist);
    }

    @Test public void hyphensInTheArtistArePreservedByTheExactSuffixRule() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "A lyric", "Song-Artist-With-Hyphen", "Artist-With-Hyphen", null, null);
        assertEquals("Song", result.title);
        assertEquals("Artist-With-Hyphen", result.artist);
    }

    @Test public void hyphensInBothSongAndArtistArePreservedByTheExactSuffixRule() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "A lyric", "Rock-n-Roll-Jay-Z", "Jay-Z", null, null);
        assertEquals("Rock-n-Roll", result.title);
        assertEquals("Jay-Z", result.artist);
    }

    @Test public void artistWithoutTheExactAlbumArtistSuffixIsNotSplit() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Known", "Known-Other", "Artist", null, null);
        assertEquals("Known", result.title);
        assertEquals("Known-Other", result.artist);
    }

    @Test public void onlyTheAsciiHyphenCanProveTheAlbumArtistSuffix() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Known", "Known–Artist", "Artist", null, null);
        assertEquals("Known", result.title);
        assertEquals("Known–Artist", result.artist);
    }

    @Test public void missingOrBlankAlbumArtistNeverCausesASplit() {
        assertUnsplitWhenAlbumArtistIs(null);
        assertUnsplitWhenAlbumArtistIs("");
        assertUnsplitWhenAlbumArtistIs(" \t ");
    }

    @Test public void emptyCandidateTitleDoesNotUseTheCombinedField() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Raw title", "-Artist", "Artist", null, null);
        assertEquals("Raw title", result.title);
        assertEquals("-Artist", result.artist);
    }

    @Test public void standardIndependentTitleAndArtistRemainTheFallbackWithoutDisplayFields() {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Fallback Song", "Fallback Artist", null, null, null);
        assertEquals("Fallback Song", result.title);
        assertEquals("Fallback Artist", result.artist);
    }

    @Test public void titleRemainsPreferredToDisplayTitleWhenTheCombinedFieldIsAbsent() {
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
                null, null, "A rolling lyric", "Shared Song-Shared Artist", "Shared Artist", null, null);
        QqMusicMetadataMapper.Result home = QqMusicMetadataMapper.map(candidates);
        QqMusicMetadataMapper.Result service = QqMusicMetadataMapper.map(candidates);
        assertEquals(home.title, service.title);
        assertEquals(home.artist, service.artist);
    }

    private static void assertUnsplitWhenAlbumArtistIs(String albumArtist) {
        QqMusicMetadataMapper.Result result = map(
                null, null, "Raw title", "Song-Artist", albumArtist, null, null);
        assertEquals("Raw title", result.title);
        assertEquals("Song-Artist", result.artist);
    }

    private static QqMusicMetadataMapper.Result map(String displayTitle, String displaySubtitle,
                                                     String title, String artist, String albumArtist,
                                                     String descriptionTitle, String descriptionSubtitle) {
        return QqMusicMetadataMapper.map(new QqMusicMetadataMapper.Candidates(
                displayTitle, displaySubtitle, title, artist, albumArtist, descriptionTitle, descriptionSubtitle));
    }
}
