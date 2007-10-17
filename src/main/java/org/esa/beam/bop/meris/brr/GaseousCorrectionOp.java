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
import java.util.Map;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.operator.util.HelperFunctions;
import org.esa.beam.util.BitSetter;
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
    public static final String TG_BAND_PREFIX = "tg";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    public static final int F_DO_CORRECT = 0;
    public static final int F_SUN70 = 1;
    public static final int F_ORINP0 = 2;
    public static final int F_OROUT0 = 3;

    private DpmConfig dpmConfig;
    private L2AuxData auxData;

//    private FlagWrapper cloudFlags;
//    private FlagWrapper l1Flags;

    private Band flagBand;
//    private FlagWrapper gasFlags;
    private Band[] rhoNgBands;
    private Band[] tgBands;

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
    @Parameter
    boolean exportTg = false;

    @Override
    public Product initialize() throws OperatorException {
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
    	targetProduct = createCompatibleProduct(rhoToaProduct, "MER", "MER_L2");
    	
    	rhoNgBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            rhoNgBands[i] = targetProduct.addBand(RHO_NG_BAND_PREFIX + "_" + (i + 1), ProductData.TYPE_FLOAT32);
            ProductUtils.copySpectralAttributes(rhoToaProduct.getBandAt(i), rhoNgBands[i]);
            rhoNgBands[i].setNoDataValueUsed(true);
            rhoNgBands[i].setNoDataValue(BAD_VALUE);
        }

        flagBand = targetProduct.addBand(GAS_FLAGS, ProductData.TYPE_INT8);
        FlagCoding flagCoding = createFlagCoding();
        flagBand.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);
        
        if (exportTg) {
            tgBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        	for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
                tgBands[i] = targetProduct.addBand(TG_BAND_PREFIX + "_" + (i + 1), ProductData.TYPE_FLOAT32);
                tgBands[i].setNoDataValueUsed(true);
                tgBands[i].setNoDataValue(BAD_VALUE);
            }
        }
        return targetProduct;
    }

    private static FlagCoding createFlagCoding() {
        FlagCoding flagCoding = new FlagCoding(GAS_FLAGS);
        flagCoding.addFlag("F_DO_CORRECT", BitSetter.setFlag(0, F_DO_CORRECT), null);
        flagCoding.addFlag("F_SUN70", BitSetter.setFlag(0, F_SUN70), null);
        flagCoding.addFlag("F_ORINP0", BitSetter.setFlag(0, F_ORINP0), null);
        flagCoding.addFlag("F_OROUT0", BitSetter.setFlag(0, F_OROUT0), null);
        return flagCoding;
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle) throws OperatorException {
        ProgressMonitor pm = createProgressMonitor();
        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            Tile detectorIndex = getSourceTile(l1bProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME), rectangle);
			Tile sza = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle);
			Tile vza = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle);
			Tile altitude = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME), rectangle);
			Tile ecmwfOzone = getSourceTile(l1bProduct.getTiePointGrid("ozone"), rectangle);
			Tile l1Flags = getSourceTile(l1bProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME), rectangle);
			
			Tile[] rhoToa = new Tile[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
			for (int i1 = 0; i1 < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i1++) {
			    rhoToa[i1] = getSourceTile(rhoToaProduct.getBand(Rad2ReflOp.RHO_TOA_BAND_PREFIX + "_" + (i1 + 1)), rectangle);
			}
			
			Tile cloudFlags = getSourceTile(cloudProduct.getBand(CloudClassificationOp.CLOUD_FLAGS), rectangle);

            Tile gasFlags = targetTiles.get(flagBand);
            Tile[] rhoNg = new Tile[rhoNgBands.length];
            Tile[] tg = null;
            if (exportTg) {
                tg = new Tile[tgBands.length];
            }
            for (int i = 0; i < rhoNgBands.length; i++) {
                rhoNg[i] = targetTiles.get(rhoNgBands[i]);
                if (exportTg) {
                	tg[i] = targetTiles.get(tgBands[i]);
                }
            }

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y += Constants.SUBWIN_HEIGHT) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x += Constants.SUBWIN_WIDTH) {
                    final int xWinEnd = Math.min(rectangle.x + rectangle.width, x + Constants.SUBWIN_WIDTH) - 1;
                    final int yWinEnd = Math.min(rectangle.y + rectangle.height, y + Constants.SUBWIN_HEIGHT) - 1;
                    boolean correctPixel = false;
					boolean correctWaterPixel = false;
					double[] dSumrho = new double[L1_BAND_NUM]; /* accumulator for rho above water */
					
					for (int iy = y; iy <= yWinEnd; iy++) {
					    for (int ix = x; ix <= xWinEnd; ix++) {
					        if (!l1Flags.getSampleBit(ix, iy, L1_F_INVALID) &&
					                !cloudFlags.getSampleBit(ix, iy, CloudClassificationOp.F_CLOUD) &&
					                (correctWater || altitude.getSampleFloat(ix, iy) >= -50.0 || l1Flags.getSampleBit(ix, iy, L1_F_LAND))) {
					
					            correctPixel = true;
					            gasFlags.setSample(ix, iy, F_DO_CORRECT, true);
					
					            /* v4.2: average radiances for water pixels */
					            if (!l1Flags.getSampleBit(ix, iy, L1_F_LAND)) {
					                correctWaterPixel = true;
					                for (int bandId = bb753; bandId <= bb900; bandId++) {
					                    dSumrho[bandId] += rhoToa[bandId].getSampleFloat(ix, iy);
					                }
					            }
					        } else {
					        	writeBadValue(rhoNg, ix, iy);
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
					    double airMass0 = HelperFunctions.calculateAirMass(vza.getSampleFloat(x, y), sza.getSampleFloat(x, y));
					    trans_o3(airMass0, ecmwfOzone.getSampleFloat(x, y), T_o3);
					
					    /* process each pixel */
					    for (int iy = y; iy <= yWinEnd; iy++) {
					        for (int ix = x; ix <= xWinEnd; ix++) {
					            if (gasFlags.getSampleBit(ix, iy, F_DO_CORRECT)) {
					                double eta, x2;       /* band ratios eta, x2 */
					
					                /* test SZA - v4.2 */
					                if (sza.getSampleFloat(ix, iy) > auxData.TETAS_LIM) {
					                    gasFlags.setSample(ix, iy, F_SUN70, true);
					                }
					
					                /* gaseous transmittance gasCor : writes rho-ag field - v4.2 */
					                /* do band ratio for land pixels with full exception handling */
					                if (l1Flags.getSampleBit(ix, iy, L1_F_LAND)) {
					                    if ((rhoToa[bb753].getSampleFloat(ix, iy) > 0.) && (rhoToa[bb760].getSampleFloat(ix, iy) > 0.)) {
					                        eta = rhoToa[bb760].getSampleFloat(ix, iy) / rhoToa[bb753].getSampleFloat(ix, iy);    //o2
					                    } else {
					                        eta = 1.;
					                        gasFlags.setSample(ix, iy, F_ORINP0, true);
					                    }
					                    /* DPM #2.6.12.3-1 */
					                    if ((rhoToa[bb890].getSampleFloat(ix, iy) > 0.) && (rhoToa[bb900].getSampleFloat(ix, iy) > 0.)) {
					                        x2 = rhoToa[bb900].getSampleFloat(ix, iy) / rhoToa[bb890].getSampleFloat(ix, iy);   //h2o
					                    } else {
					                        x2 = 1.;
					                        gasFlags.setSample(ix, iy, F_ORINP0, true);
					                    }
					                } else { /* water pixels */
					                    eta = etaAverageForWater;
					                    x2 = x2AverageForWater;
					                    gasFlags.setSample(ix, iy, F_ORINP0, iOrinp0);
					                }
					                int status = 0;
					                status = gasCor.gas_correction(ix, iy, T_o3, eta, x2,
					                                               rhoToa,
					                                               detectorIndex.getSampleInt(ix, iy),
					                                               rhoNg,
					                                               tg,
					                                               cloudFlags.getSampleBit(ix, iy, CloudClassificationOp.F_PCD_POL_P));
					
					                /* exception handling */
					                gasFlags.setSample(ix, iy, F_OROUT0, status != 0);
					            } else {
					                writeBadValue(rhoNg, ix, iy);
					            }
					        }
					    }
					}
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
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

    private void writeBadValue(Tile[] rhoNg, int x, int y) {
        for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
            rhoNg[bandId].setSample(x, y, BAD_VALUE);
        }
    }


    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GaseousCorrectionOp.class, "Meris.GaseousCorrection");
        }
    }
}