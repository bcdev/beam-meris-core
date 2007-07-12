/*
 * $Id: GapLessSdrOp.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
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
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.util.ProductUtils;

import com.bc.ceres.core.ProgressMonitor;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class GapLessSdrOp extends MerisBasisOp {

    private Map<Band, Band> sdrBands;
    private Map<Band, Band> toarBands;
    private Band invalidBand;
    
    @SourceProduct(alias="sdr")
    private Product sdrProduct;
    @SourceProduct(alias="toar")
    private Product toarProduct;
    @TargetProduct
    private Product targetProduct;

    public GapLessSdrOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        targetProduct = createCompatibleProduct(sdrProduct, "MER_SDR", "MER_SDR");

        sdrBands = new HashMap<Band, Band>();
        toarBands = new HashMap<Band, Band>();
        String[] bandNames = sdrProduct.getBandNames();
        for (final String bandName : bandNames) {
            if (bandName.startsWith("sdr_") && !bandName.endsWith("flags")) {
                Band targetBand = ProductUtils.copyBand(bandName, sdrProduct, targetProduct);
                Band sdrBand = sdrProduct.getBand(bandName);
                targetBand.setNoDataValueUsed(sdrBand.isNoDataValueUsed());
                targetBand.setNoDataValue(sdrBand.getNoDataValue());
                sdrBands.put(targetBand, sdrBand);
                
                final String toarBandName = bandName.replaceFirst("sdr", "toar");
    			Band toarBand = toarProduct.getBand(toarBandName);
                toarBands.put(targetBand, toarBand);
            }
        }
        invalidBand = createBooleanBandForExpression("l2_flags_p1.F_INVALID", pm);
        return targetProduct;
    }
    
    private Band createBooleanBandForExpression(String expression, ProgressMonitor pm) throws OperatorException {
		Map<String, Object> parameters = new HashMap<String, Object>();
        BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[1];
        BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
		bandDescriptor.name = "bBand";
		bandDescriptor.expression = expression;
		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
		bandDescriptors[0] = bandDescriptor;
		parameters.put("bandDescriptors", bandDescriptors);
		
		Product validLandProduct = GPF.createProduct("BandArithmetic", parameters, toarProduct, pm);
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", validLandProduct);
		return validLandProduct.getBand("bBand");
	}

    @Override
    public void computeBand(Raster targetRaster, ProgressMonitor pm) throws OperatorException {

    	Rectangle rectangle = targetRaster.getRectangle();
        pm.beginTask("Processing frame...", rectangle.height + 1);
        try {
        	Band targetBand = (Band) targetRaster.getRasterDataNode();
        	Raster sdrRaster = getRaster(sdrBands.get(targetBand), rectangle);
        	Raster toarRaster = getRaster(toarBands.get(targetBand), rectangle);
        	Raster invalid = getRaster(invalidBand, rectangle);
        	
        	pm.worked(1);

        	for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
					final float sdr = sdrRaster.getFloat(x, y);
					if (invalid.getBoolean(x, y) || (sdr != -1 && sdr != 0)) {
						targetRaster.setFloat(x, y, sdr);
					} else {
						targetRaster.setFloat(x, y, toarRaster.getFloat(x, y));

					}
				}
				pm.worked(1);
			}
        } finally {
            pm.done();
        }
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(GapLessSdrOp.class, "Meris.GapLessSdr");
        }
    }
}