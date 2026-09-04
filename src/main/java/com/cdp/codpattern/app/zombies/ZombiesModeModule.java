package com.cdp.codpattern.app.zombies;

import com.cdp.codpattern.app.match.extension.ModeAreaProtectionContributor;
import com.cdp.codpattern.app.match.extension.ModeDebugSnapshotContributor;
import com.cdp.codpattern.app.match.extension.ModeEntityReconciliationContributor;
import com.cdp.codpattern.app.match.extension.ModeHeldToolPreviewContributor;
import com.cdp.codpattern.app.match.extension.ModeModule;
import com.cdp.codpattern.app.match.extension.ModeObjectInteractionBypassContributor;
import com.cdp.codpattern.app.match.extension.ModePlayerLoginContributor;
import com.cdp.codpattern.app.match.model.GameModeDefinition;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesAreaProtectionContributor;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesDebugSnapshotContributor;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesHeldToolPreviewContributor;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesObjectInteractionBypassContributor;
import com.cdp.codpattern.app.zombies.model.ZombiesGameModeDefinitions;
import com.cdp.codpattern.app.zombies.service.ZombiesEntityReconciliationContributor;
import com.cdp.codpattern.app.zombies.service.ZombiesLoginRecoveryContributor;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Addon-owned Zombies mode definition and common server extensions. */
public final class ZombiesModeModule implements ModeModule {
    public static final ZombiesModeModule INSTANCE = new ZombiesModeModule();
    private static final ResourceLocation ID = new ResourceLocation("codpattern_zombies", "zombies");
    private static final List<ModePlayerLoginContributor> LOGIN_CONTRIBUTORS =
            List.of(new ZombiesLoginRecoveryContributor());
    private static final List<ModeEntityReconciliationContributor> ENTITY_CONTRIBUTORS =
            List.of(new ZombiesEntityReconciliationContributor());
    private static final List<ModeObjectInteractionBypassContributor> INTERACTION_CONTRIBUTORS =
            List.of(new ZombiesObjectInteractionBypassContributor());
    private static final List<ModeAreaProtectionContributor> PROTECTION_CONTRIBUTORS =
            List.of(new ZombiesAreaProtectionContributor());
    private static final List<ModeDebugSnapshotContributor> DEBUG_CONTRIBUTORS =
            List.of(new ZombiesDebugSnapshotContributor());
    private static final List<ModeHeldToolPreviewContributor> TOOL_PREVIEW_CONTRIBUTORS =
            List.of(new ZombiesHeldToolPreviewContributor());

    private ZombiesModeModule() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<GameModeDefinition> definitions() {
        return ZombiesGameModeDefinitions.definitions();
    }

    @Override
    public List<ModePlayerLoginContributor> playerLoginContributors() {
        return LOGIN_CONTRIBUTORS;
    }

    @Override
    public List<ModeEntityReconciliationContributor> entityReconciliationContributors() {
        return ENTITY_CONTRIBUTORS;
    }

    @Override
    public List<ModeObjectInteractionBypassContributor> objectInteractionBypassContributors() {
        return INTERACTION_CONTRIBUTORS;
    }

    @Override
    public List<ModeAreaProtectionContributor> areaProtectionContributors() {
        return PROTECTION_CONTRIBUTORS;
    }

    @Override
    public List<ModeDebugSnapshotContributor> debugSnapshotContributors() {
        return DEBUG_CONTRIBUTORS;
    }

    @Override
    public List<ModeHeldToolPreviewContributor> heldToolPreviewContributors() {
        return TOOL_PREVIEW_CONTRIBUTORS;
    }
}
