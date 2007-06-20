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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.support.CachingOperator;
import org.esa.beam.framework.gpf.support.ProductDataCache;
import org.esa.beam.framework.gpf.support.SourceDataRetriever;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.util.ProductUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class GapLessSdrOp extends CachingOperator {

    private SourceDataRetriever dataRetriever;
    private Band[] sdrBands;
    private Map<Band, Band> bandMap;
    private float[][] toar;
    private boolean[] invalid;
    private double scalingFactor;

    public GapLessSdrOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public boolean isComputingAllBandsAtOnce() {
        return true;
    }

    @Override
    public Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        Product sdrProduct = getContext().getSourceProduct("sdr");

        final int sceneWidth = sdrProduct.getSceneRasterWidth();
        final int sceneHeight = sdrProduct.getSceneRasterHeight();

        Product targetProduct = new Product("MER_SDR", "MER_SDR", sceneWidth, sceneHeight);
        List<Band> sdrBandList = new ArrayList<Band>();
        bandMap = new HashMap<Band, Band>();
        String[] bandNames = sdrProduct.getBandNames();
        for (final String bandName : bandNames) {
            if (bandName.startsWith("sdr_") && !bandName.endsWith("flags")) {
                Band newBand = ProductUtils.copyBand(bandName, sdrProduct, targetProduct);
                Band oldBand = sdrProduct.getBand(bandName);
                newBand.setNoDataValueUsed(oldBand.isNoDataValueUsed());
                newBand.setNoDataValue(oldBand.getNoDataValue());
                sdrBandList.add(newBand);
                bandMap.put(newBand, oldBand);
                scalingFactor = oldBand.getScalingFactor();
            }
        }
        sdrBands = sdrBandList.toArray(new Band[sdrBandList.size()]);
        return targetProduct;
    }

    @Override
    public void initSourceRetriever() {
        final Product toarProduct = getContext().getSourceProduct("toar");
        dataRetriever = new SourceDataRetriever(maxTileSize);

        invalid = dataRetriever.connectBooleanExpression(toarProduct, "l2_flags_p1.F_INVALID");

        toar = new float[sdrBands.length][0];
        for (int i = 0; i < sdrBands.length; i++) {
            final String bandName = sdrBands[i].getName();
            final String toarBandName = bandName.replaceFirst("sdr", "toar");
            final Band toarBand = toarProduct.getBand(toarBandName);
            toar[i] = dataRetriever.connectFloat(toarBand);
        }
    }

    @Override
    public void computeTiles(Rectangle rect,
                             ProductDataCache cache, ProgressMonitor pm) throws OperatorException {

        final int size = rect.height * rect.width;
        pm.beginTask("Processing frame...", size + sdrBands.length + 1);
        try {
            dataRetriever.readData(rect, new SubProgressMonitor(pm, 1));

            short[][] sdr = new short[bandMap.size()][0];

            for (int i = 0; i < sdrBands.length; i++) {
                final Band sdrOutBand = sdrBands[i];
                ProductData productData = cache.createData(sdrOutBand);
                sdr[i] = (short[]) productData.getElems();
                Band sdrInBand = bandMap.get(sdrOutBand);
                sdrInBand.readRasterData(rect.x, rect.y, rect.width, rect.height, productData, new SubProgressMonitor(pm, 1));
            }

            for (int i = 0; i < size; i++) {
                if (!invalid[i] && (sdr[0][i] == -1 || sdr[0][i] == 0)) {
                    for (int bandId = 0; bandId < sdrBands.length; bandId++) {
                        sdr[bandId][i] = (short) (toar[bandId][i] / scalingFactor);
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

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(GapLessSdrOp.class, "Meris.GapLessSdr");
        }
    }
}