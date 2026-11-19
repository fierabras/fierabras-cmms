package com.grash.utils;

import java.util.function.Function;

/**
 * A column of a tabular export: the message key of its header and the extractor of its value.
 *
 * @param headerKey message key translated with the company locale
 * @param value     value of the cell for a row; a {@link Number} is written as a number, null as an empty cell and
 *                  anything else as text
 */
public record ExportColumn<T>(String headerKey, Function<T, Object> value) {
}
