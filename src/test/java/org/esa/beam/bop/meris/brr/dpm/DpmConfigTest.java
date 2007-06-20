/*
 * $Id: DpmConfigTest.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris.brr.dpm;

import junit.framework.TestCase;
import org.esa.beam.bop.meris.brr.DpmConfig;
import org.esa.beam.bop.meris.brr.DpmConfigException;
import org.esa.beam.bop.util.TestDataFactory;

import java.io.File;

public class DpmConfigTest extends TestCase {

    public void testX() throws DpmConfigException {
        final DpmConfig config = TestDataFactory.createConfig();
        assertEquals(new File("./auxdata/meris_l2"), config.getAuxDataDir());
    }
}

