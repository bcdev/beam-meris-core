/*
 * $Id: ProcessFurtherStateOp.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
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
package org.esa.beam.meris;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.common.BandMathOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.bc.ceres.core.ProgressMonitor;


/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class ProcessFurtherStateOp extends MerisBasisOp {

    private static final String PROCESS_FURTHER_BAND_NAME = "process_further_state";

    private static final String[] EXPRESSIONS = {
            // 0 == cloud-free land
            "not ($cloud.combined_cloud.cloud or $cloud.combined_cloud.cloud_edge or " +
            "$cloud.combined_cloud.cloud_shadow) and $brr.l2_flags_p1.F_LANDCONS",
            // 1 == flooded area
            "($l1b.l1_flags.LAND_OCEAN or (not $l1b.l1_flags.LAND_OCEAN and $l1b.dem_alt > -50))" +
            " and  not $brr.l2_flags_p1.F_LANDCONS and not ($cloud.combined_cloud.cloud or $cloud.combined_cloud.cloud_edge or " +
            "$cloud.combined_cloud.cloud_shadow)",
            // 2 == Cloud suspicion (cloud edge or shadow)
            "$cloud.combined_cloud.cloud_edge or $cloud.combined_cloud.cloud_shadow",
            // 3 == cloud
            "$cloud.combined_cloud.cloud",
            // 4 == water
            "not $l1b.l1_flags.LAND_OCEAN and not $brr.l2_flags_p1.F_LANDCONS",
            // 5 == snow
            "$cloud.combined_cloud.snow",
            // 6 == invalid
            "$l1b.l1_flags.INVALID"};

    private Band[] bands;
    
    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="brr")
    private Product brrProduct;
    @SourceProduct(alias="cloud")
    private Product cloudProduct;
    @TargetProduct
    private Product targetProduct;

    @Override
    public void initialize() throws OperatorException {
        targetProduct = createCompatibleProduct(l1bProduct, "MER", "MER_L2");
        Band processFurtherBand = targetProduct.addBand(PROCESS_FURTHER_BAND_NAME, ProductData.TYPE_INT8);
        processFurtherBand.setDescription("process further state");

        Map<String, Object> parameters = new HashMap<String, Object>();
        BandMathOp.BandDescriptor[] bandDescriptions = new BandMathOp.BandDescriptor[EXPRESSIONS.length];
        for (int i = 0; i < EXPRESSIONS.length; i++) {
        	BandMathOp.BandDescriptor bandDescriptor = new BandMathOp.BandDescriptor();
			bandDescriptor.name = "b"+i;
			bandDescriptor.expression = EXPRESSIONS[i];
			bandDescriptor.type = ProductData.TYPESTRING_INT8;
			bandDescriptions[i] = bandDescriptor;
    	}
		parameters.put("targetBands", bandDescriptions);
		
		Map<String, Product> products = new HashMap<String, Product>();
		products.put(getSourceProductId(l1bProduct), l1bProduct);
		products.put(getSourceProductId(brrProduct), brrProduct);
		products.put(getSourceProductId(cloudProduct), cloudProduct);
		Product expressionProduct = GPF.createProduct("BandArithmetic", parameters, products);
		
		bands = expressionProduct.getBands();
    }
    
    @Override
    public void computeTile(Band band, Tile targetTile, ProgressMonitor pm) throws OperatorException {

    	Rectangle rectangle = targetTile.getRectangle();
    	Tile[] isValid = new Tile[EXPRESSIONS.length];
    	for (int i = 0; i < isValid.length; i++) {
    	    isValid[i] = getSourceTile(bands[i], rectangle, pm);
    	}

    	for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
    	    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
    	        for (int j = 0; j < EXPRESSIONS.length; j++) {
    	            if (isValid[j].getSampleBoolean(x, y)) {
    	                targetTile.setSample(x, y, j);
    	            }
    	        }
    	    }
    	}
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ProcessFurtherStateOp.class, "Meris.ProcessFurtherState");
        }
    }
}
