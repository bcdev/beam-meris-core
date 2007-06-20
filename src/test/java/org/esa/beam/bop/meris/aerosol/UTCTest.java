/*
 * $Id: UTCTest.java,v 1.1 2007/03/27 12:52:23 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris.aerosol;

import junit.framework.TestCase;
import org.esa.beam.framework.datamodel.ProductData;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class UTCTest extends TestCase {
    public void testUTC() {
        final Calendar calendar = getCalendar();
        calendar.set(2005, 5, 7, 18, 30, 15);
        final DateFormat dateTimeFormat = getDateTimeFormat();
        assertEquals("07.06.2005 18:30:15", dateTimeFormat.format(calendar.getTime()));
    }

    public static DateFormat getDateTimeFormat() {
        final Calendar calendar = getCalendar();
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ENGLISH);
        dateFormat.setCalendar(calendar);
        return dateFormat;
    }

    public static Calendar getCalendar() {
        return ProductData.UTC.createCalendar();
    }
}
