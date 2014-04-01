package org.esa.beam.meris.brr.ui;

import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.ModelessDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Action class for BRR Processing GUI
 *
 * @author olafd
 */
public class BrrProcessingAction extends AbstractVisatAction {
    private DefaultSingleTargetProductDialog dialog;

    @Override
    public void actionPerformed(CommandEvent event) {
        if (dialog == null) {
            dialog = new DefaultSingleTargetProductDialog("Meris.Brr",
                                                          getAppContext(),
                                                          "MERIS Rayleigh Correction - v1.0",
                                                          "BrrProcessorPlugIn");
            dialog.setTargetProductNameSuffix("_BRR");
        }
        dialog.show();
    }
}
