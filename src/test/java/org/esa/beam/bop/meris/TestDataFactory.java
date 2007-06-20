package org.esa.beam.bop.meris;

/*
 * $Id: TestDataFactory.java,v 1.1 2007/03/27 12:52:23 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */

import junit.framework.Assert;
import org.esa.beam.bop.meris.brr.dpm.DpmConfig;
import org.esa.beam.bop.meris.brr.dpm.DpmConfigException;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;

import java.io.IOException;
import java.io.InputStreamReader;

public class TestDataFactory {

    public static Product createL1bProduct() {
        Product product = new Product("name", "type", 10, 10);
        final MetadataElement mph = new MetadataElement("MPH");
        mph.addAttribute(
                new MetadataAttribute("SENSING_START", new ProductData.ASCII("07-Jun-2004 14:10:20.543627"), true));
        mph.addAttribute(
                new MetadataAttribute("SENSING_STOP", new ProductData.ASCII("07-Jun-2004 15:30:10.236584"), true));
        product.getMetadataRoot().addElement(mph);
        return product;
    }

    public static DpmConfig createConfig() {
        DpmConfig dpmConfig = null;
        InputStreamReader reader = new InputStreamReader(TestDataFactory.class.getResourceAsStream("test_config.xml"));
        try {
            dpmConfig = new DpmConfig(reader);
        } catch (DpmConfigException e) {
            Assert.fail("DpmConfigException not expected");
        }
        try {
            reader.close();
        } catch (IOException e) {
        }
        return dpmConfig;
    }

}
