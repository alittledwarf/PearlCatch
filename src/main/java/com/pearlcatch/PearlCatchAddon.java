package com.pearlcatch;

import com.mojang.logging.LogUtils;
import com.pearlcatch.modules.PearlCatchHorizontal;
import com.pearlcatch.modules.WindJump;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class PearlCatchAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Pearl Catch");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Pearl Catch");

        Modules.get().add(new PearlCatchHorizontal());
        Modules.get().add(new WindJump());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.pearlcatch";
    }
}
