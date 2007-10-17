/*
 * $Id: AlbedoUtils.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris;

import org.esa.beam.bop.meris.brr.dpm.Constants;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;

import java.text.SimpleDateFormat;
import java.util.Date;

public class AlbedoUtils {

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

    private AlbedoUtils() {
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

    /**
     * Computes the azimuth difference of the inversion model from the given
     *
     * @param vaa viewing azimuth angle [degree]
     * @param saa sun azimuth angle [degree]
     * @return the azimuth difference [degree]
     */
    public static double computeAzimuthDifference(final double vaa, final double saa) {
        double ada = vaa - saa;
        if (ada <= -180.0) {
            ada = +360.0 + ada;
        } else if (ada > +180.0) {
            ada = -360.0 + ada;
        }
        if (ada >= 0.0) {
            ada = +180.0 - ada;
        } else {
            ada = -180.0 - ada;
        }
        return ada;
    }
}
