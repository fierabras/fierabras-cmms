package com.grash.utils;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Writes one sheet of an XLSX file: a header row followed by data rows. Text is always written as a string cell
 * (never as a formula), numbers as numbers and nulls as empty cells. Write errors are propagated.
 */
public class XlsxTableWriter {

    private final Workbook workbook;
    private final Worksheet worksheet;
    private int rowCount;

    public XlsxTableWriter(OutputStream outputStream, String sheetName, List<String> headers) {
        this.workbook = new Workbook(outputStream, "Fierabras CMMS", null);
        this.worksheet = workbook.newWorksheet(sheetName);
        for (int column = 0; column < headers.size(); column++) {
            worksheet.value(0, column, headers.get(column));
        }
    }

    public void writeRow(List<?> values) {
        int row = rowCount + 1;
        for (int column = 0; column < values.size(); column++) {
            Object value = values.get(column);
            if (value == null) continue;
            if (value instanceof Number number) worksheet.value(row, column, number);
            else worksheet.value(row, column, value.toString());
        }
        rowCount++;
    }

    /**
     * Writes the rows set so far to the output, releasing them from memory.
     */
    public void flush() throws IOException {
        worksheet.flush();
    }

    public void finish() throws IOException {
        workbook.finish();
    }

    /**
     * Number of data rows written, without the header.
     */
    public int getRowCount() {
        return rowCount;
    }
}
