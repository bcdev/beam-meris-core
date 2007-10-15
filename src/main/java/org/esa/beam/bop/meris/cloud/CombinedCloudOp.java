package org.esa.beam.bop.meris.cloud;

import java.awt.Rectangle;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.bc.ceres.core.ProgressMonitor;


/**
 * Created by IntelliJ IDEA.
 * User: marcoz
 * Date: 08.02.2006
 * Time: 10:12:01
 * To change this template use File | Settings | File Templates.
 */
public class CombinedCloudOp extends MerisBasisOp {

    public static final String FLAG_BAND_NAME = "combined_cloud";
    public static final int FLAG_INVALID = 0;
    public static final int FLAG_CLEAR = 1;
    public static final int FLAG_CLOUD = 2;
    public static final int FLAG_SNOW = 4;
    public static final int FLAG_CLOUD_EDGE = 8;
    public static final int FLAG_CLOUD_SHADOW = 16;

    private Band combinedCloudBand;
    
    @SourceProduct(alias="cloudProb")
    private Product cloudProduct;
    @SourceProduct(alias="blueBand")
    private Product blueBandProduct;
    @TargetProduct
    private Product targetProduct;

    @Override
    public Product initialize() throws OperatorException {

        targetProduct = createCompatibleProduct(cloudProduct, "MER_COMBINED_CLOUD", "MER_L2");
        // create and add the flags coding
        FlagCoding flagCoding = createFlagCoding();
        targetProduct.addFlagCoding(flagCoding);

        // create and add the flags dataset
        combinedCloudBand = targetProduct.addBand(FLAG_BAND_NAME, ProductData.TYPE_UINT8);
        combinedCloudBand.setDescription("combined cloud flags");
        combinedCloudBand.setFlagCoding(flagCoding);

        return targetProduct;
    }

    @Override
    public void computeTile(Band band, Tile targetTile) throws OperatorException {

    	Rectangle rectangle = targetTile.getRectangle();
        final int size = rectangle.height * rectangle.width;
        ProgressMonitor pm = createProgressMonitor();
        pm.beginTask("Processing frame...", size + 1);
        try {
        	byte[] cloudProbData = (byte[]) getSourceTile(cloudProduct.getBand(CloudProbabilityOp.CLOUD_FLAG_BAND), rectangle).getRawSampleData().getElems();
        	byte[] blueBandData = (byte[]) getSourceTile(blueBandProduct.getBand(BlueBandOp.BLUE_FLAG_BAND), rectangle).getRawSampleData().getElems();

            ProductData flagData = getSourceTile(combinedCloudBand, rectangle).getRawSampleData();
            byte[] combinedCloudData = (byte[]) flagData.getElems();
            pm.worked(1);
            
            for (int i = 0; i < size; i++) {
                final byte cloudProb = cloudProbData[i];
                byte result;
                if (cloudProb == CloudProbabilityOp.FLAG_INVALID) {
                    result = FLAG_INVALID;
                } else {
                    byte combined = FLAG_CLEAR;
                    final byte blueBand = blueBandData[i];
                    if (cloudProb == CloudProbabilityOp.FLAG_CLOUDY
                            || isSet(blueBand, BlueBandOp.DENSE_CLOUD_BIT)
                            || isSet(blueBand, BlueBandOp.THIN_CLOUD_BIT)) {
                        combined = FLAG_CLOUD;
                    }
                    if (isSet(blueBand, BlueBandOp.SNOW_BIT)) {
                        combined = FLAG_SNOW;
                    }
                    
                    boolean snowPlausible = isSet(blueBand, BlueBandOp.SNOW_PLAUSIBLE_BIT);
                    boolean snowIndex = isSet(blueBand, BlueBandOp.SNOW_INDEX_BIT);
                    boolean brightLand = isSet(blueBand, BlueBandOp.BRIGHT_LAND_BIT);

                    if (snowPlausible && (snowIndex || combined == FLAG_SNOW)) {
                        result = FLAG_SNOW;
                    } else if (brightLand && !snowPlausible &&
                                    ((snowIndex && combined != FLAG_CLOUD)
                                    || combined == FLAG_SNOW)) {
                        result = FLAG_CLOUD;
                    } else if (combined == FLAG_CLOUD && !snowIndex) {
                        result = FLAG_CLOUD;
                    } else {
                        result = FLAG_CLEAR;
                    }
                    if (combined == FLAG_CLEAR) {
                        result = FLAG_CLEAR;
                    }
                }
                combinedCloudData[i] = result;
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }
    
    private boolean isSet(int flags, int bitIndex) {
        return (flags & (1 << bitIndex)) != 0;
    }

    private FlagCoding createFlagCoding() {
        MetadataAttribute cloudAttr;
        final FlagCoding flagCoding = new FlagCoding(FLAG_BAND_NAME);
        flagCoding.setDescription("Combined CLoud Band Flag Coding");

        cloudAttr = new MetadataAttribute("clear", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_CLEAR);
        flagCoding.addAttribute(cloudAttr);


        cloudAttr = new MetadataAttribute("cloud", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_CLOUD);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("snow", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_SNOW);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("cloud_edge", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_CLOUD_EDGE);
        flagCoding.addAttribute(cloudAttr);

        cloudAttr = new MetadataAttribute("cloud_shadow", ProductData.TYPE_UINT8);
        cloudAttr.getData().setElemInt(FLAG_CLOUD_SHADOW);
        flagCoding.addAttribute(cloudAttr);

        return flagCoding;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CombinedCloudOp.class, "Meris.CombinedCloud");
        }
    }
}
