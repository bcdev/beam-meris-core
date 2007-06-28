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
import java.util.Calendar;

import org.esa.beam.bop.meris.AlbedomapConstants;
import org.esa.beam.bop.meris.brr.dpm.DpmConfig;
import org.esa.beam.bop.meris.brr.dpm.L2AuxData;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.Auxdata;
import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jexp.ParseException;
import com.bc.jexp.Term;
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

    private short[] detector;
    private float[] sza;
    private float[] saa;
    private float[] vza;
    private float[] vaa;
    private float[] lon;
    private float[] lat;
    private float[] toar10;
    private float[] toar11;
    private boolean[] isInvalid;
    private L2AuxData auxData;
    private JnnNet neuralNet;
    private L2CloudAuxData cloudAuxData;
    private Term term;
    
    @SourceProduct(alias="input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    public CloudTopPressureOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
    public Product initialize(ProgressMonitor pm) throws OperatorException {
        try {
            loadNeuralNet();
        } catch (Exception e) {
            throw new OperatorException("Failed to load neural net ctp.nna:\n" + e.getMessage());
        }
        initAuxData();
        return createTargetProduct();
    }

    private void loadNeuralNet() throws IOException, JnnException {
        Auxdata ctpAuxdata = new Auxdata(AlbedomapConstants.SYMBOLIC_NAME, "ctp");
        ctpAuxdata.installAuxdata(this);
        File ctpAuxDataDir = ctpAuxdata.getDefaultAuxdataDir();

        File nnFile = new File(ctpAuxDataDir, "ctp.nna");
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

        try {
			term = sourceProduct.createTerm(INVALID_EXPRESSION);
		} catch (ParseException e) {
			throw new OperatorException("Could not create Term for expression.", e);
		}
        
        return targetProduct;
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

    private void loadSourceTiles(Rectangle rectangle) throws OperatorException {

        detector = (short[]) getTile(sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME), rectangle).getDataBuffer().getElems();
        sza = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        saa = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), rectangle).getDataBuffer().getElems();
        vza = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), rectangle).getDataBuffer().getElems();
        vaa = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), rectangle).getDataBuffer().getElems();

        lat = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_LAT_DS_NAME), rectangle).getDataBuffer().getElems();
        lon = (float[]) getTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_LON_DS_NAME), rectangle).getDataBuffer().getElems();

        toar10 = (float[]) getTile(sourceProduct.getBand("radiance_10"), rectangle).getDataBuffer().getElems();
        toar11 = (float[]) getTile(sourceProduct.getBand("radiance_11"), rectangle).getDataBuffer().getElems();

        final int size = rectangle.height * rectangle.width;
        isInvalid = new boolean[size];
    	try {
	    	sourceProduct.readBitmask(rectangle.x, rectangle.y,
	    			rectangle.width, rectangle.height, term, isInvalid, ProgressMonitor.NULL);
		} catch (IOException e) {
			throw new OperatorException("Couldn't load bitmasks", e);
		}
    }

    @Override
    public void computeBand(Raster targetRaster,
            ProgressMonitor pm) throws OperatorException {
    	
    	Rectangle rectangle = targetRaster.getRectangle();
        final int size = rectangle.height * rectangle.width;
        pm.beginTask("Processing frame...", size);
        try {
        	loadSourceTiles(rectangle);

            ProductData productDataCtp = getTile(targetRaster.getRasterDataNode(), rectangle).getDataBuffer();
            float[] ctp = (float[]) productDataCtp.getElems();

            final double[] nnIn = new double[7];
            final double[] nnOut = new double[1];

            for (int i = 0; i < size; i++) {
                if (pm.isCanceled()) {
                    break;
                }
                if (isInvalid[i]) {
                    ctp[i] = 0;
                } else {
                    double szaRad = sza[i] * MathUtils.DTOR;
                    double vzaRad = vza[i] * MathUtils.DTOR;
                    nnIn[0] = computeSurfAlbedo(lat[i], lon[i]); // albedo
                    nnIn[1] = toar10[i];
                    nnIn[2] = toar11[i] / toar10[i];
                    nnIn[3] = Math.cos(szaRad);
                    nnIn[4] = Math.cos(vzaRad);
                    nnIn[5] = Math.sin(vzaRad)
                            * Math.cos(MathUtils.DTOR * (vaa[i] - saa[i]));
                    nnIn[6] = auxData.central_wavelength[BB760][detector[i]];

                    neuralNet.process(nnIn, nnOut);
                    ctp[i] = (float) nnOut[0];
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    private double computeSurfAlbedo(float latitude, float longitude) {
//	    if  ( (cloudAuxData.surfAlb.Surfalb.tab2[0] >= 0.0) && pPixel->lon < 0.0) lon += 360.0; 
        /* a priori tab values in 0-360 deg*/
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


    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(CloudTopPressureOp.class, "Meris.CloudTopPressureOp");
        }
    }
}
