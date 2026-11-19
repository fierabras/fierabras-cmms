package com.grash.service;

import com.grash.utils.ExportColumn;
import com.grash.utils.XlsxTableWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic XLSX export of the records selected by a list query (INC-01, INC-03, RF-10).
 * <p>
 * The ids are read with one query using the specification and the sort of the list, then the records are loaded and
 * written in batches, in the order of the ids. The file is only returned when every selected record was written.
 */
@Service
@RequiredArgsConstructor
public class TabularExportService {

    static final int BATCH_SIZE = 200;

    private final EntityManager entityManager;
    private final MessageSource messageSource;

    /**
     * Ids of the records matching the specification, in the given order, without repetitions (the first occurrence
     * is kept: the joins of some filters repeat rows).
     */
    public <T> List<Long> findOrderedIds(Class<T> type, Specification<T> specification, Sort sort) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = criteriaBuilder.createQuery(Long.class);
        Root<T> root = query.from(type);
        if (specification != null) {
            Predicate predicate = specification.toPredicate(root, query, criteriaBuilder);
            if (predicate != null) query.where(predicate);
        }
        query.select(root.get("id"));
        query.orderBy(QueryUtils.toOrders(sort, root, criteriaBuilder));
        return new ArrayList<>(new LinkedHashSet<>(entityManager.createQuery(query).getResultList()));
    }

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
