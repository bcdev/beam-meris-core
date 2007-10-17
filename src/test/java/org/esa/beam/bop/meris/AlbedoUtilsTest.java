/*
 * $Id: AlbedoUtilsTest.java,v 1.1 2007/03/27 12:52:23 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris;

import java.text.SimpleDateFormat;
import java.util.Date;

import junit.framework.TestCase;

public class AlbedoUtilsTest extends TestCase {

    public void testProductAquisitionDate() {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");
        final Date date = AlbedoUtils.getProductAquisitionDate(TestDataFactory.createL1bProduct());
        assertEquals("07-Jun-2004 14:50:15", dateFormat.format(date));
    }


    public void testSeasonalFactorComputation() {
        double meanEarthSunDist = 149.60e+06 * 1000;
        final double sunEarthDistanceSquare = meanEarthSunDist * meanEarthSunDist;

        final double vStart = AlbedoUtils.computeSeasonalFactor(0, sunEarthDistanceSquare);
        assertEquals(1, vStart, 0.05);
        assertTrue(vStart < 1.0);

        final double vMid = AlbedoUtils.computeSeasonalFactor(0.5 * 365, sunEarthDistanceSquare);
        assertEquals(1, vMid, 0.05);
        assertTrue(vMid > 1.0);

        final double vEnd = AlbedoUtils.computeSeasonalFactor(365, sunEarthDistanceSquare);
        assertEquals(1, vEnd, 0.05);
        assertTrue(vEnd < 1.0);
    }

    public void testRhoSpectralIndex() {

        assertTrue(AlbedoUtils.isValidRhoSpectralIndex(0));
        assertFalse(AlbedoUtils.isValidRhoSpectralIndex(10));
        assertTrue(AlbedoUtils.isValidRhoSpectralIndex(11));
        assertFalse(AlbedoUtils.isValidRhoSpectralIndex(14));

        assertFalse(AlbedoUtils.isValidRhoSpectralIndex(-1));
        assertFalse(AlbedoUtils.isValidRhoSpectralIndex(15));
    }
}

