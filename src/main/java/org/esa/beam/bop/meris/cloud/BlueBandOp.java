/*
 * $Id: BlueBandOp.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
 *
 * Copyright (C) 2006 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.bop.meris.cloud;

import java.awt.Rectangle;
import java.util.Calendar;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.bc.ceres.core.ProgressMonitor;

public class BlueBandOp extends MerisBasisOp {

    public static final int FLAG_CLEAR = 1;
    public static final int FLAG_SNOW = 2;
    public static final int FLAG_DENSE_CLOUD = 4;
    public static final int FLAG_THIN_CLOUD = 8;

    public static final String BLUE_FLAG_BAND = "blue_cloud";

    private float[] toar1;
    private float[] toar7;
    private float[] toar10;
    private float[] toar11;
    private float[] toar13;

    // for bright sand test
    private float[] toar9;
    private float[] toar14;

    // for plausibility test
    private float[] latitude;
    private float[] altitude;
    
    private static final float D_BBT = 0.25f;
    private static final float D_ASS = 0.4f;

    private static final float R1_BBT = -1f;
    private static final float R2_BBT = 0.01f;
    private static final float R3_BBT = 0.1f;
    private static final float R4_BBT = 0.95f;
    private static final float R5_BBT = 0.05f;
    private static final float R6_BBT = 0.6f;
    private static final float R7_BBT = 0.45f;

    private static final float R1_ASS = 0.95f;
    private static final float R2_ASS = 0.05f;
    private static final float R3_ASS = 0.6f;
    private static final float R4_ASS = 0.05f;
    private static final float R5_ASS = 0.5f;

    // for plausibility test
    private static final float LAT_THR = 60.0f;
    private static final float ALT_THR = 1000.0f;
    public int month;

    // for bright sand test
    private static final float SLOPE2_LOW = 0.65f;
    private static final float SLOPE2_UPPER = 1.075f;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="toar")
    private Product brrProduct;
    @TargetProduct
    private Product targetProduct;
    
    public BlueBandOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
	protected Product initialize(ProgressMonitor pm) throws OperatorException {
        month = l1bProduct.getStartTime().getAsCalendar().get(Calendar.MONTH);

        targetProduct = createCompatibleProduct(l1bProduct, "MER_BLUEBAND_CLOUD", "MER_L2");
        
        // create and add the flags coding
        FlagCoding flagCoding = createFlagCoding();
        targetProduct.addFlagCoding(flagCoding);

        // create and add the flags dataset
        Band cloudFlagBand = targetProduct.addBand(BLUE_FLAG_BAND, ProductData.TYPE_UINT8);
        cloudFlagBand.setDescription("blue band cloud flags");
        cloudFlagBand.setFlagCoding(flagCoding);

        return targetProduct;
    }
    
    private void getSourceTiles(Rectangle rect) throws OperatorException {
        toar1 = (float[]) getTile(brrProduct.getBand("toar_1"), rect).getDataBuffer().getElems();
        toar7 = (float[]) getTile(brrProduct.getBand("toar_7"), rect).getDataBuffer().getElems();
        toar9 = (float[]) getTile(brrProduct.getBand("toar_9"), rect).getDataBuffer().getElems();
        toar10 = (float[]) getTile(brrProduct.getBand("toar_10"), rect).getDataBuffer().getElems();
        toar11 = (float[]) getTile(brrProduct.getBand("toar_11"), rect).getDataBuffer().getElems();
        toar13 = (float[]) getTile(brrProduct.getBand("toar_13"), rect).getDataBuffer().getElems();
        toar14 = (float[]) getTile(brrProduct.getBand("toar_14"), rect).getDataBuffer().getElems();

        if (l1bProduct.getProductType().equals(
                EnvisatConstants.MERIS_FSG_L1B_PRODUCT_TYPE_NAME)) {
            latitude = (float[]) getTile(l1bProduct.getBand("corr_latitude"), rect).getDataBuffer().getElems();
            altitude = (float[]) getTile(l1bProduct.getBand("altitude"), rect).getDataBuffer().getElems();
        } else {
            latitude = (float[]) getTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_LAT_DS_NAME), rect).getDataBuffer().getElems();
            altitude = (float[]) getTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME), rect).getDataBuffer().getElems();
        }
    }

    @Override
    public void computeBand(Raster targetRaster,
            ProgressMonitor pm) throws OperatorException {
    	
    	Rectangle rect = targetRaster.getRectangle();
        final int size = rect.height * rect.width;
        pm.beginTask("Processing frame...", size);
        try {
            getSourceTiles(rect);
            byte[] cloudFlagScanLine = (byte[]) targetRaster.getDataBuffer().getElems();

            boolean isSnowPlausible;
            boolean isBrightLand;
            for (int i = 0; i < size; i++) {
                final float po2 = toar11[i] / toar10[i];

                isSnowPlausible = isSnowPlausible(latitude[i], altitude[i]);
                isBrightLand = isBrightLand(toar9[i], toar14[i]);

                // blue band test
                if (toar1[i] >= D_BBT) {
                    final float ndvi = (toar13[i] - toar7[i])
                            / (toar13[i] + toar7[i]);
                    final float ndsi = (toar10[i] - toar13[i])
                            / (toar10[i] + toar13[i]);
                    // snow cover
                    if (((ndvi <= R1_BBT * ndsi + R2_BBT) || // snow test 1
                            (ndsi >= R3_BBT))
                            && (po2 <= R7_BBT)) {
                        cloudFlagScanLine[i] = FLAG_SNOW;
                    } else {
                        if ((toar13[i] <= R4_BBT * toar7[i] + R5_BBT) && // snow test 2
                                (toar13[i] <= R6_BBT) && (po2 <= R7_BBT)) {
                            cloudFlagScanLine[i] = FLAG_SNOW;
                        } else {
                            cloudFlagScanLine[i] = FLAG_DENSE_CLOUD;
                        }
                    }
                } else {
                    // altitude of scattering surface
                    // ToDo: introduce RR/FR specific altitude
                    if ((altitude[i] < 1700 && po2 >= D_ASS)
                            || (altitude[i] >= 1700 && po2 > 0.04 + (0.31746 + 0.00003814 * altitude[i]))) {
                        // snow cover
                        if ((toar13[i] <= R1_ASS * toar7[i] + R2_ASS) && // snow test 3
                                (toar13[i] <= R3_ASS)) {
                            if ((toar13[i] >= R4_ASS) && // snow test 4
                                    (toar7[i] >= R5_ASS)) {
                                cloudFlagScanLine[i] = FLAG_SNOW;
                            } else {
                                cloudFlagScanLine[i] = FLAG_CLEAR;
                            }
                        } else {
                            cloudFlagScanLine[i] = FLAG_THIN_CLOUD;
                        }
                    } else {
                        cloudFlagScanLine[i] = FLAG_CLEAR;
                    }
                }
                if (cloudFlagScanLine[i] == FLAG_SNOW
                        && (!isSnowPlausible || isBrightLand)) {
                    cloudFlagScanLine[i] = FLAG_CLEAR;
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    private boolean isBrightLand(float toar_9, float toar_14) {
        final float bsRatio = toar_9 / toar_14;
        return ((bsRatio >= SLOPE2_LOW) && (bsRatio <= SLOPE2_UPPER));
    }

    private boolean isSnowPlausible(float lat, float alt) {
        if (((lat <= LAT_THR && lat >= 0.) && (alt <= ALT_THR) && (month >= 4) && (month <= 10)) ||   // northern hemisphere
                ((-1 * lat <= LAT_THR && lat < 0.) && (alt <= ALT_THR) && (month >= 10) && (month <= 4)))
        {     // southern hemisphere
            return false;
        }
        return true;
    }

    private FlagCoding createFlagCoding() {
        MetadataAttribute cloudAttr;
        final FlagCoding flagCoding = new FlagCoding(BLUE_FLAG_BAND);
        flagCoding.setDescription("Blue Band - Cloud Flag Coding");

        cloudAttr = new MetadataAttribute("clear", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_CLEAR);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("snow", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_SNOW);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("dense_cloud", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_DENSE_CLOUD);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("thin_cloud", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_THIN_CLOUD);
        flagCoding.addAttribute(cloudAttr);

        return flagCoding;
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(BlueBandOp.class, "Meris.BlueBand");
        }
    }
}