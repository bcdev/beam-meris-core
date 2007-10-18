/*
 * $Id: CloudShadowOp.java,v 1.1 2007/03/27 12:52:22 marcoz Exp $
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

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.framework.gpf.support.TileRectCalculator;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;


/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:52:22 $
 */
public class CloudShadowOp extends MerisBasisOp {

    private static final int MEAN_EARTH_RADIUS = 6372000;

    private static final int MAX_ITER = 5;

    private static final double DIST_THRESHOLD = 1 / 740.0;

    private TileRectCalculator rectCalculator;
    private GeoCoding geoCoding;
    private TiePointGrid tpAltitude;

    @SourceProduct(alias="l1b")
    private Product l1bProduct;
    @SourceProduct(alias="cloud")
    private Product cloudProduct;
    @SourceProduct(alias="ctp")
    private Product ctpProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private int shadowWidth;

    @Override
    public Product initialize() throws OperatorException {
        targetProduct = createCompatibleProduct(cloudProduct, "MER_CLOUD_SHADOW", "MER_L2");
        Band cloudBand = ProductUtils.copyBand(CombinedCloudOp.FLAG_BAND_NAME, cloudProduct, targetProduct);
        FlagCoding sourceFlagCoding = cloudProduct.getBand(CombinedCloudOp.FLAG_BAND_NAME).getFlagCoding();
        ProductUtils.copyFlagCoding(sourceFlagCoding, targetProduct);
        cloudBand.setFlagCoding(targetProduct.getFlagCoding(sourceFlagCoding.getName()));

        if (shadowWidth == 0) {
            shadowWidth = 16;
        }
        rectCalculator = new TileRectCalculator(l1bProduct, shadowWidth, shadowWidth);
        geoCoding = l1bProduct.getGeoCoding();
        tpAltitude = l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME);

        return targetProduct;
    }

    @Override
    public void computeTile(Band band, Tile targetTile, ProgressMonitor pm) throws OperatorException {
    	
    	Rectangle targetRectangle = targetTile.getRectangle();
        Rectangle sourceRectangle = rectCalculator.computeSourceRectangle(targetRectangle);
        pm.beginTask("Processing frame...", sourceRectangle.height);
        try {
        	Tile szaTile = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), sourceRectangle, pm);
        	Tile saaTile = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME), sourceRectangle, pm);
        	Tile vzaTile = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME), sourceRectangle, pm);
        	Tile vaaTile = getSourceTile(l1bProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME), sourceRectangle, pm);
        	Tile cloudTile = getSourceTile(cloudProduct.getBand(CombinedCloudOp.FLAG_BAND_NAME), sourceRectangle, pm);
        	Tile ctpTile = getSourceTile(ctpProduct.getBand("cloud_top_press"), sourceRectangle, pm);

        	Tile cloudTargetRaster = getSourceTile(targetTile.getRasterDataNode(), targetRectangle, pm);

            for (int y = targetRectangle.y; y < targetRectangle.y + targetRectangle.height; y++) {
                for (int x = targetRectangle.x; x < targetRectangle.x + targetRectangle.width; x++) {
                    cloudTargetRaster.setSample(x, y, cloudTile.getSampleInt(x, y));
                }
            }

            int sourceIndex = 0;
            for (int y = sourceRectangle.y; y < sourceRectangle.y + sourceRectangle.height; y++) {
                for (int x = sourceRectangle.x; x < sourceRectangle.x + sourceRectangle.width; x++) {
                    if (cloudTile.getSampleInt(x, y) == CombinedCloudOp.FLAG_CLOUD) {
                        final float sza = szaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                        final float saa = saaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                        final float vza = vzaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                        final float vaa = vaaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;

                        PixelPos pixelPos = new PixelPos(x, y);
                        final GeoPos geoPos = geoCoding.getGeoPos(pixelPos, null);
                        float cloudAlt = computeHeightFromPressure(ctpTile.getSampleFloat(x, y));
                        GeoPos shadowPos = getCloudShadow2(sza, saa, vza, vaa, cloudAlt, geoPos);
                        if (shadowPos != null) {
                            pixelPos = geoCoding.getPixelPos(shadowPos, pixelPos);

                            if (targetRectangle.contains(pixelPos)) {
                                final int pixelX = (int) Math.floor(pixelPos.x);
                                final int pixelY = (int) Math.floor(pixelPos.y);
                                int flagValue = cloudTile.getSampleInt(pixelX, pixelY);
                                if ((flagValue & CombinedCloudOp.FLAG_CLOUD_SHADOW) == 0) {
                                    flagValue += CombinedCloudOp.FLAG_CLOUD_SHADOW;
                                    cloudTargetRaster.setSample(pixelX, pixelY, flagValue);
                                }
                            }
                        }
                    }
                    sourceIndex++;
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    private float computeHeightFromPressure(float pressure) {
        return (float) (-8000 * Math.log(pressure / 1013f));
    }

    private GeoPos getCloudShadow2(float sza, float saa, float vza,
                                   float vaa, float cloudAlt, GeoPos appCloud) {

        double surfaceAlt = getAltitude(appCloud);

        // deltaX and deltaY are the corrections to apply to get the
        // real cloud position from the apparent one
        // deltaX/deltyY are in meters
        final double deltaX = -(cloudAlt - surfaceAlt) * Math.tan(vza)
                * Math.sin(vaa);
        final double deltaY = -(cloudAlt - surfaceAlt) * Math.tan(vza)
                * Math.cos(vaa);

        // distLat and distLon are in degrees
        double distLat = -(deltaY / MEAN_EARTH_RADIUS) * MathUtils.RTOD;
        double distLon = -(deltaX / (MEAN_EARTH_RADIUS * Math.cos(appCloud
                .getLat()
                * MathUtils.DTOR)))
                * MathUtils.RTOD;

        double latCloud = appCloud.getLat() + distLat;
        double lonCloud = appCloud.getLon() + distLon;

        // once the cloud position is know, we iterate to get the shadow
        // position
        int iter = 0;
        double dist = 2 * DIST_THRESHOLD;
        surfaceAlt = 0;
        double lat0, lon0;
        double lat = latCloud;
        double lon = lonCloud;
        GeoPos pos = new GeoPos();

        while ((iter < MAX_ITER) && (dist > DIST_THRESHOLD)
                && (surfaceAlt < cloudAlt)) {
            lat0 = lat;
            lon0 = lon;
            pos.setLocation((float) lat, (float) lon);
            surfaceAlt = getAltitude(pos);

            double deltaProjX = (cloudAlt - surfaceAlt) * Math.tan(sza)
                    * Math.sin(saa);
            double deltaProjY = (cloudAlt - surfaceAlt) * Math.tan(sza)
                    * Math.cos(saa);

            // distLat and distLon are in degrees
            distLat = -(deltaProjY / MEAN_EARTH_RADIUS) * MathUtils.RTOD;
            lat = latCloud + distLat;
            distLon = -(deltaProjX / (MEAN_EARTH_RADIUS * Math.cos(lat
                    * MathUtils.DTOR)))
                    * MathUtils.RTOD;
            lon = lonCloud + distLon;

            dist = Math.max(Math.abs(lat - lat0), Math.abs(lon - lon0));
            iter++;
        }
        if (surfaceAlt < cloudAlt && iter < MAX_ITER && dist < DIST_THRESHOLD) {
            return new GeoPos((float) lat, (float) lon);
        }
        return null;
    }

    private float getAltitude(GeoPos geoPos) {
        float altitude;
        final PixelPos pixelPos = geoCoding.getPixelPos(geoPos, null);
        //TODO
//		if (workProduct.getProductType().equals(EnvisatConstants.MERIS_FSG_L1B_PRODUCT_TYPE_NAME)) {
//			resampling.computeIndex(pixelPos.x, pixelPos.y, width, height, resamplingIndex);
//			altitude = resampling.resample(resamplingRaster, resamplingIndex);
//		} else {
        altitude = tpAltitude.getPixelFloat(pixelPos.x, pixelPos.y);
//		}
        return altitude;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CloudShadowOp.class, "Meris.CloudShadow");
        }
    }
}
