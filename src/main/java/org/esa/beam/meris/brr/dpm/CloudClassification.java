/*
 * $Id: CloudClassification.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.meris.brr.dpm;

import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.util.BitSetter;
import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;


/**
 * The MERIS Level 2 cloud classification module.
 */
public class CloudClassification implements Constants {

    private L2AuxData auxData;

    private LocalHelperVariables lh;

    private RayleighCorrection rayleighCorrection;

    /**
     * Constructs the module
     */
    public CloudClassification(L2AuxData auxData, RayleighCorrection rayCorr) {
        this.auxData = auxData;
        lh = new LocalHelperVariables();
        rayleighCorrection = rayCorr;
    }

    public void classify_cloud(DpmPixel pixel) {

        //boolean pcd_poly = Comp_Pressure(pixel) != 0;
        Comp_Pressure(pixel, lh.press);
        boolean pcd_poly = lh.press.error;

        /* apply thresholds on pressure- step 2.1.2 */
        press_thresh(pixel, lh.press.value, lh.resultFlags);
        boolean low_P_nn = lh.resultFlags[0];
        boolean low_P_poly = lh.resultFlags[1];
        boolean delta_p = lh.resultFlags[2];

        /* keep for display-debug - added for v2.1 */
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_LOW_NN_P, low_P_nn);
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_PCD_NN_P, true);    /* DPM #2.1.5-25 */
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_LOW_POL_P, low_P_poly);
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_PCD_POL_P, pcd_poly); /* DPM #2.1.12-12 */
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_CONFIDENCE_P, delta_p);

        // Compute slopes- step 2.1.7
        spec_slopes(pixel, lh.resultFlags);
        boolean bright_f = lh.resultFlags[0];
        boolean slope_1_f = lh.resultFlags[1];
        boolean slope_2_f = lh.resultFlags[2];
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_BRIGHT, bright_f);
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_SLOPE_1, slope_1_f);
        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_SLOPE_2, slope_2_f);

        // table-driven classification- step 2.1.8
        // DPM #2.1.8-1
        boolean land_f = BitSetter.isFlagSet(pixel.l2flags, F_LAND);
        boolean is_cloud = is_cloudy(land_f,
                                     bright_f,
                                     low_P_nn, low_P_poly, delta_p,
                                     slope_1_f, slope_2_f,
                                     true, pcd_poly);

        pixel.l2flags = BitSetter.setFlag(pixel.l2flags, F_CLOUD, is_cloud);
    }

    /**
     * Computes the pressure.
     * <p/>
     * <p/>
     * <b>Input:</b> {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#rho_toa}, {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#mus}, {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#muv}<br> <b>Output:</b> {@link
     * DpmPixel#eta}, {@link org.esa.beam.dataproc.meris.sdr.dpm.DpmPixel#TOAR} (exceptional)<br> <b>DPM ref.:</b> section 3.5 step
     * 2.1.4<br> <b>MEGS ref.:</b> <code>pixelid.c</code>, function <code>Comp_Pressure</code><br>
     *
     * @param pixel the pixel structure
     * @param press the resulting pressure
     */
    private void Comp_Pressure(DpmPixel pixel, ReturnValue press) {
        double eta; // Ratio TOAR(11)/TOAR(10)
        press.error = false;

        /* DPM #2.1.3-1 */
        /* get spectral_shift from detector id in order to use pressure polynomials */
        Interp.interpCoord(auxData.central_wavelength[bb760][pixel.detector],
                           auxData.spectral_shift_wavelength,
                           lh.spectralShiftIndex);

        // DPM #2.1.3-2, DPM #2.1.3-3, DPM #2.1.3-4
        // when out of bands, spectral_shift is set to 0 or PPOL_NUM_SHIFT with a null weight,
        // so it works fine with the following.

        /* reflectance ratio computation, DPM #2.1.12-2 */
        if (pixel.rho_toa[bb753] > 0.0) {
            eta = pixel.rho_toa[bb760] / pixel.rho_toa[bb753];
        } else {
            // DPM section 3.5.3
            eta = 0.0;        /* DPM #2.1.12-3 */
            press.error = true;                /* raise PCD */
        }
        // DPM #2.1.12-4
        Interp.interpCoord(pixel.airMass, auxData.C.getTab(1), lh.cIndex[0]);
        Interp.interpCoord(pixel.rho_toa[bb753], auxData.C.getTab(2), lh.cIndex[1]);

        float[][][] c_lut = (float[][][]) auxData.C.getJavaArray();
        // coefficient used in the pressure estimation
        double C_res = Interp.interpolate(c_lut[VOLC_NONE], lh.cIndex);

        // DPM #2.1.12-5, etha * C
        double ethaC = eta * C_res;
        // DPM #2.1.12-5a
        pressure_func(ethaC, pixel.airMass, lh.spectralShiftIndex.index, lh.press_1);
        // DPM #2.1.12-5b
        pressure_func(ethaC, pixel.airMass, lh.spectralShiftIndex.index + 1, lh.press_2);
        if (lh.press_1.error) {
            press.value = lh.press_2.value; /* corrected by LB as DPM is flawed: press_1 --> press_2 */
        } else if (lh.press_2.error) {
            press.value = lh.press_1.value; /* corrected by LB as DPM is flawed: press_2 --> press_1 */
        } else {
            /* DPM #2.1.12-5c */
            press.value = (1 - lh.spectralShiftIndex.fraction) * lh.press_1.value + lh.spectralShiftIndex.fraction * lh.press_2.value;
        }

        /* DPM #2.1.12-12 */
        press.error = press.error || lh.press_1.error || lh.press_2.error;
    }

    /**
     * Computes surface pressure from corrected ratio b11/b10.
     * <p/>
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

        /* Interpoate polcoeff from spectral shift dependent table - DPM #2.1.16-1 */
        Interp.interpCoord(spectralShift, auxData.polcoeff.getTab(0), lh.polcoeffShiftIndex);
        /* nearest neighbour interpolation */
        if (lh.polcoeffShiftIndex.fraction > 0.5) {
            lh.polcoeffShiftIndex.index++;
        }
        float[][] polcoeff = (float[][]) auxData.polcoeff.getArray().getJavaArray();

        /* DPM #2.1.16-2 */
        P = polcoeff[lh.polcoeffShiftIndex.index][0];
        koeff = 1.0;
        for (i = 1; i < PPOL_NUM_ORDER; i++) {
            koeff *= eta_C;
            P += polcoeff[lh.polcoeffShiftIndex.index][i] * koeff;
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
     * @param result_flags the return values, <code>resultFlags[0]</code> contains low NN pressure flag (low_P_nn),
     *                     <code>resultFlags[1]</code> contains low polynomial pressure flag (low_P_poly),
     *                     <code>resultFlags[2]</code> contains pressure range flag (delta_p).
     */
    private void press_thresh(DpmPixel pixel, double pressure, boolean[] result_flags) {
        double delta_press_thresh; /* absolute threshold on pressure difference */
        FractIndex[] DP_Index = FractIndex.createArray(2);

        /* get proper threshold - DPM #2.1.2-2 */
        if (BitSetter.isFlagSet(pixel.l2flags, F_LAND)) {
            Interp.interpCoord(pixel.sun_zenith, auxData.DPthresh_land.getTab(0), DP_Index[0]);
            Interp.interpCoord(pixel.view_zenith, auxData.DPthresh_land.getTab(1), DP_Index[1]);
            delta_press_thresh = Interp.interpolate(auxData.DPthresh_land.getJavaArray(), DP_Index);
        } else {
            Interp.interpCoord(pixel.sun_zenith, auxData.DPthresh_ocean.getTab(0), DP_Index[0]);
            Interp.interpCoord(pixel.view_zenith, auxData.DPthresh_ocean.getTab(1), DP_Index[1]);
            delta_press_thresh = Interp.interpolate(auxData.DPthresh_ocean.getJavaArray(), DP_Index);
        }

        /* test NN pressure- DPM #2.1.2-4 */ // low_P_nn
        result_flags[0] = (pixel.press_ecmwf < pixel.press_ecmwf - delta_press_thresh); //changed in V7
        /* test polynomial pressure- DPM #2.1.2-3 */ // low_P_poly
        result_flags[1] = (pressure < pixel.press_ecmwf - delta_press_thresh);  //changed in V7
        /* test pressure range - DPM #2.1.2-5 */   // delta_p
        result_flags[2] = (Math.abs(pixel.press_ecmwf - pressure) > auxData.press_confidence); //changed in V7
    }

    /**
     * Computes the slope of Rayleigh-corrected reflectance.
     *
     * @param pixel        the pixel structure
     * @param result_flags the return values, <code>resultFlags[0]</code> contains low NN pressure flag (low_P_nn),
     *                     <code>resultFlags[1]</code> contains low polynomial pressure flag (low_P_poly),
     *                     <code>resultFlags[2]</code> contains pressure range flag (delta_p).
     */
    private void spec_slopes(DpmPixel pixel, boolean[] result_flags) {
        double rhorc_442_thr;   /* threshold on rayleigh corrected reflectance */
        double slope1, slope2;

        /* shorthand for access */
        long flags = pixel.l2flags;
        int saturated = pixel.SATURATED_F;

        double sins = Math.sin(RAD * pixel.sun_zenith);
        double sinv = Math.sin(RAD * pixel.view_zenith);

        /* Rayleigh phase function Fourier decomposition */
        rayleighCorrection.phase_rayleigh(pixel.mus, pixel.muv, sins, sinv, lh.phaseR);

        double press = pixel.press_ecmwf; /* DPM #2.1.7-1 v1.1 */

        /* Rayleigh optical thickness */
        rayleighCorrection.tau_rayleigh(press, lh.tauR); /* DPM #2.1.7-2 */

        /* Rayleigh reflectance - DPM #2.1.7-3 - v1.3 */
        rayleighCorrection.ref_rayleigh(pixel.delta_azimuth, pixel.sun_zenith, pixel.view_zenith,
                                        pixel.mus, pixel.muv, pixel.airMass, lh.phaseR, lh.tauR, lh.rhoRay);

        if (pixel.x == 4 && pixel.y == 4) {
            System.out.print("RhoRay = {");
            for (int i = 0; i < lh.rhoRay.length; i++) {
                System.out.print(lh.rhoRay[i] + ", ");
            }
            System.out.println("}");
        }

        /* DPM #2.1.7-4 */
        for (int band = bb412; band <= bb900; band++) {
            lh.rhoAg[band] = pixel.rho_toa[band] - lh.rhoRay[band];
        }

        /* Interpolate threshold on rayleigh corrected reflectance - DPM #2.1.7-9 */
        if (BitSetter.isFlagSet(flags, F_LAND)) {   /* land pixel */
            Interp.interpCoord(pixel.sun_zenith,
                               auxData.Rhorc_442_land_LUT.getTab(0),
                               lh.rhoRC442index[0]);
            Interp.interpCoord(pixel.view_zenith,
                               auxData.Rhorc_442_land_LUT.getTab(1),
                               lh.rhoRC442index[1]);
            Interp.interpCoord(pixel.delta_azimuth,
                               auxData.Rhorc_442_land_LUT.getTab(2),
                               lh.rhoRC442index[2]);
            rhorc_442_thr = Interp.interpolate(auxData.Rhorc_442_land_LUT.getJavaArray(), lh.rhoRC442index);
        } else {    /* water  pixel */
            Interp.interpCoord(pixel.sun_zenith,
                               auxData.Rhorc_442_ocean_LUT.getTab(0),
                               lh.rhoRC442index[0]);
            Interp.interpCoord(pixel.view_zenith,
                               auxData.Rhorc_442_ocean_LUT.getTab(1),
                               lh.rhoRC442index[1]);
            Interp.interpCoord(pixel.delta_azimuth,
                               auxData.Rhorc_442_ocean_LUT.getTab(2),
                               lh.rhoRC442index[2]);
            rhorc_442_thr = Interp.interpolate(auxData.Rhorc_442_ocean_LUT.getJavaArray(), lh.rhoRC442index);
        }
        /* END CHANGE 01 */

        boolean bright_f, slope1_f, slope2_f;
        bright_f = (lh.rhoAg[auxData.band_bright_n] >= rhorc_442_thr)
                || BitSetter.isFlagSet(saturated, auxData.band_bright_n); /* DPM #2.1.7-10 */

        /* Spectral slope processor.brr 1 */
        if (lh.rhoAg[auxData.band_slope_d_1] <= 0.0) {
            /* negative reflectance exception */
            slope1_f = false; /* DPM #2.1.7-6 */
        } else {
            /* DPM #2.1.7-5 */
            slope1 = lh.rhoAg[auxData.band_slope_n_1] / lh.rhoAg[auxData.band_slope_d_1];
            slope1_f = ((slope1 >= auxData.slope_1_low_thr) && (slope1 <= auxData.slope_1_high_thr))
                    || BitSetter.isFlagSet(saturated, auxData.band_slope_n_1);
        }

        /* Spectral slope processor.brr 2 */
        if (lh.rhoAg[auxData.band_slope_d_2] <= 0.0) {
            /* negative reflectance exception */
            slope2_f = false; /* DPM #2.1.7-8 */
        } else {
            /* DPM #2.1.7-7 */
            slope2 = lh.rhoAg[auxData.band_slope_n_2] / lh.rhoAg[auxData.band_slope_d_2];
            slope2_f = ((slope2 >= auxData.slope_2_low_thr) && (slope2 <= auxData.slope_2_high_thr))
                    || BitSetter.isFlagSet(saturated, auxData.band_slope_n_2);
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

    private static class LocalHelperVariables {
        /**
         * Local helper variable used in {@link CloudClassification#Comp_Pressure}.
         */
        final FractIndex[] cIndex = FractIndex.createArray(2);
        /**
         * Local helper variable used in {@link CloudClassification#Comp_Pressure}.
         */
        final FractIndex spectralShiftIndex = new FractIndex();
        /**
         * Local helper variable used in {@link CloudClassification#Comp_Pressure}.
         */
        final ReturnValue press_1 = new ReturnValue();
        /**
         * Local helper variable used in {@link CloudClassification#Comp_Pressure}.
         */
        final ReturnValue press_2 = new ReturnValue();
        /**
         * Local helper variable use in {@link CloudClassification#pressure_func}
         */
        final FractIndex polcoeffShiftIndex = new FractIndex();
        /**
         * Local helper variable used in {@link CloudClassification#classify_cloud}.
         */
        final ReturnValue press = new ReturnValue();
        /**
         * Interp. coordinates into table {@link L2AuxData#Rhorc_442_land_LUT} and
         * {@link L2AuxData#Rhorc_442_ocean_LUT}. Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final FractIndex[] rhoRC442index = FractIndex.createArray(3);
        /**
         * Rayleigh phase function coefficients, PR in DPM. Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final double[] phaseR = new double[RAYSCATT_NUM_SER];
        /**
         * Rayleigh optical thickness, tauR0 in DPM . Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final double[] tauR = new double[L1_BAND_NUM];
        /**
         * Rayleigh corrected reflectance. Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final double[] rhoAg = new double[L1_BAND_NUM];
        /**
         * Rayleigh correction. Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final double[] rhoRay = new double[L1_BAND_NUM];
        /**
         * Array of flags used as return value by some functions. Local helper variable used in {@link CloudClassification#spec_slopes}.
         */
        final boolean[] resultFlags = new boolean[3];
    }
    
    private static class ReturnValue {
        public double value;
        public boolean error;
    }
}
