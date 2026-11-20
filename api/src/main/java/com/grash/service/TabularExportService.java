package com.grash.service;

import com.grash.utils.ExportColumn;
import com.grash.utils.XlsxTableWriter;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic XLSX export of the records selected by a list query (INC-01, INC-03, RF-10).
 * <p>
 * The ids come from {@link com.grash.advancedsearch.OrderedIdQuery}, as for the list; the records are loaded and
 * written in batches, in the order of the ids. The file is only returned when every selected record was written.
 */
@Service
@RequiredArgsConstructor
public class TabularExportService {

    static final int BATCH_SIZE = 200;

    private final EntityManager entityManager;
    private final MessageSource messageSource;

    /**
     * Writes one row per id, in the order of {@code ids}.
     *
     * @param loader loads the records of a batch of ids, in any order
     * @throws IllegalStateException when a selected record cannot be loaded (I-4)
     * @throws IOException           when the file cannot be written (I-5)
     */
    public <T> byte[] generate(String sheetName, List<ExportColumn<T>> columns, Locale locale, List<Long> ids,
                               Function<List<Long>, Collection<T>> loader, Function<T, Long> idOf) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XlsxTableWriter writer = new XlsxTableWriter(outputStream, sheetName, columns.stream()
                .map(column -> messageSource.getMessage(column.headerKey(), null, locale)).toList());
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<Long> batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            Map<Long, T> loaded = loader.apply(batch).stream()
                    .collect(Collectors.toMap(idOf, Function.identity(), (first, second) -> first));
            for (Long id : batch) {
                T record = loaded.get(id);
                if (record == null) {
                    throw new IllegalStateException("Export aborted: the record " + id + " could not be loaded");
                }
                writer.writeRow(columns.stream().map(column -> column.value().apply(record)).toList());
            }
            writer.flush();
            entityManager.clear();
        }
        if (writer.getRowCount() != ids.size()) {
            throw new IllegalStateException("Export aborted: " + writer.getRowCount() + " rows written for "
                    + ids.size() + " records");
        }
        writer.finish();
        return outputStream.toByteArray();
    }
}
