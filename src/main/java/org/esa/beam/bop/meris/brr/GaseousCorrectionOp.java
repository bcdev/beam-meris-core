/*
 * $Id: GaseousCorrectionOp.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
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
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.operator.util.HelperFunctions;
import org.esa.beam.util.FlagWrapper;
import org.esa.beam.util.ProductUtils;

import com.bc.ceres.core.ProgressMonitor;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:51:41 $
 */
public class GaseousCorrectionOp extends MerisBasisOp implements Constants {

    public static final String RHO_NG_BAND_PREFIX = "rho_ng";
    public static final String GAS_FLAGS = "gas_flags";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    public static final int F_DO_CORRECT = 0;
    public static final int F_SUN70 = 1;
    public static final int F_ORINP0 = 2;
    public static final int F_OROUT0 = 3;

    private DpmConfig dpmConfig;
    private L2AuxData auxData;

    private float[][] rhoToa;
    private short[] detectorIndex;
    private float[] sza;
    private float[] vza;
    private float[] altitude;
    private float[] ecmwfOzone;
    private FlagWrapper cloudFlags;
    private FlagWrapper l1Flags;

    private Band flagBand;
    private FlagWrapper gasFlags;
    private Band[] rhoNgBands;
    private float[][] rhoNg;

    private GaseousAbsorptionCorrection gasCor;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="rhotoa")
    private Product rhoToaProduct;
    @SourceProduct(alias="cloud")
    private Product cloudProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String configFile = MERIS_L2_CONF;
    @Parameter
    boolean correctWater = false;

    public GaseousCorrectionOp(OperatorSpi spi) {
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
            gasCor = new GaseousAbsorptionCorrection(auxData);
        } catch (Exception e) {
            throw new OperatorException("could not load L2Auxdata", e);
        }
        return createTargetProduct();
    }

    private Product createTargetProduct() {
        rhoNgBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        
    	targetProduct = createCompatibleProduct(rhoToaProduct, "MER", "MER_L2");
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            Band rhoToaBand = rhoToaProduct.getBandAt(i);

            rhoNgBands[i] = targetProduct.addBand(RHO_NG_BAND_PREFIX + "_" + (i + 1), ProductData.TYPE_FLOAT32);
            ProductUtils.copySpectralAttributes(rhoToaBand, rhoNgBands[i]);
            rhoNgBands[i].setNoDataValueUsed(true);
            rhoNgBands[i].setNoDataValue(BAD_VALUE);
        }

        flagBand = targetProduct.addBand(GAS_FLAGS, ProductData.TYPE_INT8);
        FlagCoding flagCoding = createFlagCoding();
        flagBand.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);
        
        rhoToa = new float[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS][0];
        rhoNg = new float[rhoNgBands.length][0];
        return targetProduct;
    }

    protected static FlagCoding createFlagCoding() {
        FlagCoding flagCoding = new FlagCoding(GAS_FLAGS);
        flagCoding.addFlag("F_DO_CORRECT", FlagWrapper.setFlag(0, F_DO_CORRECT), null);
        flagCoding.addFlag("F_SUN70", FlagWrapper.setFlag(0, F_SUN70), null);
        flagCoding.addFlag("F_ORINP0", FlagWrapper.setFlag(0, F_ORINP0), null);
        flagCoding.addFlag("F_OROUT0", FlagWrapper.setFlag(0, F_OROUT0), null);
        return flagCoding;
    }

    private void loadSourceTiles(Rectangle rectangle) throws OperatorException {
        
        detectorIndex = (short[]) getTile(
                l1bProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME), rectangle).getDataBuffer().getElems();
        sza = (float[]) getTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        vza = (float[]) getTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        altitude = (float[]) getTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME), rectangle).getDataBuffer().getElems();
        ecmwfOzone = (float[]) getTile(l1bProduct.getTiePointGrid("ozone"), rectangle).getDataBuffer().getElems();
        l1Flags = new FlagWrapper.Byte((byte[]) getTile(l1bProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME), rectangle).getDataBuffer().getElems());

        
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            rhoToa[i] = (float[]) getTile(rhoToaProduct.getBand(Rad2ReflOp.RHO_TOA_BAND_PREFIX + "_" + (i + 1)), rectangle).getDataBuffer().getElems();
        }

        cloudFlags = new FlagWrapper.Short((short[])getTile(cloudProduct.getBand(CloudClassificationOp.CLOUD_FLAGS), rectangle).getDataBuffer().getElems());
    }

    @Override
    public void computeTiles(Rectangle rectangle, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            loadSourceTiles(rectangle);

            gasFlags = new FlagWrapper.Byte((byte[]) getTile(flagBand, rectangle).getDataBuffer().getElems());
            for (int i = 0; i < rhoNgBands.length; i++) {
                rhoNg[i] = (float[]) getTile(rhoNgBands[i], rectangle).getDataBuffer().getElems();
            }

            for (int iPL1 = rectangle.y; iPL1 < rectangle.y + rectangle.height; iPL1 += Constants.SUBWIN_HEIGHT) {
                for (int iPC1 = rectangle.x; iPC1 < rectangle.x + rectangle.width; iPC1 += Constants.SUBWIN_WIDTH) {
                    final int iPC2 = Math.min(rectangle.x + rectangle.width, iPC1 + Constants.SUBWIN_WIDTH) - 1;
                    final int iPL2 = Math.min(rectangle.y + rectangle.height, iPL1 + Constants.SUBWIN_HEIGHT) - 1;
                    gaseousCorrection(iPC1, iPC2, iPL1, iPL2, rectangle);
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }

    public void gaseousCorrection(int ic0, int ic1, int il0, int il1, Rectangle rectangle) {
        boolean correctPixel = false;
        boolean correctWaterPixel = false;
        double[] dSumrho = new double[L1_BAND_NUM]; /* accumulator for rho above water */

        for (int il = il0; il <= il1; il++) {
            for (int ic = ic0; ic <= ic1; ic++) {
                int index = TileRectCalculator.convertToIndex(ic, il, rectangle);

                if (!l1Flags.isSet(index, L1_F_INVALID) &&
                        !cloudFlags.isSet(index, CloudClassificationOp.F_CLOUD) &&
                        (correctWater || altitude[index] >= -50.0 || l1Flags.isSet(index, L1_F_LAND))) {

                    correctPixel = true;
                    gasFlags.set(index, F_DO_CORRECT);

                    /* v4.2: average radiances for water pixels */
                    if (!l1Flags.isSet(index, L1_F_LAND)) {
                        correctWaterPixel = true;
                        for (int bandId = bb753; bandId <= bb900; bandId++) {
                            dSumrho[bandId] += rhoToa[bandId][index];
                        }
                    }
                } else {
                    writeBadValue(index);
                }
            }
        }

        if (correctPixel) {
            /* v4.2 average TOA radiance */
            double etaAverageForWater = 0.;
            double x2AverageForWater = 0.;
            boolean iOrinp0 = false;
            if (correctWaterPixel) {
                if ((dSumrho[bb753] > 0.) && (dSumrho[bb760] > 0.)) {
                    etaAverageForWater = dSumrho[bb760] / dSumrho[bb753];
                } else {
                    iOrinp0 = true;
                    etaAverageForWater = 1.;
                }

                if ((dSumrho[bb890] > 0.) && (dSumrho[bb900] > 0.)) {
                    x2AverageForWater = dSumrho[bb900] / dSumrho[bb890];
                } else {
                    iOrinp0 = true;
                    x2AverageForWater = 1.;
                }
            }

            /* V.2 APPLY GASEOUS ABSORPTION CORRECTION - DPM Step 2.6.12 */

            /* ozone transmittance on 4x4 window - step 2.6.12.1 */
            double[] T_o3 = new double[L1_BAND_NUM];   /* ozone transmission */
            int index0 = TileRectCalculator.convertToIndex(ic0, il0, rectangle);
            double airMass0 = HelperFunctions.calculateAirMass(vza[index0], sza[index0]);
            trans_o3(airMass0, ecmwfOzone[index0], T_o3);

            /* process each pixel */
            for (int il = il0; il <= il1; il++) {
                for (int ic = ic0; ic <= ic1; ic++) {
                    int index = TileRectCalculator.convertToIndex(ic, il, rectangle);
                    if (gasFlags.isSet(index, F_DO_CORRECT)) {
                        double eta, x2;       /* band ratios eta, x2 */

                        /* test SZA - v4.2 */
                        if (sza[index] > auxData.TETAS_LIM) {
                            gasFlags.set(index, F_SUN70);
                        }

                        /* gaseous transmittance gasCor : writes rho-ag field - v4.2 */
                        /* do band ratio for land pixels with full exception handling */
                        if (l1Flags.isSet(index, L1_F_LAND)) {
                            if ((rhoToa[bb753][index] > 0.) && (rhoToa[bb760][index] > 0.)) {
                                eta = rhoToa[bb760][index] / rhoToa[bb753][index];    //o2
                            } else {
                                eta = 1.;
                                gasFlags.set(index, F_ORINP0);
                            }
                            /* DPM #2.6.12.3-1 */
                            if ((rhoToa[bb890][index] > 0.) && (rhoToa[bb900][index] > 0.)) {
                                x2 = rhoToa[bb900][index] / rhoToa[bb890][index];   //h2o
                            } else {
                                x2 = 1.;
                                gasFlags.set(index, F_ORINP0);
                            }
                        } else { /* water pixels */
                            eta = etaAverageForWater;
                            x2 = x2AverageForWater;
                            gasFlags.set(index, F_ORINP0, iOrinp0);
                        }
                        int status = 0;
                        status = gasCor.gas_correction(index, T_o3, eta, x2,
                                                       rhoToa,
                                                       detectorIndex[index],
                                                       rhoNg,
                                                       cloudFlags.isSet(index, CloudClassificationOp.F_PCD_POL_P));

                        /* exception handling */
                        gasFlags.set(index, F_OROUT0, status != 0);
                    } else {
                        writeBadValue(index);
                    }
                }
            }
        }
    }

    /**
     * Computes the ozone transmittance for a given pixel. This routine should be called every 4x4 pixels.
     * <p/>
     * Reference: DPM equation #2.6.12.1-2<br>
     * Uses: <br>
     * {@link L2AuxData#tauO3_norm variables.tauO3_norm} <br>
     *
     * @param airMass air mass
     * @param ozone   total ozone contents
     * @param T_o3    ozone optical thickness in 15 bands
     */
    private void trans_o3(double airMass, double ozone, double[] T_o3) {
        for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
            /* DPM #2.6.12.1-2 */
            T_o3[bandId] = Math.exp(-ozone / 1000.0 * airMass * auxData.tauO3_norm[bandId]);
        }
    }

    private void writeBadValue(int index) {
        for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
            rhoNg[bandId][index] = BAD_VALUE;
        }
    }


    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(GaseousCorrectionOp.class, "Meris.GaseousCorrection");
        }
    }
}