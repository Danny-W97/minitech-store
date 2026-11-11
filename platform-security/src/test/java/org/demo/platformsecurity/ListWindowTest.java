package org.demo.platformsecurity;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ListWindowTest {
    @Test void rejectsUnboundedAndInvalidWindows() {
        for (int size : List.of(-1, 0, 201, Integer.MAX_VALUE))
            assertThrows(ResponseStatusException.class, () -> new ListWindow(0, size));
        assertThrows(ResponseStatusException.class, () -> new ListWindow(-1, 50));
        assertThrows(ResponseStatusException.class, () -> ListWindow.search("x".repeat(201)));
        assertEquals("%abc%", ListWindow.search(" AbC "));
    }
    @Test void sentinelIsNotMappedAndCursorPreservesLongPrecision() {
        var page = new ListWindow(0, 2).map(List.of(Long.MAX_VALUE, Long.MAX_VALUE - 1, 1L), Long::longValue, String::valueOf);
        assertEquals(List.of("9223372036854775807", "9223372036854775806"), page.items());
        assertTrue(page.hasMore());
        assertEquals("9223372036854775806", page.nextCursor());
        assertEquals("true", page.response().getHeaders().getFirst("X-Has-More"));
        assertEquals(3, new ListWindow(0, 2).fetch().getPageSize());
        assertEquals(Long.MAX_VALUE, new ListWindow(0, 2).ceiling());
        assertEquals(0, new ListWindow(1, 2).ceiling());
    }
    @Test void terminalAndEmptyPagesHaveNoCursor() {
        var window = new ListWindow(11, 2);
        assertEquals(10, window.ceiling());
        assertFalse(window.map(List.of(10L, 9L), Long::longValue, String::valueOf).hasMore());
        assertEquals("0", window.map(List.<Long>of(), Long::longValue, String::valueOf).nextCursor());
    }
}
