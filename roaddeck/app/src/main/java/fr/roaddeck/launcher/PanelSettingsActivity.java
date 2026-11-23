package fr.roaddeck.launcher;

/** Settings stay in the side-panel task instead of reopening the HOME launcher. */
public final class PanelSettingsActivity extends MainActivity {
    @Override protected boolean isPanelSettings(){return true;}
}
