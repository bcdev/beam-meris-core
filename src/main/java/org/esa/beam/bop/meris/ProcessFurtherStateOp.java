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
package org.esa.beam.bop.meris;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;


/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class ProcessFurtherStateOp extends MerisBasisOp {

    private static final String PROCESS_FURTHER_BAND_NAME = "process_further_state";

    private static final String[] EXPRESSIONS = {
            "not ($cloud.combined_cloud.cloud or $cloud.combined_cloud.cloud_edge or " +
            "$cloud.combined_cloud.cloud_shadow) and $brr.l2_flags_p1.F_LANDCONS",
            // TODO: remove L1 land flag from process further
            "$l1b.l1_flags.LAND_OCEAN and not $brr.l2_flags_p1.F_LANDCONS",
            "$cloud.combined_cloud.cloud_edge or $cloud.combined_cloud.cloud_shadow",
            "$cloud.combined_cloud.cloud",
            "not $l1b.l1_flags.LAND_OCEAN and not $brr.l2_flags_p1.F_LANDCONS",
            "$cloud.combined_cloud.snow",
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
    public Product initialize() throws OperatorException {
        targetProduct = createCompatibleProduct(l1bProduct, "MER", "MER_L2");
        Band processFurtherBand = targetProduct.addBand(PROCESS_FURTHER_BAND_NAME, ProductData.TYPE_INT8);
        processFurtherBand.setDescription("process further state");

        Map<String, Object> parameters = new HashMap<String, Object>();
        BandArithmeticOp.BandDescriptor[] bandDescriptions = new BandArithmeticOp.BandDescriptor[EXPRESSIONS.length];
        for (int i = 0; i < EXPRESSIONS.length; i++) {
        	BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
			bandDescriptor.name = "b"+i;
			bandDescriptor.expression = EXPRESSIONS[i];
			bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
			bandDescriptions[i] = bandDescriptor;
    	}
		parameters.put("bandDescriptors", bandDescriptions);
		
		Map<String, Product> products = new HashMap<String, Product>();
		products.put(getContext().getSourceProductId(l1bProduct), l1bProduct);
		products.put(getContext().getSourceProductId(brrProduct), brrProduct);
		products.put(getContext().getSourceProductId(cloudProduct), cloudProduct);
		Product expressionProduct = GPF.createProduct("BandArithmetic", parameters, products, createProgressMonitor());
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", expressionProduct);
		
		bands = expressionProduct.getBands();
        
        return targetProduct;
    }
    
    @Override
    public void computeTile(Band band, Tile targetTile) throws OperatorException {

    	Rectangle rectangle = targetTile.getRectangle();
    	Tile[] isValid = new Tile[EXPRESSIONS.length];
    	for (int i = 0; i < isValid.length; i++) {
    	    isValid[i] = getSourceTile(bands[i], rectangle);
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

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(ProcessFurtherStateOp.class, "Meris.ProcessFurtherState");
        }
    }
}
