package org.esa.beam.bop.meris.brr;

import java.awt.Color;
import java.awt.Rectangle;

import org.esa.beam.bop.meris.AlbedoUtils;
import org.esa.beam.bop.meris.brr.dpm.AtmosphericCorrectionLand;
import org.esa.beam.bop.meris.brr.dpm.CloudClassification;
import org.esa.beam.bop.meris.brr.dpm.Constants;
import org.esa.beam.bop.meris.brr.dpm.DpmConfig;
import org.esa.beam.bop.meris.brr.dpm.DpmPixel;
import org.esa.beam.bop.meris.brr.dpm.GaseousAbsorptionCorrection;
import org.esa.beam.bop.meris.brr.dpm.L1bDataExtraction;
import org.esa.beam.bop.meris.brr.dpm.L2AuxData;
import org.esa.beam.bop.meris.brr.dpm.PixelIdentification;
import org.esa.beam.bop.meris.brr.dpm.RayleighCorrection;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;

import com.bc.ceres.core.ProgressMonitor;

/**
 * A processing node to compute the BRR of a meris Lib product.
 */
public class BrrOp extends MerisBasisOp {

    protected static final String MERIS_L2_CONF = "meris_l2_config.xml";

    protected L1bDataExtraction extdatl1;
    protected PixelIdentification pixelid;
    protected CloudClassification classcloud;
    protected GaseousAbsorptionCorrection gaz_cor;
    protected RayleighCorrection ray_cor;
    protected AtmosphericCorrectionLand landac;

    protected DpmConfig dpmConfig;

    protected DpmPixel[] frame;
    protected DpmPixel[][] block;
    protected int scanLineSize;

    // data
    protected float[][] l1bTiePointScans;
    protected float[][] l1bRadianceScans;
    protected short[] l1bDetectorIndexScan;
    protected byte[] l1bFlagsScan;

    // source product
    private RasterDataNode[] tpGrids;
    private RasterDataNode[] l1bRadiance;
    private RasterDataNode detectorIndex;
    private RasterDataNode l1bFlags;

    // target product
    protected Band l2FlagsP1;
    protected Band l2FlagsP2;
    protected Band l2FlagsP3;

    protected int[] l2FlagsP1Frame;
    protected int[] l2FlagsP2Frame;
    protected int[] l2FlagsP3Frame;

    protected Band[] brrReflecBands = new Band[Constants.L1_BAND_NUM];
    protected Band[] toaReflecBands = new Band[Constants.L1_BAND_NUM];

    @SourceProduct(alias="input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    public String configFile = MERIS_L2_CONF;
    @Parameter
    public boolean outputToar = false;
    @Parameter
    public boolean correctWater = false;

    public BrrOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }

        // todo - tell someone else that we need a 4x4 subwindow
        // operatorContext.addMinDimensions(new Dimension(4, 4));

        checkInputProduct(sourceProduct);
        prepareSourceProducts();


        targetProduct = createCompatibleProduct(sourceProduct, "BRR", "BRR");

        createOutputBands(brrReflecBands, "brr");
        if (outputToar) {
            createOutputBands(toaReflecBands, "toar");
        }

        l2FlagsP1 = addFlagsBand(createFlagCodingP1(), 0.0, 1.0, 0.5);
        l2FlagsP2 = addFlagsBand(createFlagCodingP2(), 0.2, 0.7, 0.0);
        l2FlagsP3 = addFlagsBand(createFlagCodingP3(), 0.8, 0.1, 0.3);

        initAlgorithms(sourceProduct);    // todo do only once; change some auxdata depending on date and product
        pixelid.setCorrectWater(correctWater);
        landac.setCorrectWater(correctWater);
        return targetProduct;
    }

    protected void initAlgorithms(Product inputProduct) throws IllegalArgumentException {
        try {
            final L2AuxData auxData = new L2AuxData(dpmConfig, inputProduct);
            extdatl1 = new L1bDataExtraction(auxData);
            gaz_cor = new GaseousAbsorptionCorrection(auxData);
            pixelid = new PixelIdentification(auxData, gaz_cor);
            ray_cor = new RayleighCorrection(auxData);
            classcloud = new CloudClassification(auxData, ray_cor);
            landac = new AtmosphericCorrectionLand(ray_cor);
        } catch (Exception e) { // todo handle IOException and DpmException
            e.printStackTrace();
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    protected void createScanLines(Rectangle rectangle) {
        final int frameSize = rectangle.height * rectangle.width;
        if (frameSize != scanLineSize) {
            scanLineSize = frameSize;
            final int frameWidth = rectangle.width;
            final int frameHeight = rectangle.height;

            frame = new DpmPixel[frameSize];
            block = new DpmPixel[frameHeight][frameWidth];
            for (int iP = 0; iP < frame.length; iP++) {
                final DpmPixel pixel = new DpmPixel();
                pixel.i = iP % frameWidth;
                pixel.j = iP / frameWidth;
                frame[iP] = block[pixel.j][pixel.i] = pixel;
            }

            l2FlagsP1Frame = new int[frameSize];
            l2FlagsP2Frame = new int[frameSize];
            l2FlagsP3Frame = new int[frameSize];
        }
    }

    protected void prepareSourceProducts() {
        final int numTPGrids = EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES.length;
        tpGrids = new TiePointGrid[numTPGrids];
        for (int i = 0; i < numTPGrids; i++) {
            tpGrids[i] = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[i]);
        }

        if (sourceProduct.getProductType().equals(EnvisatConstants.MERIS_FSG_L1B_PRODUCT_TYPE_NAME)) {
            tpGrids[Constants.LATITUDE_TPG_INDEX] = sourceProduct.getBand("corr_latitude");
            tpGrids[Constants.LONGITUDE_TPG_INDEX] = sourceProduct.getBand("corr_longitude");
            tpGrids[Constants.DEM_ALT_TPG_INDEX] = sourceProduct.getBand("altitude");
        }
        
        l1bRadiance = new RasterDataNode[EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES.length];
        for (int i = 0; i < l1bRadiance.length; i++) {
        	l1bRadiance[i] = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[i]);
        }
        detectorIndex = sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME);
        l1bFlags = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME);
    }
    
    protected void getSourceTiles(Rectangle rect) throws OperatorException {
    	l1bTiePointScans = new float[tpGrids.length][0];
        for (int i = 0; i < tpGrids.length; i++) {
            l1bTiePointScans[i] = (float[]) getRaster(tpGrids[i], rect).getDataBuffer().getElems();
        }
        l1bRadianceScans = new float[l1bRadiance.length][0];
        for (int i = 0; i < l1bRadiance.length; i++) {
            l1bRadianceScans[i] = (float[]) getRaster(l1bRadiance[i], rect).getDataBuffer().getElems();
        }
        l1bDetectorIndexScan = (short[]) getRaster(detectorIndex, rect).getDataBuffer().getElems();
        l1bFlagsScan = (byte[]) getRaster(l1bFlags, rect).getDataBuffer().getElems();

    }

    @Override
    public void computeAllBands(Rectangle targetTileRectangle,
            ProgressMonitor pm) throws OperatorException {

        createScanLines(targetTileRectangle);
        final int rectX = targetTileRectangle.x;
        final int rectY = targetTileRectangle.y;
        final int rectW = targetTileRectangle.width;
        final int rectH = targetTileRectangle.height;

        getSourceTiles(targetTileRectangle);
        
        for (int iP = 0; iP < rectW * rectH; iP++) {
            DpmPixel pixel = frame[iP];
            extdatl1.l1_extract_pixbloc(pixel,
                                        rectX + pixel.i,
                                        rectY + pixel.j,
                                        iP,
                                        l1bTiePointScans,
                                        l1bRadianceScans,
                                        l1bDetectorIndexScan,
                                        l1bFlagsScan);

            if (!AlbedoUtils.isFlagSet(pixel.l2flags, Constants.F_INVALID)) {
                pixelid.rad2reflect(pixel);
                classcloud.classify_cloud(pixel);
            }
        }

        for (int iPL1 = 0; iPL1 < rectH; iPL1 += Constants.SUBWIN_HEIGHT) {
            for (int iPC1 = 0; iPC1 < rectW; iPC1 += Constants.SUBWIN_WIDTH) {
                final int iPC2 = Math.min(rectW, iPC1 + Constants.SUBWIN_WIDTH) - 1;
                final int iPL2 = Math.min(rectH, iPL1 + Constants.SUBWIN_HEIGHT) - 1;
                pixelid.pixel_classification(block, iPC1, iPC2, iPL1, iPL2);
                landac.landAtmCor(block, iPC1, iPC2, iPL1, iPL2);
            }
        }

        for (int iP = 0; iP < frame.length; iP++) {
            DpmPixel pixel = frame[iP];
            l2FlagsP1Frame[iP] = (int) ((pixel.l2flags & 0x00000000ffffffffL));
            l2FlagsP2Frame[iP] = (int) ((pixel.l2flags & 0xffffffff00000000L) >> 32);
            l2FlagsP3Frame[iP] = pixel.ANNOT_F;
        }


        for (int bandIndex = 0; bandIndex < brrReflecBands.length; bandIndex++) {
            if (AlbedoUtils.isValidRhoSpectralIndex(bandIndex)) {
                ProductData data = getRaster(brrReflecBands[bandIndex], targetTileRectangle).getDataBuffer();
                float[] ddata = (float[]) data.getElems();
                for (int iP = 0; iP < rectW * rectH; iP++) {
                    ddata[iP] = (float) frame[iP].rho_top[bandIndex];
                }
            }
        }
        if (outputToar) {
            for (int bandIndex = 0; bandIndex < toaReflecBands.length; bandIndex++) {
                ProductData data = getRaster(toaReflecBands[bandIndex], targetTileRectangle).getDataBuffer();
                float[] ddata = (float[]) data.getElems();
                for (int iP = 0; iP < rectW * rectH; iP++) {
                    ddata[iP] = (float) frame[iP].rho_toa[bandIndex];
                }
            }
        }
        ProductData flagData = getRaster(l2FlagsP1, targetTileRectangle).getDataBuffer();
        int[] intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP1Frame, 0, intFlag, 0, rectW * rectH);

        flagData = getRaster(l2FlagsP2, targetTileRectangle).getDataBuffer();
        intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP2Frame, 0, intFlag, 0, rectW * rectH);

        flagData = getRaster(l2FlagsP3, targetTileRectangle).getDataBuffer();
        intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP3Frame, 0, intFlag, 0, rectW * rectH);
    }

    protected Band addFlagsBand(final FlagCoding flagCodingP1, final double rf1, final double gf1, final double bf1) {
        addFlagCodingAndCreateBMD(flagCodingP1, rf1, gf1, bf1);
        final Band l2FlagsP1Band = new Band(flagCodingP1.getName(), ProductData.TYPE_INT32,
                                        targetProduct.getSceneRasterWidth(), targetProduct.getSceneRasterHeight());
        l2FlagsP1Band.setFlagCoding(flagCodingP1);
        targetProduct.addBand(l2FlagsP1Band);
        return l2FlagsP1Band;
    }

    protected void addFlagCodingAndCreateBMD(FlagCoding flagCodingP1, double rf1, double gf1, double bf1) {
        targetProduct.addFlagCoding(flagCodingP1);
        for (int i = 0; i < flagCodingP1.getNumAttributes(); i++) {
            final MetadataAttribute attribute = flagCodingP1.getAttributeAt(i);
            final double a = 2 * Math.PI * (i / 31.0);
            final Color color = new Color((float) (0.5 + 0.5 * Math.sin(a + rf1 * Math.PI)),
                                          (float) (0.5 + 0.5 * Math.sin(a + gf1 * Math.PI)),
                                          (float) (0.5 + 0.5 * Math.sin(a + bf1 * Math.PI)));
            targetProduct.addBitmaskDef(new BitmaskDef(attribute.getName(),
                                                       null,
                                                       flagCodingP1.getName() + "." + attribute.getName(),
                                                       color,
                                                       0.4F));
        }
    }

    protected void createOutputBands(Band[] bands, final String name) {
        final Product soucreProduct = getSourceProduct("input");
        final int sceneWidth = targetProduct.getSceneRasterWidth();
        final int sceneHeight = targetProduct.getSceneRasterHeight();

        for (int bandId = 0; bandId < bands.length; bandId++) {
            if (AlbedoUtils.isValidRhoSpectralIndex(bandId) || name.equals("toar")) {
                Band aNewBand = new Band(name + "_" + (bandId + 1), ProductData.TYPE_FLOAT32, sceneWidth,
                                         sceneHeight);
                aNewBand.setNoDataValueUsed(true);
                aNewBand.setNoDataValue(-1);
                aNewBand.setSpectralBandIndex(soucreProduct.getBandAt(bandId).getSpectralBandIndex());
                aNewBand.setSpectralWavelength(soucreProduct.getBandAt(bandId).getSpectralWavelength());
                targetProduct.addBand(aNewBand);
                bands[bandId] = aNewBand;
            }
        }
    }

    protected void checkInputProduct(Product inputProduct) throws IllegalArgumentException {
        String name;

        for (int i = 0; i < EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES.length; i++) {
            name = EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[i];
            if (inputProduct.getTiePointGrid(name) == null) {
                throw new IllegalArgumentException("Invalid input product. Missing tie point grid '" + name + "'.");
            }
        }

        for (int i = 0; i < EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES.length; i++) {
            name = EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[i];
            if (inputProduct.getBand(name) == null) {
                throw new IllegalArgumentException("Invalid input product. Missing band '" + name + "'.");
            }
        }

        name = EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME;
        if (inputProduct.getBand(name) == null) {
            throw new IllegalArgumentException("Invalid input product. Missing dataset '" + name + "'.");
        }

        name = EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME;
        if (inputProduct.getBand(name) == null) {
            throw new IllegalArgumentException("Invalid input product. Missing dataset '" + name + "'.");
        }
    }

    protected static FlagCoding createFlagCodingP1() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p1");
        flagCoding.addFlag("F_BRIGHT", AlbedoUtils.setFlag(0, Constants.F_BRIGHT), null);
        flagCoding.addFlag("F_CASE2_S", AlbedoUtils.setFlag(0, Constants.F_CASE2_S), null);
        flagCoding.addFlag("F_CASE2ANOM", AlbedoUtils.setFlag(0, Constants.F_CASE2ANOM), null);
        flagCoding.addFlag("F_CASE2Y", AlbedoUtils.setFlag(0, Constants.F_CASE2Y), null);
        flagCoding.addFlag("F_CHL1RANGE_IN", AlbedoUtils.setFlag(0, Constants.F_CHL1RANGE_IN), null);
        flagCoding.addFlag("F_CHL1RANGE_OUT", AlbedoUtils.setFlag(0, Constants.F_CHL1RANGE_OUT), null);
        flagCoding.addFlag("F_CIRRUS", AlbedoUtils.setFlag(0, Constants.F_CIRRUS), null);
        flagCoding.addFlag("F_CLOUD", AlbedoUtils.setFlag(0, Constants.F_CLOUD), null);
        flagCoding.addFlag("F_CLOUDPART", AlbedoUtils.setFlag(0, Constants.F_CLOUDPART), null);
        flagCoding.addFlag("F_COASTLINE", AlbedoUtils.setFlag(0, Constants.F_COASTLINE), null);
        flagCoding.addFlag("F_COSMETIC", AlbedoUtils.setFlag(0, Constants.F_COSMETIC), null);
        flagCoding.addFlag("F_DDV", AlbedoUtils.setFlag(0, Constants.F_DDV), null);
        flagCoding.addFlag("F_DUPLICATED", AlbedoUtils.setFlag(0, Constants.F_DUPLICATED), null);
        flagCoding.addFlag("F_HIINLD", AlbedoUtils.setFlag(0, Constants.F_HIINLD), null);
        flagCoding.addFlag("F_ICE_HIGHAERO", AlbedoUtils.setFlag(0, Constants.F_ICE_HIGHAERO), null);
        flagCoding.addFlag("F_INVALID", AlbedoUtils.setFlag(0, Constants.F_INVALID), null);
        flagCoding.addFlag("F_ISLAND", AlbedoUtils.setFlag(0, Constants.F_ISLAND), null);
        flagCoding.addFlag("F_LAND", AlbedoUtils.setFlag(0, Constants.F_LAND), null);
        flagCoding.addFlag("F_LANDCONS", AlbedoUtils.setFlag(0, Constants.F_LANDCONS), null);
        flagCoding.addFlag("F_LOINLD", AlbedoUtils.setFlag(0, Constants.F_LOINLD), null);
        flagCoding.addFlag("F_MEGLINT", AlbedoUtils.setFlag(0, Constants.F_MEGLINT), null);
        flagCoding.addFlag("F_ORINP1", AlbedoUtils.setFlag(0, Constants.F_ORINP1), null);
        flagCoding.addFlag("F_ORINP2", AlbedoUtils.setFlag(0, Constants.F_ORINP2), null);
        flagCoding.addFlag("F_ORINPWV", AlbedoUtils.setFlag(0, Constants.F_ORINPWV), null);
        flagCoding.addFlag("F_OROUT1", AlbedoUtils.setFlag(0, Constants.F_OROUT1), null);
        flagCoding.addFlag("F_OROUT2", AlbedoUtils.setFlag(0, Constants.F_OROUT2), null);
        flagCoding.addFlag("F_OROUTWV", AlbedoUtils.setFlag(0, Constants.F_OROUTWV), null);
        flagCoding.addFlag("F_SUSPECT", AlbedoUtils.setFlag(0, Constants.F_SUSPECT), null);
        flagCoding.addFlag("F_UNCGLINT", AlbedoUtils.setFlag(0, Constants.F_UNCGLINT), null);
        flagCoding.addFlag("F_WHITECAPS", AlbedoUtils.setFlag(0, Constants.F_WHITECAPS), null);
        flagCoding.addFlag("F_WVAP", AlbedoUtils.setFlag(0, Constants.F_WVAP), null);
        flagCoding.addFlag("F_ACFAIL", AlbedoUtils.setFlag(0, Constants.F_ACFAIL), null);
        return flagCoding;
    }

    protected static FlagCoding createFlagCodingP2() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p2");
        flagCoding.addFlag("F_CONSOLID", AlbedoUtils.setFlag(0, Constants.F_CONSOLID), null);
        flagCoding.addFlag("F_ORINP0", AlbedoUtils.setFlag(0, Constants.F_ORINP0), null);
        flagCoding.addFlag("F_OROUT0", AlbedoUtils.setFlag(0, Constants.F_OROUT0), null);
        flagCoding.addFlag("F_LOW_NN_P", AlbedoUtils.setFlag(0, Constants.F_LOW_NN_P), null);
        flagCoding.addFlag("F_PCD_NN_P", AlbedoUtils.setFlag(0, Constants.F_PCD_NN_P), null);
        flagCoding.addFlag("F_LOW_POL_P", AlbedoUtils.setFlag(0, Constants.F_LOW_POL_P), null);
        flagCoding.addFlag("F_PCD_POL_P", AlbedoUtils.setFlag(0, Constants.F_PCD_POL_P), null);
        flagCoding.addFlag("F_CONFIDENCE_P", AlbedoUtils.setFlag(0, Constants.F_CONFIDENCE_P), null);
        flagCoding.addFlag("F_SLOPE_1", AlbedoUtils.setFlag(0, Constants.F_SLOPE_1), null);
        flagCoding.addFlag("F_SLOPE_2", AlbedoUtils.setFlag(0, Constants.F_SLOPE_2), null);
        flagCoding.addFlag("F_UNCERTAIN", AlbedoUtils.setFlag(0, Constants.F_UNCERTAIN), null);
        flagCoding.addFlag("F_SUN70", AlbedoUtils.setFlag(0, Constants.F_SUN70), null);
        flagCoding.addFlag("F_WVHIGLINT", AlbedoUtils.setFlag(0, Constants.F_WVHIGLINT), null);
        flagCoding.addFlag("F_TOAVIVEG", AlbedoUtils.setFlag(0, Constants.F_TOAVIVEG), null);
        flagCoding.addFlag("F_TOAVIBAD", AlbedoUtils.setFlag(0, Constants.F_TOAVIBAD), null);
        flagCoding.addFlag("F_TOAVICSI", AlbedoUtils.setFlag(0, Constants.F_TOAVICSI), null);
        flagCoding.addFlag("F_TOAVIWS", AlbedoUtils.setFlag(0, Constants.F_TOAVIWS), null);
        flagCoding.addFlag("F_TOAVIBRIGHT", AlbedoUtils.setFlag(0, Constants.F_TOAVIBRIGHT), null);
        flagCoding.addFlag("F_TOAVIINVALREC", AlbedoUtils.setFlag(0, Constants.F_TOAVIINVALREC), null);
        return flagCoding;
    }

    protected static FlagCoding createFlagCodingP3() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p3");
        for (int i = 0; i < Constants.L1_BAND_NUM; i++) {
            flagCoding.addFlag("F_INVALID_REFLEC_" + (i + 1), AlbedoUtils.setFlag(0, i), null);
        }
        return flagCoding;
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(BrrOp.class, "Meris.Brr");
        }
    }
}
