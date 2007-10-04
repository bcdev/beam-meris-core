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

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.operator.util.HelperFunctions;
import org.esa.beam.util.FlagWrapper;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;


/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.3 $ $Date: 2007/04/27 15:30:03 $
 */
public class RayleighCorrectionOp extends MerisBasisOp implements Constants {

    public static final String BRR_BAND_PREFIX = "brr";
    public static final String RAY_CORR_FLAGS = "ray_corr_flags";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    private DpmConfig dpmConfig;
    protected L2AuxData auxData;
    protected FlagWrapper brrFlags;
    protected RayleighCorrection rayleighCorrection;
    
    private Band isLandBand;
    private Band[] brrBands;
    private Band flagBand;
    
    private Band[] transRvBands;
    private Band[] transRsBands;
    private Band[] tauRBands;
    private Band[] sphAlbRBands;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="input")
    private Product gascorProduct;
    @SourceProduct(alias="land")
    private Product landProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String configFile = MERIS_L2_CONF;
    @Parameter
    boolean correctWater = false;
    @Parameter
    boolean exportRayCoeffs = false;
	
    
    @Override
    public Product initialize() throws OperatorException {
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }
        try {
            auxData = new L2AuxData(dpmConfig, l1bProduct);
            rayleighCorrection = new RayleighCorrection(auxData);
        } catch (Exception e) {
            throw new OperatorException("could not load L2Auxdata", e);
        }
        return createTargetProduct();
    }

    private Product createTargetProduct() throws OperatorException {
    	targetProduct = createCompatibleProduct(l1bProduct, "MER", "MER_L2");

    	brrBands = addBandGroup(BRR_BAND_PREFIX);

        flagBand = targetProduct.addBand(RAY_CORR_FLAGS, ProductData.TYPE_INT16);
        FlagCoding flagCoding = createFlagCoding();
        flagBand.setFlagCoding(flagCoding);
        targetProduct.addFlagCoding(flagCoding);
        
        if (exportRayCoeffs) {
	        transRvBands = addBandGroup("transRv");
    	    transRsBands = addBandGroup("transRs");
        	tauRBands = addBandGroup("tauR");
	        sphAlbRBands = addBandGroup("sphAlbR");
		}
        isLandBand = createBooleanBandForExpression(LandClassificationOp.LAND_FLAGS + ".F_LANDCONS", landProduct);
		
        return targetProduct;
    }
    
    private Band createBooleanBandForExpression(String expression,
			Product product) throws OperatorException {
    	
		Map<String, Object> parameters = new HashMap<String, Object>();
		BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[1];
		BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
		bandDescriptor.name = "bBand";
		bandDescriptor.expression = expression;
		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
		bandDescriptors[0] = bandDescriptor;
		parameters.put("bandDescriptors", bandDescriptors);

		Product expProduct = GPF.createProduct("BandArithmetic", parameters, product);
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", expProduct);
		return expProduct.getBand("bBand");
	}
    
    private Band[] addBandGroup(String prefix) {
        Band[] bands = new Band[L1_BAND_NUM];
        for (int i = 0; i < bands.length; i++) {
            if (i == bb11 || i == bb15) {
                continue;
            }
            final Band inBand = l1bProduct.getBandAt(i);

            bands[i] = targetProduct.addBand(prefix + "_" + (i + 1), ProductData.TYPE_FLOAT32);
            ProductUtils.copySpectralAttributes(inBand, bands[i]);
            bands[i].setNoDataValueUsed(true);
            bands[i].setNoDataValue(BAD_VALUE);
        }
        return bands;
    }

    private FlagCoding createFlagCoding() {
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
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle) throws OperatorException {
        ProgressMonitor pm = createProgressMonitor();
        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
            Tile sza = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle);
            Tile vza = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle);
            Tile saa = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle);
            Tile vaa = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle);
            Tile altitude = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME), rectangle);
            Tile ecmwfPressure = getSourceTile(l1bProduct.getTiePointGrid("atm_press"), rectangle);
			
            Tile[] rhoNg = new Tile[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
			for (int i = 0; i < rhoNg.length; i++) {
			    if (i == bb11 || i == bb15) {
			        continue;
			    }
			    rhoNg[i] = getSourceTile(gascorProduct.getBand(GaseousCorrectionOp.RHO_NG_BAND_PREFIX + "_" + (i + 1)), rectangle);
			}
			Tile isLandCons = getSourceTile(isLandBand, rectangle);

			Tile[] transRvData = null;
			Tile[] transRsData = null;
			Tile[] tauRData = null;
			Tile[] sphAlbRData = null;
            if (exportRayCoeffs) {
				transRvData = getTargetTileGroup(transRvBands, targetTiles);
				transRsData = getTargetTileGroup(transRsBands, targetTiles);
				tauRData = getTargetTileGroup(tauRBands, targetTiles);
				sphAlbRData = getTargetTileGroup(sphAlbRBands, targetTiles);
            }
            Tile[] brr = getTargetTileGroup(brrBands, targetTiles);
            brrFlags = new FlagWrapper.Short((short[]) getSourceTile(flagBand, rectangle).getRawSampleData().getElems());
            
            boolean[][] do_corr = new boolean[SUBWIN_HEIGHT][SUBWIN_WIDTH];
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
		    
            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y += Constants.SUBWIN_HEIGHT) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x += Constants.SUBWIN_WIDTH) {
                    final int xWinEnd = Math.min(rectangle.x + rectangle.width, x + Constants.SUBWIN_WIDTH) - 1;
                    final int yWinEnd = Math.min(rectangle.y + rectangle.height, y + Constants.SUBWIN_HEIGHT) - 1;
                    boolean correctPixel = false;
					
					for (int iy = y; iy <= yWinEnd; iy++) {
					    for (int ix = x; ix <= xWinEnd; ix++) {
					        if (rhoNg[0].getSampleFloat(ix, iy) != BAD_VALUE && (correctWater || isLandCons.getSampleBoolean(ix, iy))) {
					            correctPixel = true;
					            do_corr[iy - y][ix - x] = true;
					        } else {
					            do_corr[iy - y][ix - x] = false;
					            for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
								    if (bandId == bb11 || bandId == bb15) {
								        continue;
								    }
								    brr[bandId].setSample(ix, iy, BAD_VALUE);
								}
					        }
					    }
					}
					
					if (correctPixel) {
					    /* average geometry, ozone for window DPM : just use corner pixel ! */
					    final double szaRad = sza.getSampleFloat(x, y) * MathUtils.DTOR;
					    final double vzaRad = vza.getSampleFloat(x, y) * MathUtils.DTOR;
						final double sins = Math.sin(szaRad);
						final double sinv = Math.sin(vzaRad);
					    final double mus = Math.cos(szaRad);
					    final double muv = Math.cos(vzaRad);
					    final double deltaAzimuth = HelperFunctions.computeAzimuthDifference(vaa.getSampleFloat(x, y), saa.getSampleFloat(x, y));
					
					    /*
					    * 2. Rayleigh corrections (DPM section 7.3.3.3.2, step 2.6.15)
					    */
					    final double press = HelperFunctions.correctEcmwfPressure(ecmwfPressure.getSampleFloat(x, y),
					                                                              altitude.getSampleFloat(x, y), 
					                                                              auxData.press_scale_height); /* DPM #2.6.15.1-3 */
					    final double airMass = HelperFunctions.calculateAirMassMusMuv(muv, mus);
					
					    /* Rayleigh phase function Fourier decomposition */
					    rayleighCorrection.phase_rayleigh(mus, muv, sins, sinv, phaseR);
					
					    /* Rayleigh optical thickness */
					    rayleighCorrection.tau_rayleigh(press, tauR);
					
					    /* Rayleigh reflectance*/
					    rayleighCorrection.ref_rayleigh(deltaAzimuth, sza.getSampleFloat(x, y), vza.getSampleFloat(x, y), mus, muv,
					                                    airMass, phaseR, tauR, rhoR);
					
					    /* Rayleigh transmittance */
					    rayleighCorrection.trans_rayleigh(mus, tauR, transRs);
					    rayleighCorrection.trans_rayleigh(muv, tauR, transRv);
					
					    /* Rayleigh spherical albedo */
					    rayleighCorrection.sphAlb_rayleigh(tauR, sphAlbR);
					
					    /* process each pixel */
					    for (int iy = y; iy <= yWinEnd; iy++) {
					        for (int ix = x; ix <= xWinEnd; ix++) {
					            int index = TileRectCalculator.convertToIndex(ix, iy, rectangle);
					            if (do_corr[iy - y][ix - x]) {
					                /* Rayleigh correction for each pixel */
					                rayleighCorrection.corr_rayleigh(rhoR, sphAlbR, transRs, transRv,
					                                                 rhoNg, brr, ix, iy); /*  (2.6.15.4) */
					
					                /* flag negative Rayleigh-corrected reflectance */
					                for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
					                    switch (bandId) {
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
					                            if (brr[bandId].getSampleFloat(ix, iy) <= 0.) {
					                                /* set annotation flag for reflectance product - v4.2 */
					                                brrFlags.set(index, (bandId <= bb760 ? bandId : bandId - 1));
					                            }
					                            break;
					                        default:
					                            break;
					                    }
					                }
					                if (exportRayCoeffs) {
					                	for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
					                		if (bandId == bb11 || bandId == bb15) {
					                			continue;
					                		}
					                		transRvData[bandId].setSample(ix, iy, transRv[bandId]);
					                		transRsData[bandId].setSample(ix, iy, transRs[bandId]);
					                		tauRData[bandId].setSample(ix, iy, tauR[bandId]);
					                		sphAlbRData[bandId].setSample(ix, iy, sphAlbR[bandId]);
					                	}
					                }
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
    
    private Tile[] getTargetTileGroup(Band[] bands, Map<Band, Tile> targetTiles)  {
        final Tile[] bandRaster = new Tile[L1_BAND_NUM];
        for (int i = 0; i < bands.length; i++) {
            Band band = bands[i];
            if (band != null) {
                bandRaster[i] = targetTiles.get(band);
            }
        }
        return bandRaster;
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(RayleighCorrectionOp.class, "Meris.RayleighCorrection");
        }
    }
}
