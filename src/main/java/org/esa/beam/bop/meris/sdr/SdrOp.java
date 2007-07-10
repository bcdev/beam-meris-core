package org.esa.beam.bop.meris.sdr;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.bop.meris.AlbedoUtils;
import org.esa.beam.bop.meris.AlbedomapConstants;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.Auxdata;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.StringUtils;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jnn.Jnn;
import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;

/**
 * Created by IntelliJ IDEA.
 * User: marcoz
 * Date: 12.05.2005
 * Time: 12:17:11
 * To change this template use File | Settings | File Templates.
 */
public class SdrOp extends MerisBasisOp {

    private static final String DEFAULT_OUTPUT_PRODUCT_NAME = "MER_SDR";
    private static final String SDR_PRODUCT_TYPE = "MER_L2_SDR";

    private static final String SDR_BAND_NAME_PREFIX = "sdr_";
    private static final String SDR_FLAGS_BAND_NAME = "sdr_flags";

    private static final String SDR_INVALID_FLAG_NAME = "INVALID_SDR";
    private static final int SDR_INVALID_FLAG_VALUE = 1 << 0;
    private static final float SCALING_FACTOR = 0.0001f;

    private static final int[] sdrBandNo = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14};

    private SdrAlgorithm algorithm;
    private Band[] reflectanceBands;
    private Band[] sdrBands;
    private Band sdrFlagBand;

    private Raster[] reflectance;
    private Raster[] sdr;
    private Raster sza;
    private Raster saa;
    private Raster vza;
    private Raster vaa;
    private Raster sdrFlag;

    private Band validBand;
    private Raster isValidPixel;
    
    private Raster aot470;
    private Raster ang;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="brr")
    private Product brrProduct;
    @SourceProduct(alias="aerosol")
    private Product aerosolProduct;
    @SourceProduct(alias="cloud")
    private Product cloudProduct;
    @SourceProducts
    private Product[] sourceProducts;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String neuralNetFile;
    @Parameter
    private String validExpression;
    @Parameter
    private String aot470Name;
    @Parameter
    private String angName;
	

    public SdrOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        if (StringUtils.isNullOrEmpty(neuralNetFile)) {
            throw new OperatorException("No neural net specified.");
        }
        if (StringUtils.isNullOrEmpty(validExpression)) {
            throw new OperatorException("No validExpression specified.");
        }
        if (StringUtils.isNullOrEmpty(aot470Name)) {
            throw new OperatorException("No aot470 band specified.");
        }
        if (StringUtils.isNullOrEmpty(angName)) {
            throw new OperatorException("No ang bandt specified.");
        }
        try {
            loadNeuralNet();
        } catch (Exception e) {
            throw new OperatorException("Failed to load neural net " + neuralNetFile + ":\n" + e.getMessage());
        }
        return createTargetProduct(pm);
    }

    private Product createTargetProduct(ProgressMonitor pm) throws OperatorException {
        targetProduct = createCompatibleProduct(l1bProduct, DEFAULT_OUTPUT_PRODUCT_NAME, SDR_PRODUCT_TYPE);
        
        reflectanceBands = new Band[sdrBandNo.length];
        sdrBands = new Band[sdrBandNo.length];
        for (int i = 0; i < sdrBandNo.length; i++) {
        	reflectanceBands[i] = brrProduct.getBand("brr_" + Integer.toString(sdrBandNo[i]));
        	
            final Band band = l1bProduct.getBand("radiance_" + Integer.toString(sdrBandNo[i]));

            final Band sdrOutputBand = targetProduct.addBand(SDR_BAND_NAME_PREFIX + Integer.toString(sdrBandNo[i]),
                                                ProductData.TYPE_INT16);
            sdrOutputBand.setDescription(
                    "Surface directional reflectance at " + band.getSpectralWavelength() + " nm");
            sdrOutputBand.setUnit("1");
            sdrOutputBand.setScalingFactor(SCALING_FACTOR);
            ProductUtils.copySpectralAttributes(band, sdrOutputBand);
            sdrOutputBand.setNoDataValueUsed(true);
            sdrOutputBand.setGeophysicalNoDataValue(-1);
            sdrBands[i] = sdrOutputBand;
        }
        // create and add the NDVI flags coding
        FlagCoding sdiFlagCoding = createSdiFlagCoding(targetProduct);
        targetProduct.addFlagCoding(sdiFlagCoding);

        // create and add the SDR flags dataset
        sdrFlagBand = targetProduct.addBand(SDR_FLAGS_BAND_NAME,
                               ProductData.TYPE_UINT16);
        sdrFlagBand.setDescription("SDR specific flags");
        sdrFlagBand.setFlagCoding(sdiFlagCoding);

		validBand = createBooleanBandForExpression(validExpression, pm);

		return targetProduct;
    }

	private Band createBooleanBandForExpression(String expression, ProgressMonitor pm) throws OperatorException {
		Map<String, Object> parameters = new HashMap<String, Object>();
        BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[1];
        BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
		bandDescriptor.name = "bBand";
		bandDescriptor.expression = expression;
		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
		bandDescriptors[0] = bandDescriptor;
		parameters.put("bandDescriptors", bandDescriptors);
		
		Map<String, Product> products = new HashMap<String, Product>();
		for (Product product : sourceProducts) {
			products.put(getContext().getIdForSourceProduct(product), product);
		}
		Product validLandProduct = GPF.createProduct("BandArithmetic", parameters, products, pm);
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", validLandProduct);
		return validLandProduct.getBand("bBand");
	}

    private void loadSourceTiles(Rectangle rectangle) throws OperatorException {

        sza = getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle);
        saa = getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle);
        vza = getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle);
        vaa = getRaster(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle);

        ang = getRaster(aerosolProduct.getBand(angName), rectangle);
        aot470 = getRaster(aerosolProduct.getBand(aot470Name), rectangle);


        reflectance = new Raster[sdrBandNo.length];
        sdr = new Raster[sdrBandNo.length];
        
        for (int i = 0; i < sdrBandNo.length; i++) {
            reflectance[i] = getRaster(reflectanceBands[i], rectangle);
        }
        isValidPixel = getRaster(validBand, rectangle);
    }

    @Override
    public void computeAllBands(Rectangle rectangle, ProgressMonitor pm) throws OperatorException {

        final double[] sdrAlgoInput = new double[9];
        final double[] sdrAlgoOutput = new double[1];

        pm.beginTask("Processing frame...", rectangle.height);
        try {
            loadSourceTiles(rectangle);

            for (int i = 0; i < sdrBands.length; i++) {
            	sdr[i] = getRaster(sdrBands[i], rectangle);
//	            sdr[i] = (float[]) data.getElems();
            }
            sdrFlag = getRaster(sdrFlagBand, rectangle);
			for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				for (int x = rectangle.x; x < rectangle.x+rectangle.width; x++) {
	                if (isValidPixel.getBoolean(x, y)) {
	                    double t_sza = sza.getDouble(x, y) * MathUtils.DTOR;
	                    double t_vza = vza.getDouble(x, y) * MathUtils.DTOR;
	                    double ada = AlbedoUtils.computeAzimuthDifference(vaa.getDouble(x, y), saa.getDouble(x, y)) * MathUtils.DTOR;
	                    double rhoNorm;
	                    double wavelength;
	                    double mueSun = Math.cos(t_sza);
	                    double geomX = Math.sin(t_vza) * Math.cos(ada);
	                    double geomY = Math.sin(t_vza) * Math.sin(ada);
	                    double geomZ = Math.cos(t_vza);

	                    double t_sdr;
	                    short sdrFlags = 0;
	                    for (int bandId = 0; bandId < reflectanceBands.length; bandId++) {
	                        final Band reflInputBand = reflectanceBands[bandId];
	                        rhoNorm = reflectance[bandId].getDouble(x, y) / Math.PI;
	                        wavelength = reflInputBand.getSpectralWavelength();
	                        sdrAlgoInput[0] = rhoNorm;
	                        sdrAlgoInput[1] = wavelength;
	                        sdrAlgoInput[2] = mueSun;
	                        sdrAlgoInput[3] = geomX;
	                        sdrAlgoInput[4] = geomY;
	                        sdrAlgoInput[5] = geomZ;
	                        sdrAlgoInput[6] = aot470.getDouble(x, y);
	                        sdrAlgoInput[7] = 0; // aot 660; usage discontinued
	                        sdrAlgoInput[8] = ang.getDouble(x, y);
	                        algorithm.computeSdr(sdrAlgoInput, sdrAlgoOutput);
	                        t_sdr = sdrAlgoOutput[0];
	                        if (Double.isInfinite(t_sdr) || Double.isNaN(t_sdr)) {
	                            t_sdr = 0.0;
	                            sdrFlags |= 1 << (reflInputBand.getSpectralBandIndex() + 1);
	                        } else if (t_sdr < 0.0) {
	                            t_sdr = 0.0;
	                            sdrFlags |= 1 << (reflInputBand.getSpectralBandIndex() + 1);
	                        } else if (t_sdr > 1.0) {
	                            t_sdr = 1.0;
	                            sdrFlags |= 1 << (reflInputBand.getSpectralBandIndex() + 1);
	                        }
//	                        sdr[bandId][i] = (short) (t_sdr / SCALING_FACTOR);
	                        sdr[bandId].setFloat(x, y, (float) t_sdr);
	                    }
	                    sdrFlags |= (sdrFlags == 0 ? 0 : 1); // Combine SDR-Flags
	                    // to single INVALID
	                    // Flag
	                    sdrFlag.setInt(x, y, sdrFlags);
	                } else {
	                    for (int j = 0; j < reflectanceBands.length; j++) {
	                        sdr[j].setDouble(x, y, -1);
	                    }
	                    sdrFlag.setInt(x, y, SDR_INVALID_FLAG_VALUE);
	                }
				}
			}
            // process the complete rect
                pm.worked(1);
        } finally {
            pm.done();
        }
    }

    private void loadNeuralNet() throws IOException, JnnException {
        Auxdata auxdata = new Auxdata(AlbedomapConstants.SYMBOLIC_NAME, "sdr");
        auxdata.installAuxdata(this);
        File auxDataDir = auxdata.getDefaultAuxdataDir();

        File nnFile = new File(auxDataDir, neuralNetFile);
        final InputStreamReader reader = new FileReader(nnFile);
        final JnnNet neuralNet;
        try {
            Jnn.setOptimizing(true);
            neuralNet = Jnn.readNna(reader);
        } finally {
            reader.close();
        }
        algorithm = new SdrAlgorithm(neuralNet);
    }


    private FlagCoding createSdiFlagCoding(Product outputProduct) {
        final double rf1 = 0.3;
        final double gf1 = 1.0;
        final double bf1 = 0.5;

        final FlagCoding flagCoding = new FlagCoding(SDR_FLAGS_BAND_NAME);
        flagCoding.setDescription("SDR Flag Coding");

        final MetadataAttribute invAttr = new MetadataAttribute(SDR_INVALID_FLAG_NAME, ProductData.TYPE_INT32);
        invAttr.getData().setElemInt(SDR_INVALID_FLAG_VALUE);
        invAttr.setDescription("SDR spectrum is invalid");
        flagCoding.addAttribute(invAttr);

        double a = 2 * Math.PI * (0 / 31.0);
        Color color = new Color((float) (0.5 + 0.5 * Math.sin(a + rf1 * Math.PI)),
                                (float) (0.5 + 0.5 * Math.sin(a + gf1 * Math.PI)),
                                (float) (0.5 + 0.5 * Math.sin(a + bf1 * Math.PI)));
        outputProduct.addBitmaskDef(new BitmaskDef(invAttr.getName(),
                                                   invAttr.getDescription(),
                                                   flagCoding.getName() + "." + invAttr.getName(),
                                                   color,
                                                   0.4F));

        for (int i = 0; i < sdrBands.length; i++) {
            final Band sdrBand = sdrBands[i];
            final String flagName = "INVALID_" + sdrBand.getName().toUpperCase();
            final String flagDesc = "Invalid " + sdrBand.getDescription();

            flagCoding.addFlag(flagName,
                               1 << (sdrBand.getSpectralBandIndex() + 1),
                               flagDesc);

            a = 2 * Math.PI * (i + 1 / 31.0);
            color = new Color((float) (0.5 + 0.5 * Math.sin(a + rf1 * Math.PI)),
                              (float) (0.5 + 0.5 * Math.sin(a + gf1 * Math.PI)),
                              (float) (0.5 + 0.5 * Math.sin(a + bf1 * Math.PI)));
            outputProduct.addBitmaskDef(new BitmaskDef(flagName,
                                                       flagDesc,
                                                       flagCoding.getName() + "." + flagName,
                                                       color,
                                                       0.4F));
        }
        return flagCoding;
    }

    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(SdrOp.class, "Meris.Sdr");
        }
    }
}