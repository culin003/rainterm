package com.raindrop.ui;

import com.raindrop.storage.ConnectionProfile;
import javafx.scene.control.Tab;

/**
 * Contract implemented by {@link TabManager} so {@link TerminalSplitArea} can
 * inspect and extend open terminal sessions without depending on the concrete
 * tab-management class.
 */
public interface TerminalAreaHost {
    ConnectionProfile getTabProfile(Tab tab);

    void openTab(ConnectionProfile profile);

    /** True when the tab hosts an SSH terminal widget (false for SFTP browser tabs). */
    boolean isTerminalTab(Tab tab);
}
