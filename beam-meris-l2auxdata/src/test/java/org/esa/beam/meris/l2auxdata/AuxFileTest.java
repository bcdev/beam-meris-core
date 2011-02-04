/*
 * $Id: AuxFileTest.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.meris.l2auxdata;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.File;
import java.io.IOException;

public class AuxFileTest extends TestCase {

    public static final String CASE1_TEST_FILE = "case1/case1.60.04.prd";
    public static final String CASE2_TEST_FILE = "case2/case2.42.00.prd";

//    private File _auxDataDir;

    public AuxFileTest(String s) {
        super(s);
    }

    public static Test suite() {
        return new TestSuite(AuxFileTest.class);
    }
/*
    protected void setUp() throws Exception {
        final DpmConfig config = TestDataFactory.createConfig();
        _auxDataDir = config.getAuxDataDir();
    }
*/

    public void testConstructor() {
        final AuxFileInfo fileInfo = AuxDatabase.getInstance().getFileInfo('Z');
        final AuxFile auxFile = new AuxFile(fileInfo, new File("./unknown.prd"));
        assertEquals(new File("./unknown.prd"), auxFile.getFile());
        assertEquals(false, auxFile.isOpen());
        assertEquals(null, auxFile.getInputStream());
    }

    public void testOpenWithInvalidFile() {
        final AuxFileInfo fileInfo = AuxDatabase.getInstance().getFileInfo('Z');
        final AuxFile auxFile = new AuxFile(fileInfo, new File("./unknown.prd"));
        try {
            auxFile.open();
            fail("IOException expected");
        } catch (IOException e) {
            assertEquals(false, auxFile.isOpen());
        }
    }

    public void testCloseWithoutOpen() {
        final AuxFileInfo fileInfo = AuxDatabase.getInstance().getFileInfo('Z');
        final AuxFile auxFile = new AuxFile(fileInfo, new File("./unknown.prd"));
        auxFile.close();
    }

/*
    public void testThatNewDataInstanceIsReturnedByRead() throws L2AuxDataException, IOException {
        final File file = new File(_auxDataDir, CASE1_TEST_FILE);
        AuxFile auxFile = AuxFile.open('T', file);
        assertEquals(true, auxFile.isOpen());

        ProductData data1 = auxFile.readRecord("T000", ProductData.TYPE_ASCII);
        ProductData data2 = auxFile.readRecord("T000", ProductData.TYPE_ASCII);
        ProductData data3 = auxFile.readRecord("T502", ProductData.TYPE_FLOAT32);
        ProductData data4 = auxFile.readRecord("T502", ProductData.TYPE_FLOAT32);
        auxFile.close();

        assertNotSame(data1, data2);
        assertNotSame(data3, data4);
        assertNotSame(data1, data3);
        assertNotSame(data2, data4);
    }

    public void testThatIllegalVariableIDsAreHandled() throws IOException {
        try {
            final File file = new File(_auxDataDir, CASE1_TEST_FILE);
            AuxFile auxFile = AuxFile.open('T', file);
            assertEquals(true, auxFile.isOpen());
            auxFile.readRecord("A000", ProductData.TYPE_ASCII);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // OK
        }
    }

    public void testDatabaseT() throws IOException {
        ProductData data;
        final File file = new File(_auxDataDir, CASE1_TEST_FILE);
        AuxFile auxFile = AuxFile.open('T', file);
        assertEquals(true, auxFile.isOpen());

        //    T000 - Product file name
        data = auxFile.readRecord("T000", ProductData.TYPE_ASCII);
        assertEquals(62, data.getNumElems());
        assertEquals("PRODUCT=\"MER_OC1_AXTACR20040414_204704_20020321_193100_2012032", data.getElemString());

        //    T106 - Number of Data Set Records
        data = auxFile.readRecord("T106", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    T10E - Number of Data Set Records
        data = auxFile.readRecord("T10E", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    T10M - Number of Data Set Records
        data = auxFile.readRecord("T10M", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    T10U - Number of Data Set Records
        data = auxFile.readRecord("T10U", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    T112 - Number of Data Set Records
        data = auxFile.readRecord("T112", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(6, data.getElemInt());

        //    T11A - Number of Data Set Records
        data = auxFile.readRecord("T11A", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(27, data.getElemInt());

        //    T200 - wind speed tabulated values for GADS - Geometrical factor R
        data = auxFile.readRecord("T200", ProductData.TYPE_UINT8);
        assertEquals(4, data.getNumElems());
        assertEquals(0, data.getElemUIntAt(0));
        assertEquals(4, data.getElemUIntAt(1));
        assertEquals(10, data.getElemUIntAt(2));
        assertEquals(16, data.getElemUIntAt(3));

        //    T20L - Water refraction index
        data = auxFile.readRecord("T20L", ProductData.TYPE_FLOAT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1.34f, data.getElemFloat(), 1e-6);

        //    T502 - log10 polynomial coefficients for l = 510 nm
        data = auxFile.readRecord("T502", ProductData.TYPE_FLOAT32);
        assertEquals(6, data.getNumElems());
        assertEquals(0.389971f, data.getElemFloatAt(0), 1e-5f);
        assertEquals(-3.86418f, data.getElemFloatAt(1), 1e-5f);
        assertEquals(3.16839, data.getElemFloatAt(2), 1e-5f);
        assertEquals(-20.32915f, data.getElemFloatAt(3), 1e-5f);
        assertEquals(63.21376f, data.getElemFloatAt(4), 1e-5f);
        assertEquals(-113.8629f, data.getElemFloatAt(5), 1e-5f);

        //    T504 - Band number selected for computation of Chl1 (band number starting at 1)
        //    Values: 2   3   4
        data = auxFile.readRecord("T504", ProductData.TYPE_UINT8);
        assertEquals(3, data.getNumElems());
        assertEquals(2, data.getElemUIntAt(0));
        assertEquals(3, data.getElemUIntAt(1));
        assertEquals(4, data.getElemUIntAt(2));

        // T203 - qs tabulated values for GADS Thresholds and ADS glint reflectance
        data = auxFile.readRecord("T203", ProductData.TYPE_FLOAT32);
        float[] tdata = new float[]{
            15000000,
            17500000,
            20000000,
            22500000,
            25000000,
            27500000,
            30000000,
            32500000,
            35000000,
            37500000,
            40000000,
            42500000,
            45000000,
            47500000,
            50000000,
            52500000,
            55000000,
            57500000,
            60000000,
            62500000,
            65000000,
            67500000,
            70000000,
            72500000,
            75000000,
            77500000,
            80000000
        };

        assertEquals(tdata.length, data.getNumElems());
        for (int i = 0; i < tdata.length; i++) {
            tdata[i] *= 1e-6f;
            assertEquals(tdata[i], data.getElemFloatAt(i), 1e-5f);
        }

        auxFile.close();


    }

    public void testDatabaseU() throws IOException {
        ProductData data;
        final File file = new File(_auxDataDir, CASE2_TEST_FILE);
        AuxFile auxFile = AuxFile.open('U', file);
        assertEquals(true, auxFile.isOpen());

        //    U000 - Product file name
        data = auxFile.readRecord("U000", ProductData.TYPE_ASCII);
        assertEquals(62, data.getNumElems());
        assertEquals("PRODUCT=\"MER_OC2_AXTACR20040415_101130_20020321_193100_2012032", data.getElemString());

        //    U106 - Number of Data Set Records
        data = auxFile.readRecord("U106", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    U10E - Number of Data Set Records
        data = auxFile.readRecord("U10E", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    U10M - Number of Data Set Records
        data = auxFile.readRecord("U10M", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    U10U - Number of Data Set Records
        data = auxFile.readRecord("U10U", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    U112 - Number of Data Set Records
        data = auxFile.readRecord("U112", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(100, data.getElemInt());

        //    U11A - Number of Data Set Records
        data = auxFile.readRecord("U11A", ProductData.TYPE_INT32);
        assertEquals(1, data.getNumElems());
        assertEquals(1, data.getElemInt());

        //    U200 - l tabulated values
        data = auxFile.readRecord("U200", ProductData.TYPE_FLOAT32);
        assertEquals(10, data.getNumElems());
        assertEquals(412.5, data.getElemFloatAt(0), 1e-5f);
        assertEquals(442.5, data.getElemFloatAt(1), 1e-5f);
        assertEquals(490.0, data.getElemFloatAt(2), 1e-5f);
        assertEquals(510.0, data.getElemFloatAt(3), 1e-5f);
        assertEquals(560.0, data.getElemFloatAt(4), 1e-5f);
        assertEquals(620.0, data.getElemFloatAt(5), 1e-5f);
        assertEquals(665.0, data.getElemFloatAt(6), 1e-5f);
        assertEquals(708.75, data.getElemFloatAt(7), 1e-5f);
        assertEquals(778.75, data.getElemFloatAt(8), 1e-5f);
        assertEquals(865.0, data.getElemFloatAt(9), 1e-5f);

    }
*/
}
