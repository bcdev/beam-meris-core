/*
 * $Id: CloudClassificationOp.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
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
package org.esa.beam.meris.brr;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.meris.l2auxdata.DpmConfigException;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.meris.l2auxdata.L2AuxdataProvider;
import org.esa.beam.util.BitSetter;
import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;
import org.esa.beam.util.math.MathUtils;

import java.awt.Rectangle;


@OperatorMetadata(alias = "Meris.CloudClassification",
        version = "1.0",
        internal = true,
        authors = "Marco Zühlke",
        copyright = "(c) 2007 by Brockmann Consult",
        description = "MERIS L2 cloud classification.")
public class CloudClassificationOp extends MerisBasisOp implements Constants {

    public static final String CLOUD_FLAGS = "cloud_classif_flags";
    public static final String PRESSURE_CTP = "ctp_ipf";
    public static final String PRESSURE_SURFACE = "surface_press_ipf";
    public static final String PRESSURE_ECMWF = "p_ecmwf";

    private static final int BAND_BRIGHT_N = 0;
    private static final int BAND_SLOPE_N_1 = 1;
    private static final int BAND_SLOPE_N_2 = 2;

    public static final int F_CLOUD = 0;
    public static final int F_BRIGHT = 1;
    public static final int F_LOW_NN_P = 2;
    public static final int F_PCD_NN_P = 3;
    public static final int F_LOW_POL_P = 4;
    public static final int F_PCD_POL_P = 5;
    public static final int F_CONFIDENCE_P = 6;
    public static final int F_SLOPE_1 = 7;
    public static final int F_SLOPE_2 = 8;

    private L2AuxData auxData;
    
    private RayleighCorrection rayleighCorrection;
    
    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="rhotoa")
    private Product rhoToaProduct;
    @SourceProduct(alias="ctp", optional=true)
    private Product ctpProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter(description="If 'true' the algorithm will compute L2 Pressures.", defaultValue="true")
    public boolean l2Pressures = true;
    @Parameter(description="If 'true' the algorithm will compute L2 Cloud detection flags.", defaultValue="true")
    public boolean l2CloudDetection = true;


    @Override
    public void initialize() throws OperatorException {
        try {
            auxData = L2AuxdataProvider.getInstance().getAuxdata(l1bProduct);
        } catch (DpmConfigException e) {
            throw new OperatorException("Could not load L2Auxdata", e);
        }
        rayleighCorrection = new RayleighCorrection(auxData);
        createTargetProduct();
    }

    private void createTargetProduct() {
        targetProduct = createCompatibleProduct(l1bProduct, "MER", "MER_L2");
        
        Band cloudFlagBand = targetProduct.addBand(CLOUD_FLAGS, ProductData.TYPE_INT16);
        FlagCoding flagCoding = createFlagCoding();
        cloudFlagBand.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);
        
        if (ctpProduct != null) {
            Band ctpBand = targetProduct.addBand(PRESSURE_CTP, ProductData.TYPE_FLOAT32);
            Band pSurfBand = targetProduct.addBand(PRESSURE_SURFACE, ProductData.TYPE_FLOAT32);
            //Band pEcmwfBand = targetProduct.addBand(PRESSURE_ECMWF, ProductData.TYPE_FLOAT32);
        }
        
        if (l1bProduct.getPreferredTileSize() != null) {
            targetProduct.setPreferredTileSize(l1bProduct.getPreferredTileSize());
        }
    }

    public static FlagCoding createFlagCoding() {
        FlagCoding flagCoding = new FlagCoding(CLOUD_FLAGS);
        flagCoding.addFlag("F_CLOUD", BitSetter.setFlag(0, F_CLOUD), null);
        flagCoding.addFlag("F_BRIGHT", BitSetter.setFlag(0, F_BRIGHT), null);
        flagCoding.addFlag("F_LOW_NN_P", BitSetter.setFlag(0, F_LOW_NN_P), null);
        flagCoding.addFlag("F_PCD_NN_P", BitSetter.setFlag(0, F_PCD_NN_P), null);
        flagCoding.addFlag("F_LOW_POL_P", BitSetter.setFlag(0, F_LOW_POL_P), null);
        flagCoding.addFlag("F_PCD_POL_P", BitSetter.setFlag(0, F_PCD_POL_P), null);
        flagCoding.addFlag("F_CONFIDENCE_P", BitSetter.setFlag(0, F_CONFIDENCE_P), null);
        flagCoding.addFlag("F_SLOPE_1", BitSetter.setFlag(0, F_SLOPE_1), null);
        flagCoding.addFlag("F_SLOPE_2", BitSetter.setFlag(0, F_SLOPE_2), null);
        return flagCoding;
    }

    private SourceData loadSourceTiles(Rectangle rectangle, ProgressMonitor pm) throws OperatorException {

    	SourceData sd = new SourceData();
    	sd.rhoToa = new float[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS][0];
    	sd.radiance = new Tile[3];
    	
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            sd.rhoToa[i] = (float[]) getSourceTile(rhoToaProduct.getBand(Rad2ReflOp.RHO_TOA_BAND_PREFIX + "_" + (i + 1)), rectangle, pm).getRawSamples().getElems();
        }
        sd.radiance[BAND_BRIGHT_N] = getSourceTile(
				l1bProduct.getBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[auxData.band_bright_n]),
				rectangle, pm);
        sd.radiance[BAND_SLOPE_N_1] = getSourceTile(
				l1bProduct.getBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[auxData.band_slope_n_1]),
				rectangle, pm);
        sd.radiance[BAND_SLOPE_N_2] = getSourceTile(
				l1bProduct.getBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[auxData.band_slope_n_2]),
				rectangle, pm);
        sd.detectorIndex = (short[]) getSourceTile(
				l1bProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME),
				rectangle, pm).getRawSamples().getElems();
        sd.sza = (float[]) getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle, pm).getRawSamples().getElems();
        sd.vza = (float[]) getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle, pm).getRawSamples().getElems();
        sd.saa = (float[]) getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle, pm).getRawSamples().getElems();
        sd.vaa = (float[]) getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle, pm).getRawSamples().getElems();
        sd.altitude = (float[]) getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME), rectangle, pm).getRawSamples().getElems();
        sd.ecmwfPressure = (float[]) getSourceTile(l1bProduct.getTiePointGrid("atm_press"), rectangle, pm).getRawSamples().getElems();
        sd.l1Flags = getSourceTile(l1bProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME), rectangle, pm);
        
        return sd;
    }

    @Override
    public void computeTile(Band band, Tile targetTile, ProgressMonitor pm) throws OperatorException {

    	Rectangle rectangle = targetTile.getRectangle();
        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            SourceData sd = loadSourceTiles(rectangle, pm);
            
            Tile ctpTile = null;
            if (ctpProduct != null) {
                ctpTile = getSourceTile(ctpProduct.getBand("cloud_top_press"), rectangle, pm);
            }

            PixelInfo pixelInfo = new PixelInfo();
			int i = 0;
			for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				pixelInfo.y = y;
				for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
					if (!sd.l1Flags.getSampleBit(x, y, L1_F_INVALID)) {
						pixelInfo.x = x;
						pixelInfo.index = i;
						pixelInfo.airMass = HelperFunctions.calculateAirMass(
								sd.vza[i], sd.sza[i]);
						if (sd.l1Flags.getSampleBit(x, y, L1_F_LAND)) {
							// ECMWF pressure is only corrected for positive
							// altitudes and only for land pixels
							pixelInfo.ecmwfPressure = HelperFunctions
									.correctEcmwfPressure(sd.ecmwfPressure[i],
											sd.altitude[i],
											auxData.press_scale_height);
						} else {
							pixelInfo.ecmwfPressure = sd.ecmwfPressure[i];
						}
						if (ctpTile != null) {
						    float ctp = ctpTile.getSampleFloat(x, y);
						    if (band.getName().equals(CLOUD_FLAGS) && l2CloudDetection) {
						        classifyCloud(sd, ctp, pixelInfo, targetTile);
						    }
						    if (band.getName().equals(PRESSURE_SURFACE) && l2Pressures) {
						        setCloudPressureSurface(sd, pixelInfo, targetTile);
						    }
						    if (band.getName().equals(PRESSURE_CTP) && l2Pressures) {
						        setCloudPressureTop(ctp, pixelInfo, targetTile);
						        //if (band.getName().equals(PRESSURE_ECMWF)) {
						        //    setCloudPressureEcmwf(sd, pixelInfo, targetTile);
						        //}
						    }
						} else {
						    classifyCloud(sd, -1, pixelInfo, targetTile);
						}
					}
					i++;
				}
				pm.worked(1);
			}
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }
    
    public void setCloudPressureSurface(SourceData sd, PixelInfo pixelInfo, Tile targetTile) {
        final ReturnValue press = new ReturnValue();

        Comp_Pressure(sd, pixelInfo, press);
        targetTile.setSample(pixelInfo.x, pixelInfo.y, Math.max(0.0, press.value));
    }
    
    public void setCloudPressureTop(float ctp, PixelInfo pixelInfo, Tile targetTile) {
    	targetTile.setSample(pixelInfo.x, pixelInfo.y, ctp);
    }

//    public void setCloudPressureEcmwf(SourceData sd, PixelInfo pixelInfo, Tile targetTile) {
//        final ReturnValue press = new ReturnValue();
//
//        Comp_Pressure(sd, pixelInfo, press);
//        targetTile.setSample(pixelInfo.x, pixelInfo.y, Math.max(0.0, pixelInfo.ecmwfPressure));
//    }

    public void classifyCloud(SourceData sd, float ctp, PixelInfo pixelInfo, Tile targetTile) {
        final ReturnValue press = new ReturnValue();
        final boolean[] resultFlags = new boolean[3];

        //boolean pcd_poly = Comp_Pressure(pixel) != 0;
        Comp_Pressure(sd, pixelInfo, press);
        boolean pcd_poly = press.error;

        /* apply thresholds on pressure- step 2.1.2 */
        press_thresh(sd, pixelInfo, press.value, ctp, resultFlags);
        boolean low_P_nn = resultFlags[0];
        boolean low_P_poly = resultFlags[1];
        boolean delta_p = resultFlags[2];

        /* keep for display-debug - added for v2.1 */
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_LOW_NN_P, low_P_nn);
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_PCD_NN_P, true); /* DPM #2.1.5-25 */
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_LOW_POL_P, low_P_poly);
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_PCD_POL_P, pcd_poly); /* DPM #2.1.12-12 */
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_CONFIDENCE_P, delta_p);

        // Compute slopes- step 2.1.7
        spec_slopes(sd, pixelInfo, resultFlags);
        boolean bright_f = resultFlags[0];
        boolean slope_1_f = resultFlags[1];
        boolean slope_2_f = resultFlags[2];
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_BRIGHT, bright_f);
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_SLOPE_1, slope_1_f);
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_SLOPE_2, slope_2_f);

        // table-driven classification- step 2.1.8
        // DPM #2.1.8-1
        boolean land_f = sd.l1Flags.getSampleBit(pixelInfo.x, pixelInfo.y, L1_F_LAND);
        boolean is_cloud = is_cloudy(land_f,
                                     bright_f,
                                     low_P_nn, low_P_poly, delta_p,
                                     slope_1_f, slope_2_f,
                                     true, pcd_poly);
        
        targetTile.setSample(pixelInfo.x, pixelInfo.y, F_CLOUD, is_cloud);
    }

    /**
     * Computes the pressure.
     * <p/>
     * <b>Input:</b> {@link org.esa.beam.meris.brr.dpm.DpmPixel#rho_toa}, {@link org.esa.beam.meris.brr.dpm.DpmPixel#mus}, {@link org.esa.beam.meris.brr.dpm.DpmPixel#muv}<br> <b>Output:</b> {@link
     * DpmPixel#eta}, {@link org.esa.beam.meris.brr.dpm.DpmPixel#TOAR} (exceptional)<br> <b>DPM ref.:</b> section 3.5 step
     * 2.1.4<br> <b>MEGS ref.:</b> <code>pixelid.c</code>, function <code>Comp_Pressure</code><br>
     *
     * @param pixelInfo the pixel structure
     * @param press the resulting pressure
     */
    private void Comp_Pressure(SourceData sd, PixelInfo pixelInfo, ReturnValue press) {
        double eta; // Ratio TOAR(11)/TOAR(10)
        press.error = false;
        final FractIndex spectralShiftIndex = new FractIndex();
        final FractIndex[] cIndex = FractIndex.createArray(2);
        final ReturnValue press_1 = new ReturnValue();
        final ReturnValue press_2 = new ReturnValue();

        /* DPM #2.1.3-1 */
        /* get spectral_shift from detector id in order to use pressure polynomials */
        Interp.interpCoord(auxData.central_wavelength[bb760][sd.detectorIndex[pixelInfo.index]],
                           auxData.spectral_shift_wavelength,
                           spectralShiftIndex);

        // DPM #2.1.3-2, DPM #2.1.3-3, DPM #2.1.3-4
        // when out of bands, spectral_shift is set to 0 or PPOL_NUM_SHIFT with a null weight,
        // so it works fine with the following.

        /* reflectance ratio computation, DPM #2.1.12-2 */
        if (sd.rhoToa[bb753][pixelInfo.index] > 0.0) {
            eta = sd.rhoToa[bb760][pixelInfo.index] / sd.rhoToa[bb753][pixelInfo.index];
        } else {
            // DPM section 3.5.3
            eta = 0.0;        /* DPM #2.1.12-3 */
            press.error = true;                /* raise PCD */
        }
        // DPM #2.1.12-4
        Interp.interpCoord(pixelInfo.airMass, auxData.C.getTab(1), cIndex[0]);
        Interp.interpCoord(sd.rhoToa[bb753][pixelInfo.index], auxData.C.getTab(2), cIndex[1]);

        float[][][] c_lut = (float[][][]) auxData.C.getJavaArray();
        // coefficient used in the pressure estimation
        double C_res = Interp.interpolate(c_lut[VOLC_NONE], cIndex);

        // DPM #2.1.12-5, etha * C
        double ethaC = eta * C_res;
        // DPM #2.1.12-5a
        pressure_func(ethaC, pixelInfo.airMass, spectralShiftIndex.index, press_1);
        // DPM #2.1.12-5b
        pressure_func(ethaC, pixelInfo.airMass, spectralShiftIndex.index + 1, press_2);
        if (press_1.error) {
            press.value = press_2.value; /* corrected by LB as DPM is flawed: press_1 --> press_2 */
        } else if (press_2.error) {
            press.value = press_1.value; /* corrected by LB as DPM is flawed: press_2 --> press_1 */
        } else {
            /* DPM #2.1.12-5c */
            press.value = (1 - spectralShiftIndex.fraction) * press_1.value + spectralShiftIndex.fraction * press_2.value;
        }

        /* DPM #2.1.12-12 */
        press.error = press.error || press_1.error || press_2.error;
    }

    /**
     * Computes surface pressure from corrected ratio b11/b10.
     * <p/>
     * <b>DPM ref.:</b> section 3.5 (step 2.1.12)<br> <b>MEGS ref.:</b> <code>pixelid.c</code>, function
     * <code>pressure_func</code><br>
     *
     * @param eta_C         ratio TOAR(B11)/TOAR(B10) corrected
     * @param airMass       air mass
     * @param spectralShift
     * @param press         the resulting pressure
     */
    private void pressure_func(double eta_C,
                               double airMass,
                               int spectralShift,
                               ReturnValue press) {
        double P; /* polynomial accumulator */
        double koeff; /* powers of eta_c */
        int i;
        press.error = false;
        final FractIndex polcoeffShiftIndex = new FractIndex();

        /* Interpoate polcoeff from spectral shift dependent table - DPM #2.1.16-1 */
        Interp.interpCoord(spectralShift, auxData.polcoeff.getTab(0), polcoeffShiftIndex);
        /* nearest neighbour interpolation */
        if (polcoeffShiftIndex.fraction > 0.5) {
            polcoeffShiftIndex.index++;
        }
        float[][] polcoeff = (float[][]) auxData.polcoeff.getArray().getJavaArray();

        /* DPM #2.1.16-2 */
        P = polcoeff[polcoeffShiftIndex.index][0];
        koeff = 1.0;
        for (i = 1; i < PPOL_NUM_ORDER; i++) {
            koeff *= eta_C;
            P += polcoeff[polcoeffShiftIndex.index][i] * koeff;
        }
        /* CHANGED v7.0: polynomial now gives log10(m*P^2) (LB 15/12/2003) */
        if ((P <= 308.0) && (P >= -308.0)) {  /* MP2 would be out of double precision range  */
            double MP2 = Math.pow(10.0, P); /* retrieved product of air mass times square of pressure */
            press.value = Math.sqrt(MP2 / airMass); /* DPM 2.1.16-3 */
            if (press.value > auxData.maxPress) {
                press.value = auxData.maxPress; /* DPM #2.1.16-5 */
                press.error = true;     /* DPM #2.1.16-4  */
            }
        } else {
            press.value = 0.0;    /* DPM #2.1.16-6 */
            press.error = true;  /* DPM #2.1.16-7 */
        }
    }

    /**
     * Compares pressure estimates with ECMWF data.
     * <p/>
     * <b>Uses:</b> {@link DpmPixel#l2flags}, {@link DpmPixel#sun_zenith}, {@link DpmPixel#view_zenith},
     * {@link DpmPixel#press_ecmwf}, {@link L2AuxData#DPthresh_land},
     * {@link L2AuxData#DPthresh_ocean},
     * {@link L2AuxData#press_confidence}<br> <b>Sets:</b> nothing <br> <b>DPM
     * Ref.:</b> MERIS Level 2 DPM, step 2.1 <br> <b>MEGS Ref.:</b> file pixelid.c, function press_thresh  <br>
     *
     * @param pixel        the pixel structure
     * @param pressure     the pressure of the pixel
     * @param ctp      cloud top pressure from CloudTopPressureOp, or -1 if not given
     * @param result_flags the return values, <code>resultFlags[0]</code> contains low NN pressure flag (low_P_nn),
     *                     <code>resultFlags[1]</code> contains low polynomial pressure flag (low_P_poly),
     *                     <code>resultFlags[2]</code> contains pressure range flag (delta_p).
     */
    private void press_thresh(SourceData sd, PixelInfo pixelInfo, double pressure, float ctp, boolean[] result_flags) {
        double delta_press_thresh; /* absolute threshold on pressure difference */
        FractIndex[] DP_Index = FractIndex.createArray(2);

        /* get proper threshold - DPM #2.1.2-2 */
        if (sd.l1Flags.getSampleBit(pixelInfo.x, pixelInfo.y, L1_F_LAND)) {
            Interp.interpCoord(sd.sza[pixelInfo.index], auxData.DPthresh_land.getTab(0), DP_Index[0]);
            Interp.interpCoord(sd.vza[pixelInfo.index], auxData.DPthresh_land.getTab(1), DP_Index[1]);
            delta_press_thresh = Interp.interpolate(auxData.DPthresh_land.getJavaArray(), DP_Index);
        } else {
            Interp.interpCoord(sd.sza[pixelInfo.index], auxData.DPthresh_ocean.getTab(0), DP_Index[0]);
            Interp.interpCoord(sd.vza[pixelInfo.index], auxData.DPthresh_ocean.getTab(1), DP_Index[1]);
            delta_press_thresh = Interp.interpolate(auxData.DPthresh_ocean.getJavaArray(), DP_Index);
        }

        /* test NN pressure- DPM #2.1.2-4 */ // low_P_nn
        if (ctp != -1) {
            result_flags[0] = (ctp < pixelInfo.ecmwfPressure - delta_press_thresh); //changed in V7
        } else {
            result_flags[0] = (pixelInfo.ecmwfPressure < pixelInfo.ecmwfPressure - delta_press_thresh); //changed in V7
        }
        /* test polynomial pressure- DPM #2.1.2-3 */ // low_P_poly
        result_flags[1] = (pressure < pixelInfo.ecmwfPressure - delta_press_thresh);  //changed in V7
        /* test pressure range - DPM #2.1.2-5 */   // delta_p
        result_flags[2] = (Math.abs(pixelInfo.ecmwfPressure - pressure) > auxData.press_confidence); //changed in V7
    }

    /**
     * Computes the slope of Rayleigh-corrected reflectance.
     *
     * @param pixelInfo        the pixel structure
     * @param result_flags the return values, <code>resultFlags[0]</code> contains low NN pressure flag (low_P_nn),
     *                     <code>resultFlags[1]</code> contains low polynomial pressure flag (low_P_poly),
     *                     <code>resultFlags[2]</code> contains pressure range flag (delta_p).
     */
    private void spec_slopes(SourceData dc, PixelInfo pixelInfo, boolean[] result_flags) {
        double rhorc_442_thr;   /* threshold on rayleigh corrected reflectance */
        double slope1, slope2;

        //Rayleigh phase function coefficients, PR in DPM
        final double[] phaseR = new double[RAYSCATT_NUM_SER];
        //Rayleigh optical thickness, tauR0 in DPM
        final double[] tauR = new double[L1_BAND_NUM];
        //Rayleigh corrected reflectance
        final double[] rhoAg = new double[L1_BAND_NUM];
        //Rayleigh correction
        final double[] rhoRay = new double[L1_BAND_NUM];

        double sins = Math.sin(dc.sza[pixelInfo.index] * MathUtils.DTOR);
        double sinv = Math.sin(dc.vza[pixelInfo.index] * MathUtils.DTOR);
        double mus = Math.cos(dc.sza[pixelInfo.index] * MathUtils.DTOR);
        double muv = Math.cos(dc.vza[pixelInfo.index] * MathUtils.DTOR);
        final double deltaAzimuth = HelperFunctions.computeAzimuthDifference(dc.vaa[pixelInfo.index], dc.saa[pixelInfo.index]);

        /* Rayleigh phase function Fourier decomposition */
        rayleighCorrection.phase_rayleigh(mus, muv, sins, sinv, phaseR);

        double press = pixelInfo.ecmwfPressure; /* DPM #2.1.7-1 v1.1 */

        /* Rayleigh optical thickness */
        rayleighCorrection.tau_rayleigh(press, tauR); /* DPM #2.1.7-2 */

        /* Rayleigh reflectance - DPM #2.1.7-3 - v1.3 */
        rayleighCorrection.ref_rayleigh(deltaAzimuth, dc.sza[pixelInfo.index], dc.vza[pixelInfo.index],
                                        mus, muv, pixelInfo.airMass, phaseR, tauR, rhoRay);

        /* DPM #2.1.7-4 */
        for (int band = bb412; band <= bb900; band++) {
            rhoAg[band] = dc.rhoToa[band][pixelInfo.index] - rhoRay[band];
        }

        final FractIndex[] rhoRC442index = FractIndex.createArray(3);
        /* Interpolate threshold on rayleigh corrected reflectance - DPM #2.1.7-9 */
        if (dc.l1Flags.getSampleBit(pixelInfo.x, pixelInfo.y, L1_F_LAND)) {   /* land pixel */
            Interp.interpCoord(dc.sza[pixelInfo.index],
                               auxData.Rhorc_442_land_LUT.getTab(0),
                               rhoRC442index[0]);
            Interp.interpCoord(dc.vza[pixelInfo.index],
                               auxData.Rhorc_442_land_LUT.getTab(1),
                               rhoRC442index[1]);
            Interp.interpCoord(deltaAzimuth,
                               auxData.Rhorc_442_land_LUT.getTab(2),
                               rhoRC442index[2]);
            rhorc_442_thr = Interp.interpolate(auxData.Rhorc_442_land_LUT.getJavaArray(), rhoRC442index);
        } else {    /* water  pixel */
            Interp.interpCoord(dc.sza[pixelInfo.index],
                               auxData.Rhorc_442_ocean_LUT.getTab(0),
                               rhoRC442index[0]);
            Interp.interpCoord(dc.vza[pixelInfo.index],
                               auxData.Rhorc_442_ocean_LUT.getTab(1),
                               rhoRC442index[1]);
            Interp.interpCoord(deltaAzimuth,
                               auxData.Rhorc_442_ocean_LUT.getTab(2),
                               rhoRC442index[2]);
            rhorc_442_thr = Interp.interpolate(auxData.Rhorc_442_ocean_LUT.getJavaArray(), rhoRC442index);
        }
        /* END CHANGE 01 */

        boolean bright_f, slope1_f, slope2_f;
        bright_f = (rhoAg[auxData.band_bright_n] >= rhorc_442_thr)
                || isSaturated(dc, pixelInfo.x, pixelInfo.y, BAND_BRIGHT_N, auxData.band_bright_n);

        /* Spectral slope processor.brr 1 */
        if (rhoAg[auxData.band_slope_d_1] <= 0.0) {
            /* negative reflectance exception */
            slope1_f = false; /* DPM #2.1.7-6 */
        } else {
            /* DPM #2.1.7-5 */
            slope1 = rhoAg[auxData.band_slope_n_1] / rhoAg[auxData.band_slope_d_1];
            slope1_f = ((slope1 >= auxData.slope_1_low_thr) && (slope1 <= auxData.slope_1_high_thr))
                    || isSaturated(dc, pixelInfo.x, pixelInfo.y, BAND_SLOPE_N_1, auxData.band_slope_n_1);
        }

        /* Spectral slope processor.brr 2 */
        if (rhoAg[auxData.band_slope_d_2] <= 0.0) {
            /* negative reflectance exception */
            slope2_f = false; /* DPM #2.1.7-8 */
        } else {
            /* DPM #2.1.7-7 */
            slope2 = rhoAg[auxData.band_slope_n_2] / rhoAg[auxData.band_slope_d_2];
            slope2_f = ((slope2 >= auxData.slope_2_low_thr) && (slope2 <= auxData.slope_2_high_thr))
                    || isSaturated(dc, pixelInfo.x, pixelInfo.y, BAND_SLOPE_N_2, auxData.band_slope_n_2);
        }

        result_flags[0] = bright_f;
        result_flags[1] = slope1_f;
        result_flags[2] = slope2_f;
    }

    /**
     * Table driven cloud classification decision.
     * <p/>
     * <b>DPM Ref.:</b> Level 2, Step 2.1.8 <br> <b>MEGS Ref.:</b> file classcloud.c, function class_cloud  <br>
     *
     * @param land_f
     * @param bright_f
     * @param low_P_nn
     * @param low_P_poly
     * @param delta_p
     * @param slope_1_f
     * @param slope_2_f
     * @param pcd_nn
     * @param pcd_poly
     * @return <code>true</code> if cloud flag shall be set
     */
    private boolean is_cloudy(boolean land_f, boolean bright_f,
                              boolean low_P_nn, boolean low_P_poly,
                              boolean delta_p, boolean slope_1_f,
                              boolean slope_2_f, boolean pcd_nn,
                              boolean pcd_poly) {
        boolean is_cloud;
        int index = 0;
        
        /* set bits of index according to inputs */
        index = BitSetter.setFlag(index, CC_BRIGHT, bright_f);
        index = BitSetter.setFlag(index, CC_LOW_P_NN, low_P_nn);
        index = BitSetter.setFlag(index, CC_LOW_P_PO, low_P_poly);
        index = BitSetter.setFlag(index, CC_DELTA_P, delta_p);
        index = BitSetter.setFlag(index, CC_PCD_NN, pcd_nn);
        index = BitSetter.setFlag(index, CC_PCD_PO, pcd_poly);
        index = BitSetter.setFlag(index, CC_SLOPE_1, slope_1_f);
        index = BitSetter.setFlag(index, CC_SLOPE_2, slope_2_f);
        index &= 0xff;

        /* readRecord decision table */
        if (land_f) {
            is_cloud = auxData.land_decision_table[index]; /* DPM #2.1.8-1 */
        } else {
            is_cloud = auxData.water_decision_table[index]; /* DPM #2.1.8-2 */
        }

        return is_cloud;
    }

    private boolean isSaturated(SourceData sd, int x, int y, int radianceBandId, int bandId) {
        return sd.radiance[radianceBandId].getSampleFloat(x, y) > auxData.Saturation_L[bandId];
    }
    
    private static class SourceData {
		private float[][] rhoToa;
		private Tile[] radiance;
		private short[] detectorIndex;
		private float[] sza;
		private float[] vza;
		private float[] saa;
		private float[] vaa;
		private float[] altitude;
		private float[] ecmwfPressure;
		private Tile l1Flags;
	}

    private static class PixelInfo {
        int index;
        int x;
        int y;
        double airMass;
        float ecmwfPressure;
    }
    
    private static class ReturnValue {
        public double value;
        public boolean error;
    }


    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CloudClassificationOp.class);
        }
    }
}
