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
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.util.ProductUtils;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jexp.ParseException;
import com.bc.jexp.Term;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class GapLessSdrOp extends MerisBasisOp {

    private Map<Band, Band> sdrBands;
    private Map<Band, Band> toarBands;
    private Term invalidTerm;
    
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
        
        try {
			invalidTerm = toarProduct.createTerm("l2_flags_p1.F_INVALID");
		} catch (ParseException e) {
			throw new OperatorException("Could not create Term for expression.", e);
		}
		
        return targetProduct;
    }

    @Override
    public void computeBand(Raster targetRaster, ProgressMonitor pm) throws OperatorException {

    	Rectangle rectangle = targetRaster.getRectangle();
        final int size = rectangle.height * rectangle.width;
        pm.beginTask("Processing frame...", size + 1);
        try {
        	Band targetBand = (Band) targetRaster.getRasterDataNode();
        	float sdr[] = (float[]) getRaster(sdrBands.get(targetBand), rectangle).getDataBuffer().getElems();
        	float toar[] = (float[]) getRaster(toarBands.get(targetBand), rectangle).getDataBuffer().getElems();
        	
        	boolean[] invalid = new boolean[size];
        	toarProduct.readBitmask(rectangle.x, rectangle.y,
    	    			rectangle.width, rectangle.height, invalidTerm, invalid, ProgressMonitor.NULL);
        	
        	float target[] = (float[]) targetRaster.getDataBuffer().getElems();
        	
        	pm.worked(1);

            for (int i = 0; i < size; i++) {
                if (invalid[i] || (sdr[i] != -1 && sdr[i] != 0)) {
                	target[i] = sdr[i];
                } else {
                	sdr[i] = toar[i];
                }
                pm.worked(1);
            }
        } catch (IOException e) {
            throw new OperatorException(e);
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