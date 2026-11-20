package com.grash.utils;

import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INC-03: the XLSX writer of the exports keeps the text intact and never writes a formula.
 */
class XlsxTableWriterTest {

    private static byte[] write(List<String> headers, List<List<?>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxTableWriter writer = new XlsxTableWriter(out, "Sheet", headers);
        for (List<?> row : rows) writer.writeRow(row);
        writer.finish();
        return out.toByteArray();
    }

    // T-04
    @Test
    void spanishText_isKeptCharacterByCharacter() throws IOException {
        String text = "ñ Ñ á é í ó ú ü Ü ¿ ¡ « » \"comillas\" 'simples'\nsegunda línea\r\ntercera";
        byte[] bytes = write(List.of("Título"), List.of(List.of(text)));

        List<List<Cell>> rows = XlsxTestUtils.read(bytes, 1);
        assertEquals("Título", rows.get(0).get(0).getRawValue());
        assertEquals(CellType.STRING, rows.get(1).get(0).getType());
        assertEquals(text, rows.get(1).get(0).getRawValue());
    }

    // T-04
    @Test
    void valuesStartingWithFormulaCharacters_areStrings() throws IOException {
        List<String> values = List.of("=1+1", "+SUM(A1:A2)", "-2+3", "@cmd");
        byte[] bytes = write(List.of("A", "B", "C", "D"), List.of(values));

        List<Cell> row = XlsxTestUtils.read(bytes, 4).get(1);
        for (int i = 0; i < values.size(); i++) {
            assertEquals(CellType.STRING, row.get(i).getType());
            assertNull(row.get(i).getFormula());
            assertEquals(values.get(i), row.get(i).getRawValue());
        }
    }

    @Test
    void numbersAreNumbers_andNullsAreEmptyCells() throws IOException {
        byte[] bytes = write(List.of("A", "B", "C"), List.of(Arrays.asList(42L, null, 1.5)));

        List<Cell> row = XlsxTestUtils.read(bytes, 3).get(1);
        assertEquals(CellType.NUMBER, row.get(0).getType());
        assertEquals("42", row.get(0).getRawValue());
        assertTrue(row.get(1) == null || row.get(1).getType() == CellType.EMPTY);
        assertEquals(CellType.NUMBER, row.get(2).getType());
        assertEquals("1.5", row.get(2).getRawValue());
    }

    // T-09
    @Test
    void noRows_producesValidFileWithHeadersOnly() throws IOException {
        byte[] bytes = write(List.of("A", "B"), List.of());

        List<List<String>> rows = XlsxTestUtils.readText(bytes, 2);
        assertEquals(1, rows.size());
        assertEquals(List.of("A", "B"), rows.get(0));
    }

    @Test
    void rowCount_countsDataRows() throws IOException {
        XlsxTableWriter writer = new XlsxTableWriter(new ByteArrayOutputStream(), "Sheet", List.of("A"));
        writer.writeRow(List.of("x"));
        writer.writeRow(List.of("y"));
        writer.finish();

        assertEquals(2, writer.getRowCount());
    }

    // T-10 (I-5)
    @Test
    void writeFailure_propagates() {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };

        assertThrows(IOException.class, () -> {
            XlsxTableWriter writer = new XlsxTableWriter(failing, "Sheet", List.of("A"));
            writer.writeRow(List.of("x"));
            writer.flush();
            writer.finish();
        });
    }
}
