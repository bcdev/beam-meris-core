/*
 * $Id: FillAerosolOp.java,v 1.1 2007/05/14 12:26:01 marcoz Exp $
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ParameterConverter;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;

import com.bc.ceres.core.ProgressMonitor;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDomReader;
import com.thoughtworks.xstream.io.xml.xppdom.Xpp3Dom;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/05/14 12:26:01 $
 */
public class FillAerosolOp extends MerisBasisOp implements ParameterConverter {

    private TileRectCalculator rectCalculator;
    private Map<Band, Band> sourceBands;
    private Map<Band, Band> defaultBands;
    private Product validProduct;
    
    @SourceProduct(alias="input")
    private Product sourceProduct;
    @SourceProduct(alias="default")
    private Product defaultProduct;
    @TargetProduct
    private Product targetProduct;
    
    private Configuration config;



    /**
     * Configuration Elements (can be set from XML)
     */
    private static class Configuration {
        private int pixelWidth;
        private List<BandDesc> bands;

        public Configuration() {
            bands = new ArrayList<BandDesc>();
        }
    }

    public class BandDesc {
        String name;
        String validExp;
        String defaultBand;
    }

    public FillAerosolOp(OperatorSpi spi) {
        super(spi);
        config = new Configuration();
    }

    public void getParameterValues(Operator operator, Xpp3Dom configuration) throws OperatorException {
        // todo - implement
    }

    public void setParameterValues(Operator operator, Xpp3Dom configuration) throws OperatorException {
        XStream xStream = new XStream();
        xStream.setClassLoader(this.getClass().getClassLoader());
        xStream.alias(configuration.getName(), Configuration.class);
        xStream.alias("band", BandDesc.class);
        xStream.addImplicitCollection(Configuration.class, "bands");
        xStream.unmarshal(new XppDomReader(configuration), config);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        targetProduct = createCompatibleProduct(sourceProduct, "fill_aerosol", "MER_L2");
        sourceBands = new HashMap<Band, Band>(config.bands.size());
        defaultBands = new HashMap<Band, Band>(config.bands.size());
        
        Map<String, Object> parameters = new HashMap<String, Object>();
        BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[config.bands.size()];
        int i = 0;
        for (BandDesc bandDesc : config.bands) {
            Band srcBand = sourceProduct.getBand(bandDesc.name);
            Band targetBand = targetProduct.addBand(srcBand.getName(), ProductData.TYPE_FLOAT32);
            targetBand.setNoDataValue(-1);
            targetBand.setNoDataValueUsed(true);
            
            sourceBands.put(targetBand, srcBand);
            Band defaultBand = defaultProduct.getBand(bandDesc.defaultBand);
            defaultBands.put(targetBand, defaultBand);
            
            BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
    		bandDescriptor.name = srcBand.getName();
    		bandDescriptor.expression = bandDesc.validExp;
    		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
    		bandDescriptors[i] = bandDescriptor;
            
    		i++;
        }
        parameters.put("bandDescriptors", bandDescriptors);
        validProduct = GPF.createProduct(pm, "BandArithmetic", parameters, sourceProduct);
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", validProduct);
		
        rectCalculator = new TileRectCalculator(sourceProduct, config.pixelWidth, config.pixelWidth);
        return targetProduct;
    }
    
    @Override
    public void computeBand(Raster targetRaster,
            ProgressMonitor pm) throws OperatorException {

    	RasterDataNode targetBand = targetRaster.getRasterDataNode();
    	Rectangle targetRect = targetRaster.getRectangle();
        Rectangle sourceRect = rectCalculator.computeSourceRectangle(targetRect);
        pm.beginTask("Processing frame...", sourceRect.height + 1);
        try {
            Raster values = getRaster(sourceBands.get(targetBand), sourceRect);
            Raster defaultValues = getRaster(defaultBands.get(targetBand), sourceRect);
            Raster valid = getRaster(validProduct.getBand(targetBand.getName()), sourceRect);
			
            for (int y = targetRect.y; y < targetRect.y + targetRect.height; y++) {
                for (int x = targetRect.x; x < targetRect.x + targetRect.width; x++) {
                    if (valid.getBoolean(x, y)) {
                    	targetRaster.setFloat(x, y, values.getFloat(x, y));
                    } else {
                        double weigthSum = 0;
						double weigthSumTotal = 0;
                        double tauSum = 0;
                        final int iyStart = Math.max(y - config.pixelWidth,sourceRect.y);
                        final int iyEnd = Math.min(y + config.pixelWidth,sourceRect.y+sourceRect.height);
                        for (int iy = iyStart; iy < iyEnd; iy++) {
                            final int yDist = iy - y;
                            final int ixStart = Math.max(x - config.pixelWidth,sourceRect.x);
                            final int ixEnd = Math.min(x + config.pixelWidth,sourceRect.x+sourceRect.width);
                            for (int ix = ixStart; ix < ixEnd; ix++) {
                            	final int xDist = ix - x;
								if (xDist == 0 && yDist == 0) {
									continue;
								}
								final double weight = 1.0 / (xDist * xDist + yDist * yDist);
								weigthSumTotal += weight;
								if (valid.getBoolean(ix, iy)) {
									weigthSum += weight;
									tauSum += values.getFloat(ix, iy) * weight;
                                }
                            }
                        }
                        if (weigthSum > 0) {
							final double tauTemp = tauSum/weigthSum;
							final double ww = weigthSum/weigthSumTotal;
							final double tau = ww * tauTemp + (1.0 - ww)* defaultValues.getFloat(x, y);
							targetRaster.setFloat(x, y, (float) tau);
                        } else {
                        	targetRaster.setFloat(x, y, defaultValues.getFloat(x, y));
                        }
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
            super(FillAerosolOp.class, "FillAerosol");
        }
    }
}