package com.gtceu.calcboard.client.gui;

import com.gtceu.calcboard.api.storage.BoardManager;
import com.gtceu.calcboard.api.storage.BoardSettings;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

public class FolderBrowserPersistenceTest {

    private File tempSaveFile;

    @BeforeEach
    public void setup() throws Exception {
        BoardManager.getInstance().resetToDefault();
        tempSaveFile = File.createTempFile("calcboard_test_persistence", ".nbt");
    }

    @AfterEach
    public void cleanup() {
        if (tempSaveFile != null && tempSaveFile.exists()) {
            tempSaveFile.delete();
        }
    }

    @Test
    public void testFolderCollapseSettingsSerialization() {
        BoardSettings settings = new BoardSettings();
        settings.setFolderCollapsed("power/generators", true);
        settings.setFolderCollapsed("logistics", true);
        Assertions.assertTrue(settings.isFolderCollapsed("power/generators"));
        Assertions.assertTrue(settings.isFolderCollapsed("logistics"));
        Assertions.assertFalse(settings.isFolderCollapsed("power/storage"));

        CompoundTag tag = new CompoundTag();
        settings.serializeNBT(tag);

        BoardSettings reloaded = new BoardSettings();
        reloaded.deserializeNBT(tag);
        Assertions.assertTrue(reloaded.isFolderCollapsed("power/generators"));
        Assertions.assertTrue(reloaded.isFolderCollapsed("logistics"));
        Assertions.assertFalse(reloaded.isFolderCollapsed("power/storage"));

        reloaded.setFolderCollapsed("logistics", false);
        Assertions.assertFalse(reloaded.isFolderCollapsed("logistics"));
    }

    @Test
    public void testFolderLifecycleAutoSyncWithCollapsedFolders() {
        BoardManager bm = BoardManager.getInstance();
        bm.getPages().clear();
        bm.addPage("Steam Turbine", "power/steam");
        bm.addPage("Gas Generator", "power/gas");

        bm.setFolderCollapsed("power/steam", true);
        Assertions.assertTrue(bm.isFolderCollapsed("power/steam"));

        bm.renameFolder("power", "energy");
        Assertions.assertFalse(bm.isFolderCollapsed("power/steam"));
        Assertions.assertTrue(bm.isFolderCollapsed("energy/steam"));

        bm.deleteFolder("energy");
        Assertions.assertFalse(bm.isFolderCollapsed("energy/steam"));
        Assertions.assertTrue(bm.getCollapsedFolders().isEmpty());
    }

    @Test
    public void testFullBoardSaveAndLoadPreservesFolderCollapseState() {
        BoardManager bm = BoardManager.getInstance();
        bm.getPages().clear();
        bm.addPage("Page A", "tech/electronics");
        bm.setFolderCollapsed("tech/electronics", true);
        bm.setFolderCollapsed("tech", true);

        boolean saved = bm.saveToFile(tempSaveFile);
        Assertions.assertTrue(saved);

        bm.resetToDefault();
        Assertions.assertFalse(bm.isFolderCollapsed("tech/electronics"));
        Assertions.assertFalse(bm.isFolderCollapsed("tech"));

        boolean loaded = bm.loadFromFile(tempSaveFile);
        Assertions.assertTrue(loaded);
        Assertions.assertTrue(bm.isFolderCollapsed("tech/electronics"));
        Assertions.assertTrue(bm.isFolderCollapsed("tech"));
    }

    @Test
    public void testPageBrowserDrawerCleanupOnPageDeleted() {
        com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer drawer = new com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer(null);
        drawer.getSelectedPageIds().add("page-to-delete");
        drawer.getSelectedPageIds().add("page-to-keep");

        drawer.onPageDeleted("page-to-delete");
        Assertions.assertFalse(drawer.getSelectedPageIds().contains("page-to-delete"));
        Assertions.assertTrue(drawer.getSelectedPageIds().contains("page-to-keep"));

        drawer.onMultiplePagesDeleted(java.util.List.of("page-to-keep"));
        Assertions.assertTrue(drawer.getSelectedPageIds().isEmpty());
    }

    @Test
    public void testFolderCollapseSharedAcrossDrawerInstances() {
        BoardManager bm = BoardManager.getInstance();
        bm.setFolderCollapsed("automation/sub1", true);

        com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer drawer1 = new com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer(null);
        Assertions.assertTrue(drawer1.getCollapsedFolders().contains("automation/sub1"));

        drawer1.getCollapsedFolders().add("automation/sub2");

        com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer drawer2 = new com.gtceu.calcboard.client.gui.widget.PageBrowserDrawer(null);
        Assertions.assertTrue(drawer2.getCollapsedFolders().contains("automation/sub1"));
        Assertions.assertTrue(drawer2.getCollapsedFolders().contains("automation/sub2"));
    }
}
