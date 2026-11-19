package com.grash.service;

import com.grash.utils.ExportColumn;
import com.grash.utils.XlsxTestUtils;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * INC-01: the generic export writes the records in the order of the ids, in batches, and fails instead of delivering
 * a partial file (I-4).
 */
class TabularExportServiceTest {

    private record Item(Long id, String name) {
    }

    private EntityManager entityManager;
    private TabularExportService service;
    private final List<ExportColumn<Item>> columns = List.of(
            new ExportColumn<>("ID", Item::id),
            new ExportColumn<>("Name", Item::name));

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("ID", Locale.ENGLISH, "Id");
        messageSource.addMessage("Name", Locale.ENGLISH, "Nombre");
        service = new TabularExportService(entityManager, messageSource);
    }

    private static Function<List<Long>, Collection<Item>> loaderOf(Map<Long, Item> items, List<List<Long>> calls) {
        return ids -> {
            calls.add(new ArrayList<>(ids));
            List<Item> result = ids.stream().map(items::get).filter(Objects::nonNull).collect(Collectors.toList());
            Collections.reverse(result); // the loader order must not matter
            return result;
        };
    }

    @Test
    void writesTranslatedHeadersAndRowsInIdOrder() throws Exception {
        Map<Long, Item> items = Map.of(1L, new Item(1L, "a"), 2L, new Item(2L, "b"), 3L, new Item(3L, "c"));

        byte[] bytes = service.generate("Sheet", columns, Locale.ENGLISH, List.of(3L, 1L, 2L),
                loaderOf(items, new ArrayList<>()), Item::id);

        assertEquals(List.of(List.of("Id", "Nombre"), List.of("3", "c"), List.of("1", "a"), List.of("2", "b")),
                XlsxTestUtils.readText(bytes, 2));
    }

    @Test
    void loadsInBatchesOf200_andClearsThePersistenceContextPerBatch() throws Exception {
        Map<Long, Item> items = new HashMap<>();
        LongStream.rangeClosed(1, 450).forEach(id -> items.put(id, new Item(id, "n" + id)));
        List<Long> ids = LongStream.rangeClosed(1, 450).boxed().toList();
        List<List<Long>> calls = new ArrayList<>();

        byte[] bytes = service.generate("Sheet", columns, Locale.ENGLISH, ids, loaderOf(items, calls), Item::id);

        assertEquals(List.of(200, 200, 50), calls.stream().map(List::size).toList());
        verify(entityManager, times(3)).clear();
        List<List<String>> rows = XlsxTestUtils.readText(bytes, 2);
        assertEquals(451, rows.size());
        assertEquals(ids.stream().map(String::valueOf).toList(),
                rows.subList(1, rows.size()).stream().map(row -> row.get(0)).toList());
    }

    // T-09
    @Test
    void noIds_producesHeadersOnly() throws Exception {
        List<List<Long>> calls = new ArrayList<>();

        byte[] bytes = service.generate("Sheet", columns, Locale.ENGLISH, List.of(), loaderOf(Map.of(), calls),
                Item::id);

        assertTrue(calls.isEmpty());
        assertEquals(List.of(List.of("Id", "Nombre")), XlsxTestUtils.readText(bytes, 2));
    }

    // T-10 (I-4)
    @Test
    void recordMissingFromTheBatch_failsTheExport() {
        Map<Long, Item> items = Map.of(1L, new Item(1L, "a"));

        assertThrows(IllegalStateException.class, () -> service.generate("Sheet", columns, Locale.ENGLISH,
                List.of(1L, 2L), loaderOf(items, new ArrayList<>()), Item::id));
    }
}
