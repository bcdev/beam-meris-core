/*
 * $Id: CloudTopPressureOp.java,v 1.2 2007/03/30 15:11:20 marcoz Exp $
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
package org.esa.beam.bop.meris.cloud;

import java.awt.Rectangle;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.bop.meris.AlbedomapConstants;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.meris.l2auxdata.DpmConfig;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.util.ResourceInstaller;
import org.esa.beam.util.SystemUtils;
import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.NullProgressMonitor;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.jnn.Jnn;
import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.2 $ $Date: 2007/03/30 15:11:20 $
 */
public class CloudTopPressureOp extends MerisBasisOp {

    private static final String INVALID_EXPRESSION = "l1_flags.INVALID or not l1_flags.LAND_OCEAN";

    private static final int BB760 = 10;

    private L2AuxData auxData;
    private JnnNet neuralNet;
    private L2CloudAuxData cloudAuxData;
    private Band invalidBand;
    
    @SourceProduct(alias="input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;
	

    @Override
    public Product initialize() throws OperatorException {
        try {
            loadNeuralNet();
        } catch (Exception e) {
            throw new OperatorException("Failed to load neural net ctp.nna:\n" + e.getMessage());
        }
        initAuxData();
        return createTargetProduct();
    }

    private void loadNeuralNet() throws IOException, JnnException {
        String auxdataSrcPath = "auxdata" + File.separator + "ctp";
        final String auxdataDestPath = ".beam" + File.separator +
                AlbedomapConstants.SYMBOLIC_NAME + File.separator +
                auxdataSrcPath;
        File auxdataTargetDir = new File(SystemUtils.getUserHomeDir(), auxdataDestPath);
        URL sourceUrl = ResourceInstaller.getSourceUrl(this.getClass());

        ResourceInstaller resourceInstaller = new ResourceInstaller(sourceUrl, auxdataSrcPath, auxdataTargetDir);
        resourceInstaller.install(".*", new NullProgressMonitor());
        
        File nnFile = new File(auxdataTargetDir, "ctp.nna");
        final InputStreamReader reader = new FileReader(nnFile);
        try {
            Jnn.setOptimizing(true);
            neuralNet = Jnn.readNna(reader);
        } finally {
            reader.close();
        }
    }

    private Product createTargetProduct() throws OperatorException {
        targetProduct = createCompatibleProduct(sourceProduct, "MER_CTP", "MER_L2");
        targetProduct.addBand("cloud_top_press", ProductData.TYPE_FLOAT32);

        invalidBand = createBooleanBandForExpression(INVALID_EXPRESSION, sourceProduct);
        
        return targetProduct;
    }
    
    private Band createBooleanBandForExpression(String expression,
			Product product) throws OperatorException {
    	
		Map<String, Object> parameters = new HashMap<String, Object>();
		BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[1];
		BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
		bandDescriptor.name = "bBand";
		bandDescriptor.expression = expression;
		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
		bandDescriptors[0] = bandDescriptor;
		parameters.put("targetBands", bandDescriptors);

		Product expProduct = GPF.createProduct("BandArithmetic", parameters, product);
		addSourceProduct("x", expProduct);
		return expProduct.getBand("bBand");
	}

    private void initAuxData() throws OperatorException {
        String configFile = "meris_l2_config.xml";
        DpmConfig dpmConfig;
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }
        try {
            auxData = new L2AuxData(dpmConfig, sourceProduct);
            int month = sourceProduct.getStartTime().getAsCalendar().get(Calendar.MONTH);
            cloudAuxData = new L2CloudAuxData(dpmConfig, month);
        } catch (Exception e) {
            throw new OperatorException("Failed to load L2AuxData:\n" + e.getMessage(), e);
        }

    }

    @Override
    public void computeTile(Band band, Tile targetTile, ProgressMonitor pm) throws OperatorException {
    	
    	Rectangle rectangle = targetTile.getRectangle();
        pm.beginTask("Processing frame...", rectangle.height);
        try {
        	Tile detector = getSourceTile(sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME), rectangle, pm);
        	Tile sza = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle, pm);
			Tile saa = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle, pm);
			Tile vza = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle, pm);
			Tile vaa = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle, pm);
			
			Tile lat = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_LAT_DS_NAME), rectangle, pm);
			Tile lon = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_LON_DS_NAME), rectangle, pm);
			
			Tile toar10 = getSourceTile(sourceProduct.getBand("radiance_10"), rectangle, pm);
			Tile toar11 = getSourceTile(sourceProduct.getBand("radiance_11"), rectangle, pm);
			
			Tile isInvalid = getSourceTile(invalidBand, rectangle, pm);

            final double[] nnIn = new double[7];
            final double[] nnOut = new double[1];

            int i = 0;
			for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
					if (pm.isCanceled()) {
						break;
					}
					if (isInvalid.getSampleBoolean(x, y)) {
						targetTile.setSample(x, y, 0);
					} else {
						double szaRad = sza.getSampleFloat(x, y) * MathUtils.DTOR;
						double vzaRad = vza.getSampleFloat(x, y) * MathUtils.DTOR;
						nnIn[0] = computeSurfAlbedo(lat.getSampleFloat(x, y), lon.getSampleFloat(x, y)); // albedo
						nnIn[1] = toar10.getSampleDouble(x, y);
						nnIn[2] = toar11.getSampleDouble(x, y)
								/ toar10.getSampleDouble(x, y);
						nnIn[3] = Math.cos(szaRad);
						nnIn[4] = Math.cos(vzaRad);
						nnIn[5] = Math.sin(vzaRad)
								* Math.cos(MathUtils.DTOR * (vaa.getSampleFloat(x, y) - saa.getSampleFloat(x, y)));
						nnIn[6] = auxData.central_wavelength[BB760][detector.getSampleInt(x, y)];

						neuralNet.process(nnIn, nnOut);
						targetTile.setSample(x, y, nnOut[0]);
					}
					i++;
				}
				pm.worked(1);
			}
        } finally {
            pm.done();
        }
    }

    private double computeSurfAlbedo(float latitude, float longitude) {
// if ( (cloudAuxData.surfAlb.Surfalb.tab2[0] >= 0.0) && pPixel->lon < 0.0) lon
// += 360.0;
        /* a priori tab values in 0-360 deg */
        /* Note that it is also assumed that longitude tab values are increasing */
        FractIndex[] SaIndex = FractIndex.createArray(2);
        Interp.interpCoord(latitude, cloudAuxData.surfAlb.getTab(0), SaIndex[0]);
        Interp.interpCoord(longitude, cloudAuxData.surfAlb.getTab(1), SaIndex[1]);

        /* 	if ( weight[0] > 0.5 ) index[0]++; v 4.3- align with DPM */
        /* 	if ( weight[1] > 0.5 ) index[1]++; */

        /* DPM #2.1.5-1 */
        /* 	*pfSA = Surfalb.LUT[index[0]][index[1]]; v4.3- align with DPM */
        double surfaceAlbedo = Interp.interpolate(cloudAuxData.surfAlb.getJavaArray(), SaIndex);
        return surfaceAlbedo;
    }


    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CloudTopPressureOp.class, "Meris.CloudTopPressureOp");
        }
    }
}
