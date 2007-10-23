/*
 * $Id: DpmPixel.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris.brr.dpm;

import org.esa.beam.meris.l2auxdata.Constants;


/**
 * The <code>DpmPixel</code> class is a collection of all relevant MERIS Level 2 DPM pixel variables.
 * <p/>
 * For convinience reasons, this class models a C-language-like structure which has only public fields and no methods.
 */
public final class DpmPixel {

    /**
     * Column pixel coordinate within product
     */
    public int x;
    /**
     * Line pixel coordinate within product
     */
    public int y;
    /**
     * Column coordinate within frame
     */
    public int i;
    /**
     * Line coordinate within frame
     */
    public int j;
    /**
     * Camera detector index
     */
    public int detector;
    /**
     * Latitude
     */
    public double lat;
    /**
     * Longitude
     */
    public double lon;
    /**
     * Viewing zenith angle
     */
    public double view_zenith;
    /**
     * Sun zenith angle
     */
    public double sun_zenith;
    /**
     * Delta_azimuth angle
     */
    public double delta_azimuth;
    /**
     * Sun_azimuth angle
     */
    public double sun_azimuth;
    /**
     * cosinus(thetas)
     */
    public double mus;
    /**
     * cosinus(thetav)
     */
    public double muv;
    /**
     * Air Mass (M in DPM)
     */
    public double airMass;
    /**
     * Altitude
     */
    public double altitude;
    /**
     * Zonal wind
     */
    public double windu;
    /**
     * Meridional wind
     */
    public double windv;
    /**
     * ECMWF pressure
     */
    public double press_ecmwf;
    /**
     * Ozone
     */
    public double ozone_ecmwf;
    /**
     * Variable for storing L1B flags
     */
    public int l1flags;
    /**
     * Variable for storing L2 flags
     */
    public long l2flags;
    /**
     * Set of flags showing for each band 0...15 if band is saturated
     */
    public int SATURATED_F;
    /**
     * output flags of Water Atm Corr
     */
    public int ANNOT_F;
    /**
     * Top of atmosphere radiance on # bands
     */
    public final double[] TOAR = new double[Constants.L1_BAND_NUM];
    /**
     * Gas corrected aerosol reflectances
     */
    public final double[] rho_ag = new double[Constants.L1_BAND_NUM];
    /**
     * TOA reflectance
     */
    public final double[] rho_toa = new double[Constants.L1_BAND_NUM];
    /**
     * Rayleigh corrected reflectances (Top Of Particles)
     */
    public final double[] rho_top = new double[Constants.L1_BAND_NUM];

    // new for ICOL

    /**
     * Rayleigh reflectance rho_0_R
     */
    public final double[] rhoR = new double[Constants.L1_BAND_NUM];
    /**
     * Rayleigh upward transmittance
     */
    public final double[] transRv = new double[Constants.L1_BAND_NUM];
    /**
     * Rayleigh downward transmittance
     */
    public final double[] transRs = new double[Constants.L1_BAND_NUM];
    /**
     * Rayleigh spherical albedo s_R
     */
    public final double[] sphalbR = new double[Constants.L1_BAND_NUM];
}




