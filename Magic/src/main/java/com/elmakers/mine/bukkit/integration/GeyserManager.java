package com.elmakers.mine.bukkit.integration;

import java.util.UUID;

import org.geysermc.connector.GeyserConnector;

import com.elmakers.mine.bukkit.api.magic.MageController;

public class GeyserManager {

    public GeyserManager(MageController controller) {
    }

    public boolean isBedrock(UUID uuid) {
        return GeyserConnector.getInstance().getPlayerByUuid(uuid) != null;
    }
}
