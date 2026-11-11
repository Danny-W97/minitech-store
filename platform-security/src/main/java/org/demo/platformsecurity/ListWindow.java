package org.demo.platformsecurity;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Bounded keyset reads; existing array responses carry cursor metadata in headers. */
public record ListWindow(long cursor, int size) {
    public static final ListWindow FIRST = new ListWindow(0, 50);
    public ListWindow {
        if (cursor < 0 || size < 1 || size > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "cursor must be nonnegative; size must be between 1 and 200");
    }
    public long ceiling() { return cursor == 0 ? Long.MAX_VALUE : cursor - 1; }
    public PageRequest fetch() { return PageRequest.of(0, size + 1); }
    public <T, R> ListPage<R> map(List<T> rows, ToLongFunction<T> id, Function<T, R> mapper) {
        boolean more = rows.size() > size;
        return new ListPage<>(rows.stream().limit(size).map(mapper).toList(), more,
                more ? Long.toString(id.applyAsLong(rows.get(size - 1))) : "0", size);
    }
    public static String search(String value) {
        if (value != null && value.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is limited to 200 characters");
        return "%" + (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) + "%";
    }
    public record ListPage<T>(List<T> items, boolean hasMore, String nextCursor, int size) {
        public <R> ListPage<R> map(Function<T, R> mapper) {
            return new ListPage<>(items.stream().map(mapper).toList(), hasMore, nextCursor, size);
        }
        public ResponseEntity<List<T>> response() {
            return ResponseEntity.ok().header("X-Has-More", Boolean.toString(hasMore))
                    .header("X-Next-Cursor", nextCursor).header("X-Page-Size", Integer.toString(size)).body(items);
        }
    }
}
