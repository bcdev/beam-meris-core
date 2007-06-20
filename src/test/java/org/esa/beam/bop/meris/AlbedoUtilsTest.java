/*
 * $Id: AlbedoUtilsTest.java,v 1.1 2007/03/27 12:52:23 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.text.SimpleDateFormat;
import java.util.Date;

public class AlbedoUtilsTest extends TestCase {

    public AlbedoUtilsTest(String s) {
        super(s);
    }

    public static Test suite() {
        return new TestSuite(AlbedoUtilsTest.class);
    }

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

    public void test32BitFlagMethods() {
        int[] indexes = new int[]{
                0, 1, 4, 5,
                7, 8, 14, 16,
                17, 19, 25, 26,
                28, 31
        };
        int[] results = new int[]{
                1, 2, 16, 32,
                128, 256, 16384, 65536,
                131072, 524288, 33554432, 67108864,
                268435456, -2147483648
        };

        for (int i = 0; i < indexes.length; i++) {
            final int index = indexes[i];
            final int result = results[i];
            int flags = AlbedoUtils.setFlag(0, index);
            assertEquals("i = " + i, result, flags);
            assertEquals("i = " + i, true, AlbedoUtils.isFlagSet(flags, index));
        }

        int flags = 0;
        for (int i = 0; i < indexes.length; i++) {
            flags = AlbedoUtils.setFlag(flags, indexes[i]);
        }
        assertEquals(-1777647181, flags);
        for (int i = 0; i < 32; i++) {
            boolean expected = false;
            for (int j = 0; j < indexes.length; j++) {
                if (i == indexes[j]) {
                    expected = true;
                    break;
                }
            }
            assertEquals("i = " + i, expected, AlbedoUtils.isFlagSet(flags, i));
        }
    }

    public void test64BitFlagMethods() {
        int[] indexes = new int[]{
                0, 1, 7, 8,
                14, 16, 17, 26,
                18, 31, 32, 42,
                60, 61
        };
        long[] results = new long[]{
                1L, 2L, 128L, 256L,
                16384L, 65536L, 131072L, 67108864L,
                262144L, 2147483648L, 4294967296L, 4398046511104L,
                1152921504606846976L, 2305843009213693952L
        };

        for (int i = 0; i < indexes.length; i++) {
            final int index = indexes[i];
            final long result = results[i];
            long flags = AlbedoUtils.setFlag(0L, index);
            assertEquals("i = " + i, result, flags);
            assertEquals("i = " + i, true, AlbedoUtils.isFlagSet(flags, index));
        }

        long flags = 0;
        for (int i = 0; i < indexes.length; i++) {
            flags = AlbedoUtils.setFlag(flags, indexes[i]);
        }
        assertEquals(3458768918377087363L, flags);
        for (int i = 0; i < 64; i++) {
            boolean expected = false;
            for (int j = 0; j < indexes.length; j++) {
                if (i == indexes[j]) {
                    expected = true;
                    break;
                }
            }
            assertEquals("i = " + i, expected, AlbedoUtils.isFlagSet(flags, i));
        }
    }
}

