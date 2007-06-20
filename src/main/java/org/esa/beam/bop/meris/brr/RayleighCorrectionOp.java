/*
 * $Id: RayleighCorrectionOp.java,v 1.3 2007/04/27 15:30:03 marcoz Exp $
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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.support.CachingOperator;
import org.esa.beam.framework.gpf.support.ProductDataCache;
import org.esa.beam.framework.gpf.support.SourceDataRetriever;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.operator.util.HelperFunctions;
import org.esa.beam.util.FlagWrapper;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import java.awt.*;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.3 $ $Date: 2007/04/27 15:30:03 $
 */
public class RayleighCorrectionOp extends CachingOperator implements Constants {

    public static final String BRR_BAND_PREFIX = "brr";
    public static final String RAY_CORR_FLAGS = "ray_corr_flags";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    private SourceDataRetriever dataRetriever;
    private DpmConfig dpmConfig;
    protected L2AuxData auxData;

    protected float[][] rhoNg;
    protected float[] sza;
    protected float[] vza;
    protected float[] saa;
    protected float[] vaa;
    protected float[] altitude;
    protected float[] ecmwfPressure;
    protected boolean[] isLandCons;

    private Band[] brrBands;
    private Band flagBand;
    protected float[][] brr;
    protected FlagWrapper brrFlags;
    protected RayleighCorrection rayleighCorrection;

    @Parameter
    private String configFile = MERIS_L2_CONF;
    @Parameter
    boolean correctWater = false;

    public RayleighCorrectionOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public boolean isComputingAllBandsAtOnce() {
        return true;
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }

        return super.initialize(pm);
    }

    @Override
    public Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        Product inputProduct = getSourceProduct("l1b");

        final int sceneWidth = inputProduct.getSceneRasterWidth();
        final int sceneHeight = inputProduct.getSceneRasterHeight();

        Product targetProduct = new Product("MER", "MER_L2", sceneWidth, sceneHeight);

        brrBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < brrBands.length; i++) {
            if (i == bb11 || i == bb15) {
                continue;
            }
            Band rhoToaBand = inputProduct.getBandAt(i);

            brrBands[i] = new Band(BRR_BAND_PREFIX + "_" + (i + 1),
                                   ProductData.TYPE_FLOAT32, sceneWidth, sceneHeight);
            ProductUtils.copySpectralAttributes(rhoToaBand, brrBands[i]);
            brrBands[i].setNoDataValueUsed(true);
            brrBands[i].setNoDataValue(BAD_VALUE);

            targetProduct.addBand(brrBands[i]);
        }

        flagBand = new Band(RAY_CORR_FLAGS, ProductData.TYPE_INT16,
                            sceneWidth, sceneHeight);
        targetProduct.addBand(flagBand);
        FlagCoding flagCoding = createFlagCoding();
        flagBand.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);

        return targetProduct;
    }

    protected FlagCoding createFlagCoding() {
        FlagCoding flagCoding = new FlagCoding(RAY_CORR_FLAGS);
        int bitIndex = 0;
        for (int i = 0; i < brrBands.length; i++) {
            if (i == bb11 || i == bb15) {
                continue;
            }
            flagCoding.addFlag("F_NEGATIV_BRR_" + (i + 1), FlagWrapper.setFlag(0, bitIndex), null);
            bitIndex++;
        }
        return flagCoding;
    }

    @Override
    public void initSourceRetriever() throws OperatorException {
        final Product l1bProduct = getSourceProduct("l1b");
        final Product landProduct = getSourceProduct("land");
        final Product gasCorProduct = getSourceProduct("input");

        dataRetriever = new SourceDataRetriever(maxTileSize);
        try {
            auxData = new L2AuxData(dpmConfig, l1bProduct);
            rayleighCorrection = new RayleighCorrection(auxData);
        } catch (Exception e) {
            throw new OperatorException("could not load L2Auxdata", e);
        }
        sza = dataRetriever.connectFloat(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME));
        vza = dataRetriever.connectFloat(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME));
        saa = dataRetriever.connectFloat(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME));
        vaa = dataRetriever.connectFloat(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME));
        altitude = dataRetriever.connectFloat(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME));
        ecmwfPressure = dataRetriever.connectFloat(l1bProduct.getTiePointGrid("atm_press"));

        rhoNg = new float[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS][0];
        for (int i = 0; i < rhoNg.length; i++) {
            if (i == bb11 || i == bb15) {
                continue;
            }
            rhoNg[i] = dataRetriever.connectFloat(gasCorProduct.getBand(GaseousCorrectionOp.RHO_NG_BAND_PREFIX + "_" + (i + 1)));
        }
        isLandCons = dataRetriever.connectBooleanExpression(landProduct, LandClassificationOp.LAND_FLAGS + ".F_LANDCONS");

        brr = new float[rhoNg.length][0];
    }

    @Override
    public void computeTiles(Rectangle rectangle,
                             ProductDataCache cache, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            dataRetriever.readData(rectangle, new SubProgressMonitor(pm, 1));

            brrFlags = new FlagWrapper.Short((short[]) cache.createData(flagBand).getElems());
            for (int i = 0; i < brrBands.length; i++) {
                Band band = brrBands[i];
                if (band != null) {
                    brr[i] = (float[]) cache.createData(band).getElems();
                }
            }

            for (int iPL1 = rectangle.y; iPL1 < rectangle.y + rectangle.height; iPL1 += Constants.SUBWIN_HEIGHT) {
                for (int iPC1 = rectangle.x; iPC1 < rectangle.x + rectangle.width; iPC1 += Constants.SUBWIN_WIDTH) {
                    final int iPC2 = Math.min(rectangle.x + rectangle.width, iPC1 + Constants.SUBWIN_WIDTH) - 1;
                    final int iPL2 = Math.min(rectangle.y + rectangle.height, iPL1 + Constants.SUBWIN_HEIGHT) - 1;
                    landAtmCor(iPC1, iPC2, iPL1, iPL2, rectangle);
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }

    protected void landAtmCor(int ic0, int ic1, int il0, int il1, Rectangle rectangle) {
        boolean correctPixel = false;
        boolean[][] do_corr = new boolean[SUBWIN_HEIGHT][SUBWIN_WIDTH];

        for (int il = il0; il <= il1; il++) {
            for (int ic = ic0; ic <= ic1; ic++) {
                int index = TileRectCalculator.convertToIndex(ic, il, rectangle);
                if (rhoNg[0][index] != BAD_VALUE && (correctWater || isLandCons[index])) {
                    correctPixel = true;
                    do_corr[il - il0][ic - ic0] = true;
                } else {
                    do_corr[il - il0][ic - ic0] = false;
                    writeBadValue(index);
                }
            }
        }

        if (correctPixel) {
            // rayleigh phase function coefficients, PR in DPM
            double[] phaseR = new double[3];
            // rayleigh optical thickness, tauR0 in DPM
            double[] tauR = new double[L1_BAND_NUM];
            // rayleigh reflectance, rhoR4x4 in DPM
            double[] rhoR = new double[L1_BAND_NUM];
            // rayleigh down transmittance, T_R_thetas_4x4
            double[] transRs = new double[L1_BAND_NUM];
            // rayleigh up transmittance, T_R_thetav_4x4
            double[] transRv = new double[L1_BAND_NUM];
            // rayleigh spherical albedo, SR_4x4
            double[] sphAlbR = new double[L1_BAND_NUM];


            final int index0 = TileRectCalculator.convertToIndex(ic0, il0, rectangle);

            /* average geometry, ozone for window DPM : just use corner pixel ! */
            final double sins = Math.sin(sza[index0] * MathUtils.DTOR);
            final double sinv = Math.sin(vza[index0] * MathUtils.DTOR);
            final double mus = Math.cos(sza[index0] * MathUtils.DTOR);
            final double muv = Math.cos(vza[index0] * MathUtils.DTOR);
            final double deltaAzimuth = HelperFunctions.computeAzimuthDifference(vaa[index0], saa[index0]);

            /*
            * 2. Rayleigh corrections (DPM section 7.3.3.3.2, step 2.6.15)
            */

            final double press = HelperFunctions.correctEcmwfPressure(ecmwfPressure[index0],
                                                                      altitude[index0], auxData.press_scale_height); /* DPM #2.6.15.1-3 */
            final double airMass = HelperFunctions.calculateAirMass(vza[index0], sza[index0]);

            /* Rayleigh phase function Fourier decomposition */
            rayleighCorrection.phase_rayleigh(mus, muv, sins, sinv, phaseR);

            /* Rayleigh optical thickness */
            rayleighCorrection.tau_rayleigh(press, tauR);

            /* Rayleigh reflectance*/
            rayleighCorrection.ref_rayleigh(deltaAzimuth, sza[index0], vza[index0], mus, muv,
                                            airMass, phaseR, tauR, rhoR);

            /* Rayleigh transmittance */
            rayleighCorrection.trans_rayleigh(mus, tauR, transRs);
            rayleighCorrection.trans_rayleigh(muv, tauR, transRv);

            /* Rayleigh spherical albedo */
            rayleighCorrection.sphalb_rayleigh(tauR, sphAlbR);

            /* process each pixel */
            for (int il = il0; il <= il1; il++) {
                for (int ic = ic0; ic <= ic1; ic++) {
                    int index = TileRectCalculator.convertToIndex(ic, il, rectangle);
                    if (do_corr[il - il0][ic - ic0]) {
                        /* Rayleigh correction for each pixel */
                        rayleighCorrection.corr_rayleigh(rhoR, sphAlbR, transRs, transRv,
                                                         rhoNg, brr, index); /*  (2.6.15.4) */

                        /* flag negative Rayleigh-corrected reflectance */
                        for (int ib = 0; ib < L1_BAND_NUM; ib++) {
                            switch (ib) {
                                case bb412:
                                case bb442:
                                case bb490:
                                case bb510:
                                case bb560:
                                case bb620:
                                case bb665:
                                case bb681:
                                case bb705:
                                case bb753:
                                case bb775:
                                case bb865:
                                case bb890:
                                    if (brr[ib][index] <= 0.) {
                                        /* set annotation flag for reflectance product - v4.2 */
                                        brrFlags.set(index, (ib <= bb760 ? ib : ib - 1));
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                }
            }
        }
    }

    protected void writeBadValue(int index) {
        for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
            if (bandId == bb11 || bandId == bb15) {
                continue;
            }
            brr[bandId][index] = BAD_VALUE;
        }
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(RayleighCorrectionOp.class, "Meris.RayleighCorrection");
        }
    }
}
