package org.esa.beam.meris.brr;


import org.esa.beam.framework.dataio.ProductIO;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.util.io.FileUtils;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;

import static org.junit.Assert.*;

public class BrrOpIntegrationTest {

    private File testOutDirectory;

    @BeforeClass
    public static void beforeClass() throws ParseException {
        GPF.getDefaultInstance().getOperatorSpiRegistry().addOperatorSpi(new BrrOp.Spi());
    }

    @AfterClass
    public static void afterClass() throws ParseException {
        GPF.getDefaultInstance().getOperatorSpiRegistry().removeOperatorSpi(new BrrOp.Spi());
    }

    @Before
    public void setUp() {
        testOutDirectory = new File("output");
        if (!testOutDirectory.mkdirs()) {
            fail("unable to create test directory: " + testOutDirectory);
        }
    }

    @After
    public void tearDown() {
        if (testOutDirectory != null) {
            if (!FileUtils.deleteTree(testOutDirectory)) {
                fail("Unable to delete test directory: " + testOutDirectory);
            }
        }
    }

    @Test
    public void testProcessMerisL1B() throws IOException {
        final Product merisL1BProduct = MerisL1BProduct.create();

        Product savedProduct = null;
        final Product target = GPF.createProduct("Meris.Brr", GPF.NO_PARAMS, merisL1BProduct);
        try {
            final String targetProductPath = testOutDirectory.getAbsolutePath() + File.separator + "meris_brr.dim";
            ProductIO.writeProduct(target, targetProductPath, "BEAM-DIMAP");

            savedProduct = ProductIO.readProduct(targetProductPath);
            assertNotNull(savedProduct);

            assertCorrectBand("brr_1", new float[]{0.17513838410377502f, 0.17518553137779236f}, savedProduct);
            assertCorrectBand("brr_2", new float[]{0.13967828452587128f, 0.14011628925800323f}, savedProduct);
            assertCorrectBand("brr_3", new float[]{0.10279937833547592f, 0.10264705866575241f}, savedProduct);
            assertCorrectBand("brr_4", new float[]{0.08967681229114532f, 0.08933483064174652f}, savedProduct);
            assertCorrectBand("brr_5", new float[]{0.06188041716814041f, 0.061751339584589005f}, savedProduct);
            assertCorrectBand("brr_6", new float[]{0.04040605574846268f, 0.040280744433403015f}, savedProduct);
            assertCorrectBand("brr_7", new float[]{0.0338885635137558f, 0.03377619758248329f}, savedProduct);
            assertCorrectBand("brr_8", new float[]{0.031664226204156876f, 0.03201078623533249f}, savedProduct);
            assertCorrectBand("brr_9", new float[]{0.028185680508613586f, 0.028215745463967323f}, savedProduct);
            assertCorrectBand("brr_10", new float[]{0.023523956537246704f, 0.023352349177002907f}, savedProduct);
            assertCorrectBand("brr_12", new float[]{0.02157190628349781f, 0.021620457991957664f}, savedProduct);
            assertCorrectBand("brr_13", new float[]{0.01653626374900341f, 0.0162785854190588f}, savedProduct);
            assertCorrectBand("brr_14", new float[]{0.015317732468247414f, 0.014979645609855652f}, savedProduct);
        } finally {
            if (savedProduct != null) {
                savedProduct.dispose();
            }
        }
    }

    private void assertCorrectBand(String bandName, float[] data, Product savedProduct) {
        final Band brr_1 = savedProduct.getBand(bandName);
        assertNotNull(brr_1);
        assertEquals(data[0], brr_1.getSampleFloat(0, 0), 1e-8);
        assertEquals(data[1], brr_1.getSampleFloat(1, 0), 1e-8);
    }
}
