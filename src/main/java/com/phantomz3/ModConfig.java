package com.phantomz3;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = LifestealMod.MOD_ID)
public class ModConfig implements ConfigData {

    public int maxHeartCap = 40;
    public boolean healPlayerOnWithdraw = false;
}
