package com.copybot.plugin.gpszone.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.gpszone.ZoneFinder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CheckGpsZoneTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    private static final double[] PARIS_POSITION = {48.8566, 2.3522};
    private static final double[] MEXICO_POSITION = {19.4326, -99.1332};
    /** In Portugal, Spain 2 km away (Review Focus 2). */
    private static final double[] BORDER_POSITION = {41.97, -6.79};

    private static WorkItem item() throws MalformedURLException {
        return new WorkItem(URI.create("file:///card/item").toURL(), () -> new ByteArrayInputStream(new byte[0]));
    }

    /** A photo shot at {@code shot} (its offset the recorded one), with a valid GPS position fixed 2 min before. */
    private static WorkItem photo(double[] position, String shot) throws MalformedURLException {
        OffsetDateTime date = OffsetDateTime.parse(shot);
        WorkItem item = item();
        Map<String, Object> raw = item.getMetadatas().raw();
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, date.toZonedDateTime());
        raw.put(WorkItemMetadata.RECORDED_OFFSET, date.getOffset());
        raw.put(WorkItemMetadata.GPS_LATITUDE, position[0]);
        raw.put(WorkItemMetadata.GPS_LONGITUDE, position[1]);
        raw.put(WorkItemMetadata.GPS_VALID, true);
        raw.put(WorkItemMetadata.GPS_TIME, date.toInstant().minus(Duration.ofMinutes(2)));
        return item;
    }

    private static Object suggested(WorkItem item) {
        new CheckGpsZone().doAnalyze(item);
        return item.getMetadatas().raw().get(WorkItemMetadata.SUGGESTED_ZONE);
    }

    @Test
    public void aParisPhotoNotedWithoutSummerTimeIsSuggestedParis() throws Exception {
        assertEquals(PARIS, suggested(photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00")));
    }

    @Test
    public void aParisPhotoNotedInSummerTimeIsNotSuggested() throws Exception {
        assertNull(suggested(photo(PARIS_POSITION, "2026-07-10T14:00:00+02:00")));
    }

    @Test
    public void aMexicoPhotoNotedInParisSummerTimeIsSuggestedMexico() throws Exception {
        assertEquals(MEXICO, suggested(photo(MEXICO_POSITION, "2026-07-10T20:00:00+02:00")));
    }

    @Test
    public void aGpsTimeTwoHoursAwayIsNotTrusted() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().put(WorkItemMetadata.GPS_TIME, Instant.parse("2026-07-10T10:00:00Z"));

        assertNull(suggested(photo));
    }

    @Test
    public void aGpsTimeFourteenMinutesAwayIsTrusted() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().put(WorkItemMetadata.GPS_TIME, Instant.parse("2026-07-10T12:14:00Z"));

        assertEquals(PARIS, suggested(photo));
    }

    @Test
    public void nothingWithoutGpsTime() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().remove(WorkItemMetadata.GPS_TIME);

        assertNull(suggested(photo));
    }

    @Test
    public void nothingForAnInvalidGps() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().put(WorkItemMetadata.GPS_VALID, false);

        assertNull(suggested(photo));
    }

    @Test
    public void nothingAtZeroZero() throws Exception {
        WorkItem photo = photo(new double[]{0, 0}, "2026-07-10T13:00:00+01:00");

        assertNull(suggested(photo));
    }

    @Test
    public void nothingWithoutRecordedOffsetAsForAVideo() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().remove(WorkItemMetadata.RECORDED_OFFSET);

        assertNull(suggested(photo));
    }

    @Test
    public void nothingNearTheSpainPortugalBorder() throws Exception {
        // noted in Spanish summer time: right for Spain, wrong for Portugal where the point itself is
        assertNull(suggested(photo(BORDER_POSITION, "2026-07-10T14:00:00+02:00")));
    }

    @Test
    public void nothingWhenTheZoneWasCorrected() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, PARIS);

        assertNull(suggested(photo));
    }

    @Test
    public void theSuggestionIsRemovedAtEachAnalysis() throws Exception {
        WorkItem photo = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        assertEquals(PARIS, suggested(photo));

        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, PARIS); // corrected in the plan
        assertNull(suggested(photo));

        photo.getMetadatas().raw().remove(WorkItemMetadata.CAPTURE_ZONE); // correction cancelled
        assertEquals(PARIS, suggested(photo));
    }

    @Test
    public void anImportWithoutGpsNeverLoadsTheZones() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CheckGpsZone check = new CheckGpsZone(new ZoneFinder(() -> {
            loads.incrementAndGet();
            return (lat, lon) -> List.of(PARIS);
        }));
        WorkItem withoutGps = item();
        withoutGps.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, OffsetDateTime.parse("2026-07-10T13:00:00+01:00").toZonedDateTime());
        withoutGps.getMetadatas().raw().put(WorkItemMetadata.RECORDED_OFFSET, ZoneOffset.ofHours(1));
        WorkItem video = photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00");
        video.getMetadatas().raw().remove(WorkItemMetadata.RECORDED_OFFSET);

        check.doAnalyze(withoutGps);
        check.doAnalyze(item());
        check.doAnalyze(video);

        assertEquals(0, loads.get());
        check.doAnalyze(photo(PARIS_POSITION, "2026-07-10T13:00:00+01:00"));
        assertEquals(1, loads.get(), "loaded at the first file whose GPS can tell");
    }
}
