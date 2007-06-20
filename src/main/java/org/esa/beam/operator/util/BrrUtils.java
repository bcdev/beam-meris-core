/*
 * $Id: BrrUtils.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.operator.util;

import org.esa.beam.bop.meris.brr.Constants;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;

import java.text.SimpleDateFormat;
import java.util.Date;

public class BrrUtils {

    /**
     * Computes the mean of the sensing start and end date of the given MERIS L1b product and returns it as product
     * aquisition date.
     * <p/>
     * todo - BEAM 3 - move this general useful method to a utility class, e.g. ProductUtils
     *
     * @return the aquisition date, or <code>null</code> if it can not be retrieved
     */
    public static Date getProductAquisitionDate(Product l1bProduct) {
        Date aquisitionDate = null;
        if (l1bProduct != null) {
            final MetadataElement mph = l1bProduct.getMetadataRoot().getElement("MPH");
            final String sensingStartString = mph.getAttributeString("SENSING_START", null);
            final String sensingStopString = mph.getAttributeString("SENSING_STOP", null);
            final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");
            try {
                final long sensingStartMillis = dateFormat.parse(sensingStartString).getTime();
                final long sensingStopMillis = dateFormat.parse(sensingStopString).getTime();
                final long sensingCenterMillis = Math.round(0.5 * sensingStartMillis + 0.5 * sensingStopMillis);
                aquisitionDate = new Date(sensingCenterMillis);
            } catch (java.text.ParseException e) {
            }
        }
        return aquisitionDate;
    }

    private BrrUtils() {
    }

    public static double computeSeasonalFactor(double daysSince2000, double sunEarthDistanceSquare) {
        // Semi-major axis of ellipse Earth orbit around Sun in meters
        final double a = 149597870.0 * 1000.0;
        // Eccentricity of ellipse Earth orbit around Sun
        final double e = 0.017;
        // Perihelion in 2000 was the 03.01.2000 05:00
        final double daysToPerihelionIn2000 = 3.0 + 5.0 / 24.0;
        final double daysOfYear = 365.25;
        final double theta = 2 * Math.PI * ((daysSince2000 - daysToPerihelionIn2000) / daysOfYear);
        final double r = a * (1.0 - e * e) / (1.0 + e * Math.cos(theta));
        return r * r / sunEarthDistanceSquare;
    }

    /**
     * Tests if a flag with the given index is set in a 32-bit collection of flags.
     *
     * @param flags    a collection of a maximum of 32 flags
     * @param bitIndex the zero-based index of the flag to be tested
     * @return <code>true</code> if the flag is set
     */
//    public static boolean isFlagSet(int flags, int bitIndex) {
//        return (flags & (1 << bitIndex)) != 0;
//    }

    /**
     * Tests if a flag with the given index is set in a 64-bit collection of flags.
     *
     * @param flags    a collection of a maximum of 64 flags
     * @param bitIndex the zero-based index of the flag to be tested
     * @return <code>true</code> if the flag is set
     */
//    public static boolean isFlagSet(long flags, int bitIndex) {
//        return (flags & (1L << bitIndex)) != 0;
//    }

    /**
     * Sets a flag with the given index in a 32-bit collection of flags.
     *
     * @param flags    a collection of a maximum of 32 flags
     * @param bitIndex the zero-based index of the flag to be set
     * @return the collection of flags with the given flag set
     */
//    public static int setFlag(int flags, int bitIndex) {
//        return setFlag(flags, bitIndex, true);
//    }

    /**
     * Sets a flag with the given index in a 64-bit collection of flags.
     *
     * @param flags    a collection of a maximum of 64 flags
     * @param bitIndex the zero-based index of the flag to be set
     * @return the collection of flags with the given flag set
     */
//    public static long setFlag(long flags, int bitIndex) {
//        return setFlag(flags, bitIndex, true);
//    }

    /**
     * Sets a flag with the given index in a 32-bit collection of flags if a given condition is <code>true</code>.
     *
     * @param flags    a collection of a maximum of 32 flags
     * @param bitIndex the zero-based index of the flag to be set
     * @param cond     the condition
     * @return the collection of flags with the given flag possibly set
     */
//    public static int setFlag(int flags, int bitIndex, boolean cond) {
//        return cond ? (flags | (1 << bitIndex)) : flags;
//    }

    /**
     * Sets a flag with the given index in a 64-bit collection of flags if a given condition is <code>true</code>.
     *
     * @param flags    a collection of a maximum of 64 flags
     * @param bitIndex the zero-based index of the flag to be set
     * @param cond     the condition
     * @return the collection of flags with the given flag possibly set
     */
//    public static long setFlag(long flags, int bitIndex, boolean cond) {
//        return cond ? (flags | (1L << bitIndex)) : flags;
//    }
    public static boolean isValidRhoSpectralIndex(int i) {
        return i >= Constants.bb1 && i < Constants.bb15 && i != Constants.bb11;
    }

    public static boolean isProductRR(Product product) {
        return product.getProductType().indexOf("_RR_") > 0;
    }

    public static boolean isProductFR(Product product) {
        return (product.getProductType().indexOf("_FR") > 0) ||
                (product.getProductType().indexOf("_FS") > 0);
    }
}
