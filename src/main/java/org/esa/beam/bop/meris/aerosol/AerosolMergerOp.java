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

import java.awt.Color;
import java.awt.Rectangle;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.bc.ceres.core.ProgressMonitor;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:21 $
 */
public class AerosolMergerOp extends MerisBasisOp {

    private static final int LARS_FLAG = 1;
    private static final int MOD08_FLAG = 2;
    private static final int DEFAULT_FLAG = 4;

    private static final float AOT_DEFAULT = 0.1f;
    private static final float ANG_DEFAULT = 1f;

    private Band aot470Band;
    private Band angstrBand;
    private Band flagBand;
    private float[] modAot;
    private float[] modAng;
    private byte[] modFlag;
    private float[] l2Aot;
    private float[] l2Ang;
    private float[] aot470;
    private float[] ang;
    private byte[] flag;

    @SourceProduct(alias="l2")
    private Product l2Product;
    @SourceProduct(alias="mod08")
    private Product mod08Product;
    @TargetProduct
    private Product targetProduct;
    
    public AerosolMergerOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        targetProduct = createCompatibleProduct(mod08Product, "AEROSOL", "AEROSOL");
        aot470Band = targetProduct.addBand("aot_470", ProductData.TYPE_FLOAT32);
        angstrBand = targetProduct.addBand("ang", ProductData.TYPE_FLOAT32);

        FlagCoding cloudFlagCoding = createFlagCoding(mod08Product);
        mod08Product.addFlagCoding(cloudFlagCoding);

        flagBand = targetProduct.addBand("aerosol_flags", ProductData.TYPE_UINT8);
        flagBand.setDescription("Aerosol specific flags");
        flagBand.setFlagCoding(cloudFlagCoding);

        return targetProduct;
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

    private void loadSourceTiles(Rectangle rectangle) throws OperatorException {

        modAot = (float[]) getTile(mod08Product.getBand(ModisAerosolOp.BAND_NAME_AOT_470), rectangle).getDataBuffer().getElems();
        modAng = (float[]) getTile(mod08Product.getBand(ModisAerosolOp.BAND_NAME_ANG), rectangle).getDataBuffer().getElems();
        modFlag = (byte[]) getTile(mod08Product.getBand(ModisAerosolOp.BAND_NAME_FLAGS), rectangle).getDataBuffer().getElems();

        l2Aot = (float[]) getTile(l2Product.getBand("aero_opt_thick_443"), rectangle).getDataBuffer().getElems();
        l2Ang = (float[]) getTile(l2Product.getBand("aero_alpha"), rectangle).getDataBuffer().getElems();
    }

    @Override
    public void computeTiles(Rectangle rectangle, ProgressMonitor pm) throws OperatorException {

        final int size = rectangle.height * rectangle.width;
        pm.beginTask("Processing frame...", 1 + size);
        try {
            loadSourceTiles(rectangle);

            aot470 = (float[]) getTile(aot470Band, rectangle).getDataBuffer().getElems();
            ang = (float[]) getTile(angstrBand, rectangle).getDataBuffer().getElems();
            flag = (byte[]) getTile(flagBand, rectangle).getDataBuffer().getElems();

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
