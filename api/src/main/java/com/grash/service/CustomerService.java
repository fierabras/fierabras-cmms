package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.CustomerPatchDTO;
import com.grash.dto.CustomerPostDTO;
import com.grash.dto.cutomField.CustomFieldValuePostDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.CustomerMapper;
import com.grash.model.Customer;
import com.grash.model.Company;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.EntityType;
import com.grash.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomerService {
    public static final String CUSTOMER_IN_USE = "CUSTOMER_IN_USE";
    private static final String CUSTOMER_IN_USE_MESSAGE = "This customer is associated with closed or retired "
            + "records and cannot be deleted";

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final LicenseService licenseService;
    private final CustomFieldValueService customFieldValueService;
    private final DeletionService deletionService;


    public Customer create(Customer customer, Company company) {
        if (!licenseService.hasEntitlement(LicenseEntitlement.CUSTOMER_VENDOR))
            throw new CustomException("You need a license to create a contractor", HttpStatus.FORBIDDEN);
        if (customer instanceof CustomerPostDTO customerPostDTO) {
            customer = customerMapper.fromPostDto(customerPostDTO);
            if (customerPostDTO.getCustomFields() != null && !customerPostDTO.getCustomFields().isEmpty()) {
                setCustomerCustomFields(customer, customerPostDTO.getCustomFields(), company);
            }
        }
        return customerRepository.save(customer);
    }

    private void setCustomerCustomFields(Customer customer, List<CustomFieldValuePostDTO> customFieldValuePostDTOS,
                                         Company company) {
        customFieldValueService.setCustomFields(
                customer,
                customer.getCustomFieldValues(),
                customFieldValuePostDTOS,
                company,
                CustomFieldEntityType.CUSTOMER,
                cfv -> cfv.setCustomer(customer)
        );
    }

    public Customer update(Long id, CustomerPatchDTO customer, Company company) {
        if (customerRepository.existsById(id)) {
            Customer savedCustomer = customerRepository.findById(id).get();
            if (customer.getCustomFields() != null && !customer.getCustomFields().isEmpty()) {
                setCustomerCustomFields(savedCustomer, customer.getCustomFields(), company);
            }
            return customerRepository.save(customerMapper.updateCustomer(savedCustomer, customer));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Customer> getAll() {
        return customerRepository.findAll();
    }

    /**
     * P-12b (PPB-02, PPB-23): a customer has no references, only associations. One whose other end is closed or
     * retired blocks the deletion (409 CUSTOMER_IN_USE); otherwise the customer is deleted with its associations and
     * its custom field values, and recorded. These checks are queries of the rule: no foreign key backs them.
     */
    @Transactional
    public void delete(Customer customer, User actor) {
        Long id = customer.getId();
        deletionService.delete(new AuditTarget(EntityType.CUSTOMER, id, null, customer.getName(),
                        customer.getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(CUSTOMER_IN_USE, CUSTOMER_IN_USE_MESSAGE)
                        .blockedBy(EntityType.WORK_ORDER, () -> customerRepository.isOnClosedOrArchivedWorkOrder(id))
                        .blockedBy(EntityType.REQUEST, () -> customerRepository.isOnApprovedOrRejectedRequest(id))
                        .blockedBy(EntityType.ASSET, () -> customerRepository.isOnArchivedAsset(id))
                        .blockedBy(EntityType.PREVENTIVE_MAINTENANCE,
                                () -> customerRepository.isOnDisabledPreventiveMaintenance(id)),
                () -> customerRepository.deleteById(id));
    }

    public Optional<Customer> findById(Long id) {
        return customerRepository.findById(id);
    }

    public Collection<Customer> findByCompany(Long id) {
        return customerRepository.findByCompany_Id(id);
    }

    public Page<Customer> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<Customer> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return customerRepository.findAll(builder.build(), page);
    }

    public Optional<Customer> findByNameIgnoreCaseAndCompany(String name, Long companyId) {
        return customerRepository.findByNameIgnoreCaseAndCompany_Id(name, companyId).stream().findFirst();
    }

    public List<Customer> findByCompanyAndIdIn(Long id, List<Long> ids) {
        return customerRepository.findByCompany_IdAndIdIn(id, ids);
    }
}
