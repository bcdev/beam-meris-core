/*
 * $Id: $
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
package org.esa.beam.meris.l2auxdata;

import java.util.Map;
import java.util.WeakHashMap;

import org.esa.beam.framework.datamodel.Product;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: $ $Date: $
 */
public class L2AuxdataProvider {
    private static L2AuxdataProvider instance;
    private DpmConfig dpmConfig;
    private final Map<Product, L2AuxData> map;
    
    public static synchronized L2AuxdataProvider getInstance() {
        if (instance == null) {
            instance = new L2AuxdataProvider();
        }
        return instance;
    }
    
    private L2AuxdataProvider() {
        map = new WeakHashMap<Product, L2AuxData>();
    }
    
    public synchronized L2AuxData getAuxdata(Product product) throws DpmConfigException {
        getDpmConfig();
        L2AuxData auxData = map.get(product);
        if (auxData == null) {
            auxData = loadAuxdata(product);
            map.put(product, auxData);
        }
        return auxData;
    }
    
    public synchronized DpmConfig getDpmConfig() throws DpmConfigException {
        if (dpmConfig == null ) {
            loadDpmConfig();
        }
        return dpmConfig;
    }

    private L2AuxData loadAuxdata(Product product) throws DpmConfigException {
        L2AuxData auxData;
        try {
            auxData = new L2AuxData(dpmConfig, product);
        } catch (Exception e) {
            throw new DpmConfigException("Could not load L2Auxdata: ", e);
        }
        return auxData;
    }
    
    private void loadDpmConfig() throws DpmConfigException {
        try {
            dpmConfig = new DpmConfig();
        } catch (Exception e) {
            throw new DpmConfigException("Failed to load configuration:\n" + e.getMessage(), e);
        }
    }
}
