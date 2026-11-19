package com.grash.advancedsearch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ordered sequence of distinct ids selected by a list query, shared by the paged lists and their exports
 * (INC-01): both read the same records in the same order.
 */
@Component
@RequiredArgsConstructor
public class OrderedIdQuery {

    private final EntityManager entityManager;

    /**
     * Ids of the records matching the specification, in the given order, without repetitions (the first occurrence
     * is kept: the joins of some filters and scopes repeat rows).
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
     * One page of the list: the page is cut from the distinct ids, so that every page but the last is full and the
     * total is the number of records, whatever rows the joins repeat.
     *
     * @param loader loads the records of the ids of the page, in any order
     */
    public <T> Page<T> findPage(Class<T> type, Specification<T> specification, SearchCriteria searchCriteria,
                                Function<List<Long>, Collection<T>> loader, Function<T, Long> idOf) {
        Pageable pageable = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.sortWithIdTiebreak());
        List<Long> ids = findOrderedIds(type, specification, pageable.getSort());
        if (pageable.getOffset() >= ids.size()) return new PageImpl<>(List.of(), pageable, ids.size());
        List<Long> pageIds = ids.subList((int) pageable.getOffset(),
                (int) Math.min(pageable.getOffset() + pageable.getPageSize(), ids.size()));
        Map<Long, T> loaded = loader.apply(pageIds).stream()
                .collect(Collectors.toMap(idOf, Function.identity(), (first, second) -> first));
        List<T> content = pageIds.stream().map(loaded::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(content, pageable, ids.size());
    }
}
