package com.grash.service;

import com.grash.dto.CurrencyPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.CurrencyMapper;
import com.grash.model.Currency;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CurrencyService {
    public static final String CURRENCY_IN_USE = "CURRENCY_IN_USE";
    private static final String CURRENCY_IN_USE_MESSAGE = "This currency is used by other records and cannot be "
            + "deleted";

    private final CurrencyRepository currencyRepository;
    private final CurrencyMapper currencyMapper;
    private final DeletionService deletionService;

    public Currency create(Currency Currency) {
        return currencyRepository.save(Currency);
    }

    public Currency update(Long id, CurrencyPatchDTO currencyPatchDTO) {
        if (currencyRepository.existsById(id)) {
            Currency currency = currencyRepository.findById(id).get();
            return currencyRepository.save(currencyMapper.updateCurrency(currency, currencyPatchDTO));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Currency> getAll() {
        return currencyRepository.findAll();
    }

    /**
     * P-11 (PPB-02, PPB-23): deletes a currency that no customer nor preferences use and records it without
     * organization, since a currency is global (D-14); otherwise 409 CURRENCY_IN_USE.
     */
    @Transactional
    public void delete(Currency currency, User actor) {
        Long id = currency.getId();
        deletionService.delete(new AuditTarget(EntityType.CURRENCY, id, null, currency.getName(), null), actor,
                AuditSource.API,
                DeletionRule.inUse(CURRENCY_IN_USE, CURRENCY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.CUSTOMER,
                                () -> deletionService.isCited("customer", "billing_currency_id", id))
                        .blockedBy(EntityType.GENERAL_PREFERENCES,
                                () -> deletionService.isCited("general_preferences", "currency_id", id)),
                () -> currencyRepository.deleteById(id));
    }

    public Optional<Currency> findById(Long id) {
        return currencyRepository.findById(id);
    }

    public Optional<Currency> findByCode(String code) {
        return currencyRepository.findByCode(code);
    }
}
