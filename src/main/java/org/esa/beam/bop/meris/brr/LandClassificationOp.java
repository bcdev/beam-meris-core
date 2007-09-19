/*
 * $Id: LandClassificationOp.java,v 1.2 2007/05/08 08:03:52 marcoz Exp $
 *
 * Copyright (C) 2007 by Brockmann Consult (info@brockmann-consult.de)
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
package org.esa.beam.bop.meris.brr;

import java.awt.Rectangle;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.operator.util.HelperFunctions;
import org.esa.beam.util.FlagWrapper;
import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.2 $ $Date: 2007/05/08 08:03:52 $
 */
public class LandClassificationOp extends MerisBasisOp implements Constants {

    public static final String LAND_FLAGS = "land_classif_flags";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    public static final int F_MEGLINT = 0;
    public static final int F_LOINLD = 1;
    public static final int F_ISLAND = 2;
    public static final int F_LANDCONS = 3;


    private DpmConfig dpmConfig;
    private L2AuxData auxData;

    private float[][] rhoNg;
    private float[] sza;
    private float[] vza;
    private float[] saa;
    private float[] vaa;
    private float[] windu;
    private float[] windv;
    private FlagWrapper l1Flags;

    private FlagWrapper landFlags;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="gascor")
    private Product gasCorProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String configFile = MERIS_L2_CONF;

    public LandClassificationOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }
        try {
            auxData = new L2AuxData(dpmConfig, l1bProduct);
        } catch (Exception e) {
            throw new OperatorException("could not load L2Auxdata", e);
        }
        return createTargetProduct();
    }

    private Product createTargetProduct() {
        targetProduct = createCompatibleProduct(l1bProduct, "MER", "MER_L2");

        Band band = targetProduct.addBand(LAND_FLAGS, ProductData.TYPE_INT8);
        FlagCoding flagCoding = createFlagCoding();
        band.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);
        
        rhoNg = new float[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS][0];
        return targetProduct;
    }

    protected static FlagCoding createFlagCoding() {
        FlagCoding flagCoding = new FlagCoding(LAND_FLAGS);
        flagCoding.addFlag("F_MEGLINT", FlagWrapper.setFlag(0, F_MEGLINT), null);
        flagCoding.addFlag("F_LOINLD", FlagWrapper.setFlag(0, F_LOINLD), null);
        flagCoding.addFlag("F_ISLAND", FlagWrapper.setFlag(0, F_ISLAND), null);
        flagCoding.addFlag("F_LANDCONS", FlagWrapper.setFlag(0, F_LANDCONS), null);
        return flagCoding;
    }

    private void loadSourceTiles(Rectangle rectangle) throws OperatorException {
    	sza = (float[]) getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        vza = (float[]) getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        saa = (float[]) getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle).getDataBuffer().getElems();
        vaa = (float[]) getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle).getDataBuffer().getElems();
        windu = (float[]) getRaster(l1bProduct.getTiePointGrid("zonal_wind"), rectangle).getDataBuffer().getElems();
        windv = (float[]) getRaster(l1bProduct.getTiePointGrid("merid_wind"), rectangle).getDataBuffer().getElems();
        l1Flags = new FlagWrapper.Byte((byte[])getRaster(l1bProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME), rectangle).getDataBuffer().getElems());

        
        for (int i = 0; i < rhoNg.length; i++) {
            rhoNg[i] = (float[]) getRaster(gasCorProduct.getBand(GaseousCorrectionOp.RHO_NG_BAND_PREFIX + "_" + (i + 1)), rectangle).getDataBuffer().getElems();
        }
    }

    @Override
    public void computeBand(Band band, Raster targetRaster, ProgressMonitor pm) throws OperatorException {
    	
    	Rectangle rectangle = targetRaster.getRectangle();
        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            loadSourceTiles(rectangle);
            landFlags = new FlagWrapper.Byte((byte[]) targetRaster.getDataBuffer().getElems());

            for (int iPL1 = rectangle.y; iPL1 < rectangle.y + rectangle.height; iPL1 += Constants.SUBWIN_HEIGHT) {
                for (int iPC1 = rectangle.x; iPC1 < rectangle.x + rectangle.width; iPC1 += Constants.SUBWIN_WIDTH) {
                    final int iPC2 = Math.min(rectangle.x + rectangle.width, iPC1 + Constants.SUBWIN_WIDTH) - 1;
                    final int iPL2 = Math.min(rectangle.y + rectangle.height, iPL1 + Constants.SUBWIN_HEIGHT) - 1;
                    landWaterReclassification(iPC1, iPC2, iPL1, iPL2, rectangle);
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }

    public void landWaterReclassification(int ic0, int ic1, int il0, int il1, Rectangle rectangle) {
    	int index0 = TileRectCalculator.convertToIndex(ic0, il0, rectangle);

    	/* v7: compute Glint reflectance here (only if there are water/land pixels) */
    	/* first wind modulus at window corner */
    	double windm = 0.0;
    	windm += windu[index0] * windu[index0];
    	windm += windv[index0] * windv[index0];
    	windm = Math.sqrt(windm);
    	/* then wind azimuth */
    	double phiw = azimuth(windu[index0], windv[index0]);
    	/* and "scattering" angle */
    	double chiw = MathUtils.RTOD * (Math.acos(Math.cos(saa[index0] - phiw)));
    	double deltaAzimuth = HelperFunctions.computeAzimuthDifference(vaa[index0], saa[index0]);
    	/* allows to retrieve Glint reflectance for wurrent geometry and wind */
    	double rhoGlint = glintRef(sza[index0], vza[index0], deltaAzimuth, windm, chiw);
    	
    	FractIndex[] r7thresh_Index = FractIndex.createArray(3);  /* v4.4 */
    	/* set up threshold for land-water discrimination */
    	Interp.interpCoord(sza[index0], auxData.r7thresh.getTab(0), r7thresh_Index[0]);
    	Interp.interpCoord(vza[index0], auxData.r7thresh.getTab(1), r7thresh_Index[1]);
    	/* take azimuth difference into account - v4.4 */
    	Interp.interpCoord(deltaAzimuth, auxData.r7thresh.getTab(2), r7thresh_Index[2]);
    	/* DPM #2.6.26-1a */
    	final double r7thresh_val = Interp.interpolate(auxData.r7thresh.getJavaArray(), r7thresh_Index);
    	final double r13thresh_val = Interp.interpolate(auxData.r13thresh.getJavaArray(), r7thresh_Index);
    	
    	/* process each pixel */
    	for (int il = il0; il <= il1; il++) {
    		for (int ic = ic0; ic <= ic1; ic++) {
    			int index = TileRectCalculator.convertToIndex(ic, il, rectangle);
    			/* Land /Water re-classification - v4.2, updated for v7 */
    			/* DPM step 2.6.26 */
    			
    			boolean is_water = false;
    			boolean is_land = false;
    			int b_thresh;           /*added V7 to manage 2 bands reclassif threshold LUT */
    			double a_thresh;  /*added V7 to manage 2 bands reclassif threshold LUT */
    			double rThresh;
    			
    			/* test if pixel is water */
    			b_thresh = auxData.lap_b_thresh[0];
    			a_thresh = auxData.alpha_thresh[0];
    			is_water = inland_waters(r7thresh_val, index, b_thresh, a_thresh);
    			/* the is_water flag is available in the output product as F_LOINLD */
    			
    			/* test if pixel is land */
    			final float thresh_medg = 0.2f;
    			boolean isGlint = (rhoGlint >= thresh_medg * rhoNg[bb865][index]);
    			if (isGlint) {
    				landFlags.set(index, F_MEGLINT);
    				b_thresh = auxData.lap_b_thresh[0];
    				a_thresh = auxData.alpha_thresh[0];
    				rThresh = r7thresh_val;
    			} else {
    				b_thresh = auxData.lap_b_thresh[1];
    				a_thresh = auxData.alpha_thresh[1];
    				rThresh = r13thresh_val;
    			}
    			is_land = island(rThresh, index, b_thresh, a_thresh);
    			/* the is_land flag is available in the output product as F_ISLAND */
    			
    			// DPM step 2.6.26-7
    			// DPM #2.6.26-6
    			// TODO: reconsider to user the is_land flag in decision; define logic in ambiguous cases!
    			// the water test is less severe than the land test
    			boolean is_land_consolidated = !is_water;
    			// the land test is more severe than the water test
    			if (isGlint && !l1Flags.isSet(index, L1_F_LAND)) {
    				is_land_consolidated = is_land;
    			}
    			landFlags.set(index, F_LANDCONS, is_land_consolidated);
    		}
        }
    }

    /**
     * Function glint_ref: interpolate glint reflectance from look-up table
     * inputs:
     * output:
     * return value:
     * success code: 0 OK
     * Reference: DPM L2 section 7.3.1 step 2.6.5.1.1
     * called by:
     * confidence
     * calls:
     * InterpCoord
     * GenericInterp
     */
    private double glintRef(double thetas, double thetav, double delta, double windm, double chiw) {
        FractIndex[] rogIndex = FractIndex.createArray(5);

        Interp.interpCoord(chiw, auxData.rog.getTab(0), rogIndex[0]);
        Interp.interpCoord(thetav, auxData.rog.getTab(1), rogIndex[1]);
        Interp.interpCoord(delta, auxData.rog.getTab(2), rogIndex[2]);
        Interp.interpCoord(windm, auxData.rog.getTab(3), rogIndex[3]);
        Interp.interpCoord(thetas, auxData.rog.getTab(4), rogIndex[4]);
        double rhoGlint = Interp.interpolate(auxData.rog.getJavaArray(), rogIndex);
        return rhoGlint;
    }

    /**
     * Function azimuth: compute the azimuth (in local topocentric coordinates)
     * of a vector
     * inputs:
     * x: component of vector along X (Eastward parallel) axis
     * y: component of vector along Y (Northward meridian) axis
     * return value:
     * azimuth of vector in degrees
     * references:
     * mission convention document PO-IS-ESA-GS-0561, para 6.3.4
     * L2 DPM step 2.6.5.1.1
     */
    private double azimuth(double x, double y) {
        if (y > 0.0) {
            // DPM #2.6.5.1.1-1
            return (MathUtils.RTOD * Math.atan(x / y));
        } else if (y < 0.0) {
            // DPM #2.6.5.1.1-5
            return (180.0 + MathUtils.RTOD * Math.atan(x / y));
        } else {
            // DPM #2.6.5.1.1-6
            return (x >= 0.0 ? 90.0 : 270.0);
        }
    }

    /**
     * Detects inland water. Called by {@link #pixel_classification}.
     * Reference: DPM L2 step 2.6.11. Uses<br>
     * {@link L2AuxData#lap_beta_l}
     *
     * @param r7thresh_val threshold at 665nm
     * @param pixel        the pixel in order to read from
     *                     {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#rho_ag pixel.rho_ag}
     *                     and write to
     *                     {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#l2flags pixel.l2flags}
     * @param b_thresh
     * @param a_thresh
     * @return inland water flag
     */
    private boolean inland_waters(double r7thresh_val, int index, int b_thresh, double a_thresh) {
        /* DPM #2.6.26-4 */
        boolean status = (rhoNg[b_thresh][index] <= a_thresh * r7thresh_val) &&
                (auxData.lap_beta_l * rhoNg[bb865][index] < rhoNg[bb665][index]);

        landFlags.set(index, F_LOINLD, status);
        return status;
    }

    private boolean island(double r7thresh_val, int index, int b_thresh, double a_thresh) {
        boolean status = (rhoNg[b_thresh][index] > a_thresh * r7thresh_val) &&
                (auxData.lap_beta_w * rhoNg[bb865][index] > rhoNg[bb665][index]);

        landFlags.set(index, F_ISLAND, status);
        return status;
    }


    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(LandClassificationOp.class, "Meris.LandClassification");
        }
    }
}
