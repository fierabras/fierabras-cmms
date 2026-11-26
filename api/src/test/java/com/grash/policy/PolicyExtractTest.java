package com.grash.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P-19 (E-4, E-8; CA-PPB-001, 078, 079; PPB-01, PPB-21, PPB-29): the versioned extract of the policy is well formed
 * and coherent, and the coherence check accepts a rule other than the one of its case only with a registered
 * {@code EX-nn}.
 */
class PolicyExtractTest {

    private static final String KEYS_HEADER = "child_table,child_column,parent_table,target_rule,relation,exception\n";
    private static final String TABLES = "table,class\npart,ROOT_ENTITY\npart_category,CATALOG\n"
            + "part_transaction,HISTORICAL_RECORD\ncompany,ORGANIZATION\n";
    private static final String NO_EXCEPTIONS = "id,rule,target,reason,approved_on,valid_until\n";
    private static final String EX_01 = NO_EXCEPTIONS
            + "EX-01,PPB-21,part.category_id,test of CA-PPB-078,25/11/2026,31/12/2026\n";

    @Test
    void theExtract_eachTableAndKeyOnce_withAllowedValues() {
        PolicyExtract extract = PolicyExtract.load();
        assertAll(
                () -> assertEquals(List.of(), extract.formatErrors()),
                // D-1: the rows of the CSV files (139 and 294) plus the approved row of D-3
                () -> assertEquals(139, extract.tables().size()),
                () -> assertEquals(295, extract.keys().size()));
    }

    @Test
    void theExtract_everyRuleIsTheOneOfItsCase() {
        assertEquals(List.of(), PolicyExtract.load().incoherent());
    }

    @Test
    void ca078_ruleOtherThanItsCase_citingARegisteredException_passes() {
        PolicyExtract extract = PolicyExtract.parse(KEYS_HEADER
                + "part,category_id,part_category,CASCADE,REFERENCE,EX-01\n", TABLES, EX_01);
        assertAll(
                () -> assertEquals(List.of(), extract.formatErrors()),
                () -> assertEquals(List.of(), extract.incoherent()));
    }

    @Test
    void ca079_theSameRowWithoutException_failsAndNamesTheRow() {
        PolicyExtract extract = PolicyExtract.parse(KEYS_HEADER
                + "part,category_id,part_category,CASCADE,REFERENCE,\n", TABLES, EX_01);
        assertEquals(List.of("part.category_id -> part_category: CASCADE is not the rule of its case"),
                extract.incoherent());
    }

    @Test
    void anExceptionThatIsNotRegistered_isReported() {
        PolicyExtract extract = PolicyExtract.parse(KEYS_HEADER
                + "part,category_id,part_category,CASCADE,REFERENCE,EX-01\n", TABLES, NO_EXCEPTIONS);
        assertEquals(List.of("foreign-keys.csv: part.category_id: cites EX-01, not registered"),
                extract.formatErrors());
    }

    @Test
    void unknownValues_repeatedRows_andTablesWithoutClass_areReported() {
        PolicyExtract extract = PolicyExtract.parse(KEYS_HEADER
                        + "part,category_id,part_category,NO ACTION,REFERENCE,\n"
                        + "part,category_id,part_category,NO ACTION,REFERENCE,\n"
                        + "part,company_id,company,CASCADE,OWNERSHIP,\n"
                        + "part,vendor_id,vendor,DELETE,REFERENCE,\n",
                TABLES + "part,ROOT_ENTITY\nfile,ARCHIVE\n", NO_EXCEPTIONS);
        assertEquals(List.of("tables.csv: part appears more than once", "tables.csv: file: unknown class ARCHIVE",
                        "foreign-keys.csv: part.category_id appears more than once",
                        "foreign-keys.csv: part.company_id: unknown relation OWNERSHIP",
                        "foreign-keys.csv: part.vendor_id: unknown rule DELETE",
                        "foreign-keys.csv: part.vendor_id: table vendor has no class"),
                extract.formatErrors());
    }

    @Test
    void theCasesOfPpb21() {
        PolicyExtract extract = PolicyExtract.parse(KEYS_HEADER
                        // case 2: a historical record to a parent other than the organization
                        + "part_transaction,part_id,part,CASCADE,COMPOSITION,\n"
                        // case 1 (D-P19-7): a historical record that belongs to the organization
                        + "part_transaction,company_id,company,CASCADE,COMPOSITION,\n"
                        // case 3
                        + "part,category_id,part_category,SET NULL,REFERENCE,\n"
                        // case 6: a technical key that spreads a deletion to business data
                        + "part,company_id,company,CASCADE,TECHNICAL,\n",
                TABLES, NO_EXCEPTIONS);
        assertEquals(List.of("part_transaction.part_id -> part: CASCADE is not the rule of its case",
                        "part.category_id -> part_category: SET NULL is not the rule of its case",
                        "part.company_id -> company: CASCADE is not the rule of its case"),
                extract.incoherent());
    }
}
