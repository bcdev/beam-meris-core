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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.support.CachingOperator;
import org.esa.beam.framework.gpf.support.ProductDataCache;
import org.esa.beam.framework.gpf.support.SourceDataRetriever;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;

import java.awt.*;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class ProcessFurtherStateOp extends CachingOperator {

    private static final String PROCESS_FURTHER_BAND_NAME = "process_further_state";

    private static final String[] EXPRESSIONS = {
            "not (combined_cloud.cloud or combined_cloud.cloud_edge or combined_cloud.cloud_shadow) and l2_flags_p1.F_LANDCONS",
            // TODO: remove L1 land flag from process further
            "l1_flags.LAND_OCEAN and not l2_flags_p1.F_LANDCONS",
            "combined_cloud.cloud_edge or combined_cloud.cloud_shadow",
            "combined_cloud.cloud",
            "not l1_flags.LAND_OCEAN and not l2_flags_p1.F_LANDCONS",
            "combined_cloud.snow",
            "l1_flags.INVALID"};

    private SourceDataRetriever dataRetriever;

    private boolean[][] isValid;

    public ProcessFurtherStateOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public boolean isComputingAllBandsAtOnce() {
        return false;
    }

    @Override
    public Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        Product inputProduct = getContext().getSourceProduct("input");

        final int sceneWidth = inputProduct.getSceneRasterWidth();
        final int sceneHeight = inputProduct.getSceneRasterHeight();

        Product targetProduct = new Product("MER", "MER_L2", sceneWidth, sceneHeight);
        Band processFurtherBand = new Band(PROCESS_FURTHER_BAND_NAME,
                                           ProductData.TYPE_INT8, sceneWidth, sceneHeight);
        processFurtherBand.setDescription("process further state");
        targetProduct.addBand(processFurtherBand);

        return targetProduct;
    }

    @Override
    public void initSourceRetriever() {
        final Product inputProduct = getContext().getSourceProduct("input");
        dataRetriever = new SourceDataRetriever(maxTileSize);

        isValid = new boolean[EXPRESSIONS.length][0];
        for (int j = 0; j < EXPRESSIONS.length; j++) {
            isValid[j] = dataRetriever.connectBooleanExpression(inputProduct, EXPRESSIONS[j]);
        }
    }

    @Override
    public void computeTile(Band band, Rectangle rectangle,
                            ProductDataCache cache, ProgressMonitor pm) throws OperatorException {

        final int size = rectangle.height * rectangle.width;
        pm.beginTask("Processing frame...", size + 1);
        try {
            dataRetriever.readData(rectangle, new SubProgressMonitor(pm, 1));

            ProductData flagData = cache.createData(band);
            byte[] processfurther = (byte[]) flagData.getElems();

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < EXPRESSIONS.length; j++) {
                    if (isValid[j][i]) {
                        processfurther[i] = (byte) j;
                    }
                }
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(ProcessFurtherStateOp.class, "Meris.ProcessFurtherState");
        }
    }
}
