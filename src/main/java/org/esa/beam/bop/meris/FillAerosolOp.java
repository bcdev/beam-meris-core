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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDomReader;
import com.thoughtworks.xstream.io.xml.xppdom.Xpp3Dom;
import org.esa.beam.framework.gpf.support.CachingOperator;
import org.esa.beam.framework.gpf.support.ProductDataCache;
import org.esa.beam.framework.gpf.support.SourceDataRetriever;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/05/14 12:26:01 $
 */
public class FillAerosolOp extends CachingOperator implements ParameterConverter {

    private TileRectCalculator rectCalculator;
    private Map<Band, Band> bandMap;
    private Map<Band, SourceDataRetriever> dataRetrieverMap;
    private Map<Band, float[]> valueMap;
    private Map<Band, boolean[]> validMap;
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
    }

    public FillAerosolOp(OperatorSpi spi) {
        super(spi);
        config = new Configuration();
    }

    @Override
    public boolean isComputingAllBandsAtOnce() {
        return false;
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
    public Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        Product srcProduct = getContext().getSourceProduct("input");

        final int sceneWidth = srcProduct.getSceneRasterWidth();
        final int sceneHeight = srcProduct.getSceneRasterHeight();

        targetProduct = new Product("MER_SDR", "MER_SDR", sceneWidth, sceneHeight);
        bandMap = new HashMap<Band, Band>(config.bands.size());
        for (BandDesc bandDesc : config.bands) {
            Band srcBand = srcProduct.getBand(bandDesc.name);
            Band targetBand = targetProduct.addBand(srcBand.getName(), ProductData.TYPE_FLOAT32);
            targetBand.setNoDataValue(-1);
            targetBand.setNoDataValueUsed(true);
            bandMap.put(targetBand, srcBand);
        }
        return targetProduct;
    }

    @Override
    public void initSourceRetriever() {
        final Product srcProduct = getContext().getSourceProduct("input");

        rectCalculator = new TileRectCalculator(srcProduct, config.pixelWidth, config.pixelWidth);
        Dimension maxSourceSize = rectCalculator.computeMaxSourceSize(maxTileSize);

        dataRetrieverMap = new HashMap<Band, SourceDataRetriever>(config.bands.size());
        valueMap = new HashMap<Band, float[]>(config.bands.size());
        validMap = new HashMap<Band, boolean[]>(config.bands.size());
        for (BandDesc bandDesc : config.bands) {
            SourceDataRetriever dataRetriever = new SourceDataRetriever(maxSourceSize);
            Band targetBand = targetProduct.getBand(bandDesc.name);
            dataRetrieverMap.put(targetBand, dataRetriever);

            Band srcBand = bandMap.get(targetBand);

            float[] values = dataRetriever.connectFloat(srcBand);
            valueMap.put(targetBand, values);

            boolean[] valid = dataRetriever.connectBooleanExpression(srcProduct, bandDesc.validExp);
            validMap.put(targetBand, valid);
        }
    }

    @Override
    public void computeTile(Band targetBand, Rectangle targetRect,
                            ProductDataCache cache, ProgressMonitor pm) throws OperatorException {

        Rectangle sourceRect = rectCalculator.computeSourceRectangle(targetRect);
        pm.beginTask("Processing frame...", sourceRect.height + 1);
        try {
            SourceDataRetriever dataRetriever = dataRetrieverMap.get(targetBand);
            dataRetriever.readData(sourceRect, new SubProgressMonitor(pm, 1));

            float[] inValues = valueMap.get(targetBand);
            boolean[] valid = validMap.get(targetBand);
            float[] outValues = (float[]) cache.createData(targetBand).getElems();

            int targetIndex = 0;
            for (int y = targetRect.y; y < targetRect.y + targetRect.height; y++) {
                for (int x = targetRect.x; x < targetRect.x + targetRect.width; x++) {
                    final int sourceIndex = TileRectCalculator.convertToIndex(x, y, sourceRect);
                    if (valid[sourceIndex]) {
                        outValues[targetIndex] = inValues[sourceIndex];
                    } else {
                        double weigthSum = 0;
                        double tauSum = 0;
                        for (int iy = y - config.pixelWidth; iy < y + config.pixelWidth; iy++) {
                            final int yDist = iy - y;
                            for (int ix = x - config.pixelWidth; ix < x + config.pixelWidth; ix++) {
                                if (sourceRect.contains(ix, iy)) {
                                    final int internalIndex = TileRectCalculator.convertToIndex(ix, iy, sourceRect);
                                    final int xDist = ix - x;
                                    if (valid[internalIndex] && xDist != 0 && yDist != 0) {
                                        final double weight = 1.0 / (xDist * xDist + yDist * yDist);
                                        weigthSum += weight;
                                        tauSum += inValues[internalIndex] * weight;
                                    }
                                }
                            }
                        }
                        if (weigthSum > 0) {
                            outValues[targetIndex] = (float) (tauSum / weigthSum);
                        } else {
                            outValues[targetIndex] = -1;
                        }
                    }
                    targetIndex++;
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
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