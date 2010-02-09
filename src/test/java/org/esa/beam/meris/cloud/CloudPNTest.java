package org.esa.beam.meris.cloud;

import junit.framework.TestCase;
import org.esa.beam.util.math.MathUtils;

public class CloudPNTest extends TestCase {

    private CloudProbabilityOp cloudPn;

    @Override
    public void setUp() throws Exception {
//        new CloudProcessor().installAuxdata(); // just to extract auxdata
//        Map cloudConfig = new HashMap();
//        cloudConfig.put(CloudProbabilityOp.CONFIG_FILE_NAME, "cloud_config.txt");
//        cloudPn = new CloudProbabilityOp(null);
//        cloudPn.init(null, ProgressMonitor.NULL);
    }

    public void testDummy() throws Exception {

    }

    public void notestAltitudeCorrectedPressure() {
        double pressure = 1000;
        double altitude = 100;
        double correctedPressure = cloudPn.altitudeCorrectedPressure(pressure, altitude, true);
        assertEquals("corrected pressure", 988.08, correctedPressure, 0.01);
        correctedPressure = cloudPn.altitudeCorrectedPressure(pressure, altitude, false);
        assertEquals("corrected pressure", 1000, correctedPressure, 0.0001);
    }

    public void notestCalculateI() {
        double radiance = 50;
        float sunSpectralFlux = 10;
        double sunZenith = 45;
        double i = cloudPn.calculateI(radiance, sunSpectralFlux, sunZenith);
        assertEquals("calculated i", (radiance / (sunSpectralFlux * Math.cos(sunZenith * MathUtils.DTOR))), i, 0.00001);
    }
}