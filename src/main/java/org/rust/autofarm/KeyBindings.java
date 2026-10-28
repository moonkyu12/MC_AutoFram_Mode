package org.rust.autofarm;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;

public final class KeyBindings {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Autofarm.MODID, "main"));

    public static final KeyMapping TOGGLE =
            new KeyMapping("key.autofarm.toggle", InputConstants.KEY_DELETE, CATEGORY);

    public static final KeyMapping LOOP =
            new KeyMapping("key.autofarm.loop", InputConstants.KEY_END, CATEGORY);

    public static final KeyMapping PLANT =
            new KeyMapping("key.autofarm.plant", InputConstants.KEY_HOME, CATEGORY);

    public static final KeyMapping OPEN_CONFIG =
            new KeyMapping("key.autofarm.config", InputConstants.KEY_INSERT, CATEGORY);

    private KeyBindings() {}

    static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(LOOP);
        event.register(PLANT);
        event.register(OPEN_CONFIG);
    }
}
