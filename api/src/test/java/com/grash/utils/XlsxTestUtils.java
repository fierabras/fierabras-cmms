package com.grash.utils;

import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the first sheet of a generated XLSX file for the export tests.
 */
public final class XlsxTestUtils {

    private XlsxTestUtils() {
    }

    /**
     * Rows of the first sheet; each row holds one cell per column up to {@code columns}, null for a missing cell.
     */
    public static List<List<Cell>> read(byte[] bytes, int columns) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes));
             Stream<Row> rows = workbook.getFirstSheet().openStream()) {
            List<List<Cell>> result = new ArrayList<>();
            rows.forEach(row -> {
                List<Cell> cells = new ArrayList<>();
                for (int i = 0; i < columns; i++) cells.add(row.getCellCount() > i ? row.getCell(i) : null);
                result.add(cells);
            });
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Text of every cell, null for an empty cell.
     */
    public static List<List<String>> readText(byte[] bytes, int columns) {
        return read(bytes, columns).stream().map(row -> row.stream()
                .map(cell -> cell == null || cell.getRawValue() == null || cell.getRawValue().isEmpty() ? null :
                        cell.getRawValue()).toList()).toList();
    }

    /**
     * Number of columns of the header row.
     */
    public static int headerCount(byte[] bytes) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes));
             Stream<Row> rows = workbook.getFirstSheet().openStream()) {
            return rows.findFirst().map(Row::getCellCount).orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
