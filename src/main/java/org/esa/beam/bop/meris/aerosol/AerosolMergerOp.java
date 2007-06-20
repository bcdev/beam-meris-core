/*
 * $Id: AerosolMergerOp.java,v 1.1 2007/03/27 12:52:21 marcoz Exp $
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
package org.esa.beam.bop.meris.aerosol;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.support.CachingOperator;
import org.esa.beam.framework.gpf.support.ProductDataCache;
import org.esa.beam.framework.gpf.support.SourceDataRetriever;
import org.esa.beam.framework.datamodel.*;

import java.awt.*;
import java.io.IOException;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:21 $
 */
public class AerosolMergerOp extends CachingOperator {

    private static final int LARS_FLAG = 1;
    private static final int MOD08_FLAG = 2;
    private static final int DEFAULT_FLAG = 4;

    private static final float AOT_DEFAULT = 0.1f;
    private static final float ANG_DEFAULT = 1f;

    private SourceDataRetriever dataRetriever;
    private Band aot470Band;
    private Band angstrBand;
    private Band flagBand;
    private float[] modAot;
    private float[] modAng;
    private int[] modFlag;
    private float[] l2Aot;
    private float[] l2Ang;
    private float[] aot470;
    private float[] ang;
    private byte[] flag;

    public AerosolMergerOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public boolean isComputingAllBandsAtOnce() {
        return true;
    }

    @Override
    public Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        final Product sourceProduct = getSourceProduct("l2");

        final int width = sourceProduct.getSceneRasterWidth();
        final int height = sourceProduct.getSceneRasterHeight();
        final Product aerosolProduct = new Product("AEROSOL", "AEROSOL", width, height);
        aot470Band = new Band("aot_470", ProductData.TYPE_FLOAT32, width, height);
        aerosolProduct.addBand(aot470Band);
        angstrBand = new Band("ang", ProductData.TYPE_FLOAT32, width, height);
        aerosolProduct.addBand(angstrBand);

        FlagCoding cloudFlagCoding = createFlagCoding(aerosolProduct);
        aerosolProduct.addFlagCoding(cloudFlagCoding);

        flagBand = new Band("aerosol_flags", ProductData.TYPE_UINT8, width, height);
        flagBand.setDescription("Aerosol specific flags");
        flagBand.setFlagCoding(cloudFlagCoding);
        aerosolProduct.addBand(flagBand);

        return aerosolProduct;
    }

    private FlagCoding createFlagCoding(Product outputProduct) {
        MetadataAttribute cloudAttr;
        final FlagCoding flagCoding = new FlagCoding("aerosol_flags");
        flagCoding.setDescription("Cloud Flag Coding");

        cloudAttr = new MetadataAttribute("lars", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(LARS_FLAG);
        flagCoding.addAttribute(cloudAttr);
        outputProduct.addBitmaskDef(new BitmaskDef(cloudAttr.getName(),
                                                   cloudAttr.getDescription(),
                                                   flagCoding.getName() + "." + cloudAttr.getName(),
                                                   Color.BLUE,
                                                   0.5F));

        cloudAttr = new MetadataAttribute("mod08", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(MOD08_FLAG);
        flagCoding.addAttribute(cloudAttr);
        outputProduct.addBitmaskDef(new BitmaskDef(cloudAttr.getName(),
                                                   cloudAttr.getDescription(),
                                                   flagCoding.getName() + "." + cloudAttr.getName(),
                                                   Color.RED,
                                                   0.5F));

        cloudAttr = new MetadataAttribute("default", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(DEFAULT_FLAG);
        flagCoding.addAttribute(cloudAttr);
        outputProduct.addBitmaskDef(new BitmaskDef(cloudAttr.getName(),
                                                   cloudAttr.getDescription(),
                                                   flagCoding.getName() + "." + cloudAttr.getName(),
                                                   Color.GREEN,
                                                   0.5F));

        return flagCoding;
    }

    @Override
    public void initSourceRetriever() {
        final Product mod08Product = getSourceProduct("mod08");
        final Product l2Product = getSourceProduct("l2");

        dataRetriever = new SourceDataRetriever(maxTileSize);

        modAot = dataRetriever.connectFloat(mod08Product.getBand(ModisAerosolOp.BAND_NAME_AOT_470));
        modAng = dataRetriever.connectFloat(mod08Product.getBand(ModisAerosolOp.BAND_NAME_ANG));
        modFlag = dataRetriever.connectInt(mod08Product.getBand(ModisAerosolOp.BAND_NAME_FLAGS));

        l2Aot = dataRetriever.connectFloat(l2Product.getBand("aero_opt_thick_443"));
        l2Ang = dataRetriever.connectFloat(l2Product.getBand("aero_alpha"));
    }

    @Override
    public void computeTiles(Rectangle rectangle,
                             ProductDataCache cache, ProgressMonitor pm)
            throws OperatorException {

        final int size = rectangle.height * rectangle.width;
        pm.beginTask("Processing frame...", 1 + size);
        try {
            dataRetriever.readData(rectangle, new SubProgressMonitor(pm, 1));

            aot470 = (float[]) cache.createData(aot470Band).getElems();
            ang = (float[]) cache.createData(angstrBand).getElems();
            flag = (byte[]) cache.createData(flagBand).getElems();

            for (int i = 0; i < size; i++) {
                if (l2Aot[i] >= 0 && l2Ang[i] >= 0) {
                    aot470[i] = l2Aot[i];
                    ang[i] = l2Ang[i];
                    flag[i] = LARS_FLAG;
                } else if (modFlag[i] == 0) {
                    aot470[i] = modAot[i];
                    ang[i] = modAng[i];
                    flag[i] = MOD08_FLAG;
                } else {
                    aot470[i] = AOT_DEFAULT;
                    ang[i] = ANG_DEFAULT;
                    flag[i] = DEFAULT_FLAG;
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
            super(AerosolMergerOp.class, "Meris.AerosolMerger");
        }
    }
}
