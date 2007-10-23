/*
 * $Id: SmileCorrectionOp.java,v 1.2 2007/04/26 11:53:53 marcoz Exp $
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
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.meris.l2auxdata.DpmConfig;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.util.ProductUtils;

import com.bc.ceres.core.ProgressMonitor;


/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.2 $ $Date: 2007/04/26 11:53:53 $
 */
public class SmileCorrectionOp extends MerisBasisOp implements Constants {

    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    private DpmConfig dpmConfig;
    private L2AuxData auxData;

//    private float[][] rho;
//    private short[] detectorIndex;
    private Band isLandBand;

    private Band[] rhoCorectedBands;
//    private float[][] rhoCorrected;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="gascor")
    private Product gascorProduct;
    @SourceProduct(alias="land")
    private Product landProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String configFile = MERIS_L2_CONF;


    @Override
    public Product initialize() throws OperatorException {
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

    private Product createTargetProduct() throws OperatorException {
        targetProduct = createCompatibleProduct(gascorProduct, "MER", "MER_L2");
        rhoCorectedBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < rhoCorectedBands.length; i++) {
            Band inBand = gascorProduct.getBandAt(i);

            rhoCorectedBands[i] = targetProduct.addBand(inBand.getName(), ProductData.TYPE_FLOAT32);
            ProductUtils.copySpectralAttributes(inBand, rhoCorectedBands[i]);
            rhoCorectedBands[i].setNoDataValueUsed(true);
            rhoCorectedBands[i].setNoDataValue(BAD_VALUE);
        }
        
//        rhoCorrected = new float[rhoCorectedBands.length][0];
//        rho = new float[rhoCorectedBands.length][0];
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
		parameters.put("targetBands", bandDescriptors);

		Product expProduct = GPF.createProduct("BandArithmetic", parameters, product);
		addSourceProduct("x", expProduct);
		return expProduct.getBand("bBand");
	}

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {
        pm.beginTask("Processing frame...", rectangle.height);
        try {
        	Tile detectorIndex = getSourceTile(l1bProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME), rectangle, pm);
        	Tile[] rho = new Tile[rhoCorectedBands.length];
            for (int i = 0; i < rhoCorectedBands.length; i++) {
                rho[i] = getSourceTile(gascorProduct.getBand(rhoCorectedBands[i].getName()), rectangle, pm);
            }
            Tile isLandCons = getSourceTile(isLandBand, rectangle, pm);
            
            Tile[] rhoCorrected = new Tile[rhoCorectedBands.length];
            for (int i = 0; i < rhoCorectedBands.length; i++) {
                rhoCorrected[i] = targetTiles.get(rhoCorectedBands[i]);
            }

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
					if (rho[0].getSampleFloat(x, y) == BAD_VALUE) {
						for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
							rhoCorrected[bandId].setSample(x, y, BAD_VALUE);
						}
					} else {
						L2AuxData.SmileParams params; 
						if (isLandCons.getSampleBoolean(x, y)) {
							params = auxData.land_smile_params;
						} else {
							params = auxData.water_smile_params;
						}
						for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
				            if (params.enabled[bandId]) {
				                /* DPM #2.1.6-3 */
				                final int bandMin = params.derivative_band_id[bandId][0];
				                final int bandMax = params.derivative_band_id[bandId][1];
				                final int detector = detectorIndex.getSampleInt(x, y);
				                final double derive = (rho[bandMax].getSampleFloat(x, y) - rho[bandMin].getSampleFloat(x, y))
				                        / (auxData.central_wavelength[bandMax][detector] - auxData.central_wavelength[bandMin][detector]);
				                /* DPM #2.1.6-4 */
				                final double simleCorrectValue = rho[bandId].getSampleFloat(x, y)
				                        + derive
				                        * (auxData.theoretical_wavelength[bandId] - auxData.central_wavelength[bandId][detector]);
				                rhoCorrected[bandId].setSample(x, y, (float)simleCorrectValue);
				            } else {
				                /* DPM #2.1.6-5 */
				            	rhoCorrected[bandId].setSample(x, y, rho[bandId].getSampleFloat(x, y));
				            }
				        }
					}
				}
				pm.worked(1);
            }
		} finally {
            pm.done();
        }
    }


    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SmileCorrectionOp.class, "Meris.SmileCorrection");
        }
    }
}
