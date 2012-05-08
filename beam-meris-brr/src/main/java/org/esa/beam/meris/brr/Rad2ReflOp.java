/*
 * $Id: Rad2ReflOp.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
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
package org.esa.beam.meris.brr;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.jai.ResolutionLevel;
import org.esa.beam.jai.VirtualBandOpImage;
import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.meris.l2auxdata.L2AuxDataException;
import org.esa.beam.meris.l2auxdata.L2AuxDataProvider;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import java.awt.Rectangle;
import java.awt.image.Raster;


@OperatorMetadata(alias = "Meris.Rad2Refl",
                  version = "1.0",
                  internal = true,
                  authors = "Marco Zühlke",
                  copyright = "(c) 2007 by Brockmann Consult",
                  description = "Converts radiances into reflectances.")
public class Rad2ReflOp extends MerisBasisOp implements Constants {

    public static final String RADIANCE_BAND_PREFIX = "radiance";
    public static final String RHO_TOA_BAND_PREFIX = "rho_toa";


    @SourceProduct(alias = "input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    private transient L2AuxData auxData;
    private transient RasterDataNode detectorIndexBand;
    private transient RasterDataNode sunZenihTPG;
    private VirtualBandOpImage invalidImage;

    @Override
    public void initialize() throws OperatorException {
        try {
            auxData = L2AuxDataProvider.getInstance().getAuxdata(sourceProduct);
        } catch (L2AuxDataException e) {
            throw new OperatorException(e.getMessage(), e);
        }

        detectorIndexBand = sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME);
        sunZenihTPG = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME);
        invalidImage = VirtualBandOpImage.createMask("l1_flags.INVALID", sourceProduct, ResolutionLevel.MAXRES);

        targetProduct = createCompatibleProduct(sourceProduct, "MER", "MER_L2");
        int spectralBandIndex = 0;
        for (int i = 0; i < sourceProduct.getNumBands(); i++) {
            if (sourceProduct.getBandAt(i).getName().startsWith(RADIANCE_BAND_PREFIX)) {
                Band rhoToaBand = targetProduct.addBand(RHO_TOA_BAND_PREFIX + "_" + (spectralBandIndex + 1),
                                                        ProductData.TYPE_FLOAT32);
                Band radianceBand = sourceProduct.getBand(RADIANCE_BAND_PREFIX + "_" + (spectralBandIndex + 1));
                ProductUtils.copySpectralBandProperties(radianceBand, rhoToaBand);
                rhoToaBand.setNoDataValueUsed(true);
                rhoToaBand.setNoDataValue(BAD_VALUE);
                spectralBandIndex++;
            }
        }
        if (sourceProduct.getPreferredTileSize() != null) {
            targetProduct.setPreferredTileSize(sourceProduct.getPreferredTileSize());
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Rectangle rectangle = targetTile.getRectangle();
        Tile detectorTile = getSourceTile(detectorIndexBand, rectangle);
        Tile sza = getSourceTile(sunZenihTPG, rectangle);
        Raster isInvalid = invalidImage.getData(rectangle);
        final int spectralBandIndex = targetBand.getSpectralBandIndex();
        final String srcBandName = RADIANCE_BAND_PREFIX + "_" + (spectralBandIndex + 1);
        final Tile radianceTile = getSourceTile(sourceProduct.getBand(srcBandName), rectangle);
        final double seasonal_factor = auxData.seasonal_factor;

        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                if (isInvalid.getSample(x, y, 0) != 0) {
                    targetTile.setSample(x, y, BAD_VALUE);
                } else {
                    final double constantTerm = (Math.PI / Math.cos(sza.getSampleFloat(x, y) * MathUtils.DTOR)) * seasonal_factor;
                    final int detectorIndex = detectorTile.getSampleInt(x, y);
                    // DPM #2.1.4-1
                    final float aRhoToa = (float) ((radianceTile.getSampleFloat(x, y) * constantTerm) / auxData.detector_solar_irradiance[spectralBandIndex][detectorIndex]);
                    targetTile.setSample(x, y, aRhoToa);
                }
            }
        }

    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(Rad2ReflOp.class);
        }
    }
}
