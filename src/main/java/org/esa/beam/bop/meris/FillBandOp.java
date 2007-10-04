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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.ParameterConverter;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDomReader;
import com.thoughtworks.xstream.io.xml.xppdom.Xpp3Dom;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/05/14 12:26:01 $
 */
public class FillBandOp extends MerisBasisOp implements ParameterConverter {
	
	private Map<Band, Float> defaultMap;
	/**
     * Configuration Elements (can be set from XML)
     */
	private static class Configuration {
		private List<BandDesc> bands;
		
		public Configuration() {
			bands = new ArrayList<BandDesc>();
		}
	}
	public class BandDesc {
		String name;
		float defaultValue;
	}
	private Configuration config;
	
	@SourceProduct(alias="input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;
	
	
	public FillBandOp() {
		config = new Configuration();
	}

	public Object getConfigurationObject() {
		return config;
	}

    public void getParameterValues(Operator operator, Xpp3Dom configuration) throws OperatorException {
        // todo - implement        
    }

    public void setParameterValues(Operator operator, Xpp3Dom parameterDom) throws OperatorException {
        XStream xStream = new XStream();
        xStream.setClassLoader(this.getClass().getClassLoader());
        xStream.alias(parameterDom.getName(), Configuration.class);
        xStream.alias("band", BandDesc.class);
        xStream.addImplicitCollection(Configuration.class, "bands");
        xStream.unmarshal(new XppDomReader(parameterDom), config);
    }

	@Override
    public Product initialize() throws OperatorException {
        
		targetProduct = createCompatibleProduct(sourceProduct, "fille_band", "FILL");
		defaultMap = new HashMap<Band, Float>(config.bands.size());
		for (BandDesc bandDesc: config.bands) {
			Band targetBand = targetProduct.addBand(bandDesc.name, ProductData.TYPE_FLOAT32);
			defaultMap.put(targetBand, bandDesc.defaultValue);
		}
		return targetProduct;
	}
	
	@Override
    public void computeTile(Band band, Tile targetTile) throws OperatorException {
	    Rectangle targetRect = targetTile.getRectangle();
	    float[] outValues = (float[]) getSourceTile(band, targetRect).getRawSampleData().getElems();
	    final float defaultValue = defaultMap.get(band);
	    
	    Arrays.fill(outValues, defaultValue);
    }
	
	public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(FillBandOp.class, "FillBand");
        }
    }
}