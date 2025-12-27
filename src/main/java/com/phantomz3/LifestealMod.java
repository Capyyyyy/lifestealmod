package com.phantomz3;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.stream.Collectors; // if not already imported
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LifestealMod implements ModInitializer {

    public static final String MOD_ID = "lifestealmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Lifesteal mod has been initialized!");

        registerConfig();
        registerEvents();
        registerCommands();
        // registerReviveCommand();
        registerOpReviveCommand();

        // Check if player was revived on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            double playerMaxHealth = player.getAttributeBaseValue(
                EntityAttributes.MAX_HEALTH
            );
            // If player joins with low max health (meaning they were banned/dead), restore them
            if (playerMaxHealth <= 1.0) {
                double newMaxHealth = playerMaxHealth + 8.0;
                player
                    .getAttributeInstance(EntityAttributes.MAX_HEALTH)
                    .setBaseValue(newMaxHealth);
                player.setHealth((float) newMaxHealth);

                // Ensure survival mode
                player.changeGameMode(GameMode.SURVIVAL);

                player.sendMessage(
                    Text.literal("You have been revived!").formatted(
                        Formatting.GREEN
                    ),
                    true
                );
            }
        });
    }

    private void registerConfig() {
        AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
    }

    public static GameProfile getProfileFromEntry(BannedPlayerEntry entry) {
        try {
            // Iterate all fields to find the one holding GameProfile (the key)
            // This avoids issues with obfuscated field names
            for (Field field : net.minecraft.server
                .ServerConfigEntry.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(entry);
                if (value instanceof GameProfile) {
                    return (GameProfile) value;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void registerEvents() {
        // player death and drop heart
        ServerLivingEntityEvents.ALLOW_DEATH.register(
            (entity, source, amount) -> {
                if (entity instanceof PlayerEntity) {
                    ServerPlayerEntity player = (ServerPlayerEntity) entity; // Cast to ServerPlayerEntity
                    LivingEntity attacker = (LivingEntity) source.getAttacker();
                    ModConfig config = AutoConfig.getConfigHolder(
                        ModConfig.class
                    ).getConfig();

                    // do not continue if player has a totem equipped
                    if (
                        player.getMainHandStack().getItem() ==
                            Items.TOTEM_OF_UNDYING ||
                        player.getOffHandStack().getItem() ==
                        Items.TOTEM_OF_UNDYING
                    ) {
                        return true;
                    }

                    // killed by player
                    if (attacker instanceof PlayerEntity) {
                        PlayerEntity playerAttacker = (PlayerEntity) attacker;

                        // attacker has less than 'maxHeartCap' health
                        if (
                            attacker.getAttributeBaseValue(
                                EntityAttributes.MAX_HEALTH
                            ) <
                            config.maxHeartCap
                        ) {
                            // give one heart to the attacker
                            increasePlayerHealth(playerAttacker);
                            playerAttacker.sendMessage(
                                Text.literal(
                                    "You gained an additional heart!"
                                ).formatted(Formatting.GRAY),
                                true
                            );
                        } else {
                            ItemStack heartStack = createCustomNetherStar(
                                "Heart"
                            );
                            player.dropItem(heartStack, true);
                        }
                    } else if (!(attacker instanceof PlayerEntity)) {
                        ItemStack heartStack = createCustomNetherStar("Heart");
                        player.dropItem(heartStack, true);
                    }

                    // decrease the player's max health
                    double playerMaxHealth = player.getAttributeBaseValue(
                        EntityAttributes.MAX_HEALTH
                    );
                    player
                        .getAttributeInstance(EntityAttributes.MAX_HEALTH)
                        .setBaseValue(playerMaxHealth - 2.0);
                    player.sendMessage(
                        Text.literal("You lost a heart!").formatted(
                            Formatting.RED
                        ),
                        true
                    );

                    // update the player max health after decreasing it
                    playerMaxHealth = player.getAttributeBaseValue(
                        EntityAttributes.MAX_HEALTH
                    );

                    if (playerMaxHealth <= 1.0) {
                        ServerWorld serverWorld =
                            (ServerWorld) player.getEntityWorld();

                        if (
                            !serverWorld
                                .getGameRules()
                                .getBoolean(GameRules.KEEP_INVENTORY)
                        ) {
                            player.getInventory().dropAll();
                        }

                        BannedPlayerList bannedPlayerList = player
                            .getEntityWorld()
                            .getServer()
                            .getPlayerManager()
                            .getUserBanList();
                        BannedPlayerEntry bannedPlayerEntry =
                            new BannedPlayerEntry(
                                new PlayerConfigEntry(player.getGameProfile()), // ✅ Wrap in PlayerConfigEntry
                                null,
                                "Lifesteal Mod",
                                null,
                                "You lost all your hearts!"
                            );
                        bannedPlayerList.add(bannedPlayerEntry);
                        player.networkHandler.disconnect(
                            Text.literal("You lost all your hearts!")
                        );

                        player
                            .getEntityWorld()
                            .getServer()
                            .getPlayerManager()
                            .broadcast(
                                Text.literal(
                                    "→ " +
                                        player.getDisplayName().getString() +
                                        " has lost all of their hearts and is eliminated!"
                                ).formatted(Formatting.RED),
                                false
                            );

                        // return false to prevent the player from dying
                        return false;
                    }
                }

                return true;
            }
        );

        // Right-click heart to gain health
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack itemStack = player.getStackInHand(hand);
            if (
                itemStack.getItem() == Items.NETHER_STAR &&
                !(itemStack.hasGlint()) &&
                itemStack.getName().getString().equals("Heart")
            ) {
                if (player instanceof ServerPlayerEntity) {
                    ModConfig modConfig = AutoConfig.getConfigHolder(
                        ModConfig.class
                    ).getConfig();
                    double playerMaxHealth = player.getAttributeBaseValue(
                        EntityAttributes.MAX_HEALTH
                    );
                    if (playerMaxHealth >= modConfig.maxHeartCap) {
                        player.sendMessage(
                            Text.literal(
                                "You have reached the maximum health limit!"
                            ).formatted(Formatting.RED),
                            true
                        );
                        return ActionResult.FAIL; // Max health reached, prevent usage
                    }

                    player
                        .getAttributeInstance(EntityAttributes.MAX_HEALTH)
                        .setBaseValue(playerMaxHealth + 2.0);

                    player.sendMessage(
                        Text.literal(
                            "You gained an additional heart!"
                        ).formatted(Formatting.GREEN),
                        true
                    );

                    itemStack.decrement(1); // Consume the heart

                    return ActionResult.SUCCESS; // Successfully used the heart
                }
            }

            return ActionResult.PASS; // Other items pass through
        });

        // Right-click revive beacon to open a revive GUI to revive a player
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack itemStack = player.getStackInHand(hand);

            // Check for the specific "Revive Beacon"
            if (
                itemStack.getItem() == Items.BEACON &&
                itemStack.hasGlint() &&
                itemStack.getName().getString().equals("Revive Beacon")
            ) {
                if (player instanceof ServerPlayerEntity) {
                    ServerPlayerEntity serverPlayer =
                        (ServerPlayerEntity) player;

                    // Create a simple 9-slot chest inventory
                    SimpleInventory inventory = new SimpleInventory(27);

                    // Fill the inventory with player heads of players who are banned with the specific reason
                    serverPlayer
                        .getEntityWorld()
                        .getServer()
                        .getPlayerManager()
                        .getUserBanList()
                        .values()
                        .forEach(entry -> {
                            if (
                                "You lost all your hearts!".equals(
                                    entry.getReason()
                                )
                            ) {
                                // Get the PlayerConfigEntry key directly
                                PlayerConfigEntry config = entry.getKey();

                                String name = config != null
                                    ? config.name()
                                    : "Unknown";

                                ItemStack playerHead = new ItemStack(
                                    Items.PLAYER_HEAD
                                );
                                playerHead.set(
                                    DataComponentTypes.ITEM_NAME,
                                    Text.literal(name)
                                );

                                NbtCompound nbtCompound = new NbtCompound();
                                nbtCompound.putString("SkullOwner", name);
                                playerHead.set(
                                    DataComponentTypes.CUSTOM_DATA,
                                    NbtComponent.of(nbtCompound)
                                );

                                inventory.addStack(playerHead);
                            }
                        });

                    // Filling the inventory with gray glass panes to fill the remaining slots
                    // Filling the inventory with gray glass panes to fill the remaining slots
                    for (int i = 0; i < inventory.size(); i++) {
                        if (inventory.getStack(i).isEmpty()) {
                            ItemStack glassPane = new ItemStack(
                                Items.GRAY_STAINED_GLASS_PANE
                            );
                            glassPane.set(
                                DataComponentTypes.ITEM_NAME,
                                Text.literal("Empty")
                            );
                            inventory.setStack(i, glassPane);
                        }
                    }
                    // Open the chest GUI for the player
                    serverPlayer.openHandledScreen(
                        new SimpleNamedScreenHandlerFactory(
                            (syncId, playerInventory, playerEntity) ->
                                new ReviveScreenHandler(
                                    syncId,
                                    playerInventory,
                                    inventory
                                ),
                            Text.of("Revive Players")
                        )
                    );

                    return ActionResult.SUCCESS;
                }
            }

            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack itemStack = player.getStackInHand(hand);

            if (
                itemStack.getItem() == Items.BEACON &&
                itemStack.hasGlint() &&
                itemStack.getName().getString().equals("Revive Beacon")
            ) {
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });

        // When a player gets killed respect health cap
        ServerLivingEntityEvents.ALLOW_DEATH.register(
            (entity, source, amount) -> {
                if (entity instanceof PlayerEntity) {
                    PlayerEntity player = (PlayerEntity) entity;
                    double playerMaxHealth = player.getAttributeBaseValue(
                        EntityAttributes.MAX_HEALTH
                    );
                    ModConfig modConfig = AutoConfig.getConfigHolder(
                        ModConfig.class
                    ).getConfig();

                    if (playerMaxHealth > modConfig.maxHeartCap) {
                        player.sendMessage(
                            Text.literal(
                                "You have reached the maximum health limit!"
                            ).formatted(Formatting.RED),
                            true
                        );
                        player
                            .getAttributeInstance(EntityAttributes.MAX_HEALTH)
                            .setBaseValue(modConfig.maxHeartCap);
                    }
                }

                return true;
            }
        );
    }

    private ItemStack createCustomNetherStar(String name) {
        ItemStack heartStack = new ItemStack(Items.NETHER_STAR);
        heartStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
        heartStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
        //		LoreComponent loreComponent = new LoreComponent(List.of(Text.literal("Right click to consume")));
        //		heartStack.set(DataComponentTypes.LORE, loreComponent);
        return heartStack;
    }

    private ItemStack createReviveBeacon(String name) {
        ItemStack reviveBeaconStack = new ItemStack(Items.BEACON);
        reviveBeaconStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
        reviveBeaconStack.set(
            DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
            true
        );
        //		LoreComponent loreComponent = new LoreComponent(List.of(Text.literal("Right click to open revive GUI")));
        //		reviveBeaconStack.set(DataComponentTypes.LORE, loreComponent);

        return reviveBeaconStack;
    }

    private void increasePlayerHealth(PlayerEntity player) {
        double playerMaxHealth = player.getAttributeBaseValue(
            EntityAttributes.MAX_HEALTH
        );
        player
            .getAttributeInstance(EntityAttributes.MAX_HEALTH)
            .setBaseValue(playerMaxHealth + 2.0);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                dispatcher.register(
                    CommandManager.literal("lifesteal")
                        .then(
                            CommandManager.literal("withdraw").then(
                                CommandManager.argument(
                                    "amount",
                                    IntegerArgumentType.integer(0)
                                ).executes(context -> {
                                    ServerCommandSource source =
                                        context.getSource();
                                    ServerPlayerEntity player =
                                        source.getPlayer();
                                    int amount = IntegerArgumentType.getInteger(
                                        context,
                                        "amount"
                                    );

                                    double playerMaxHealth =
                                        player.getAttributeBaseValue(
                                            EntityAttributes.MAX_HEALTH
                                        );

                                    // if he tries to withdraw all of his health, don't let him
                                    if (amount >= playerMaxHealth / 2.0) {
                                        player.sendMessage(
                                            Text.literal(
                                                "Withdrawing heart failed!"
                                            ).formatted(Formatting.RED),
                                            true
                                        );
                                        return 0;
                                    }

                                    if (playerMaxHealth >= amount * 2.0) {
                                        double newMaxHealth =
                                            playerMaxHealth - amount * 2.0;
                                        // store current health
                                        double playerCurrentHealth =
                                            player.getHealth();

                                        // sets the current health too
                                        player
                                            .getAttributeInstance(
                                                EntityAttributes.MAX_HEALTH
                                            )
                                            .setBaseValue(newMaxHealth);

                                        ItemStack heartStack =
                                            createCustomNetherStar("Heart");
                                        heartStack.setCount(amount);
                                        player.giveItemStack(heartStack);

                                        player.sendMessage(
                                            Text.literal(
                                                "You have successfully withdrawn the heart!"
                                            ).formatted(Formatting.GREEN),
                                            true
                                        );
                                    } else {
                                        player.sendMessage(
                                            Text.literal(
                                                "Withdrawing heart failed!"
                                            ).formatted(Formatting.RED),
                                            true
                                        );
                                    }

                                    return amount;
                                })
                            )
                        )
                        .then(
                            CommandManager.literal("take")
                                .requires(source ->
                                    source.hasPermissionLevel(2)
                                )
                                .then(
                                    CommandManager.argument(
                                        "targets",
                                        EntityArgumentType.players()
                                    ).then(
                                        CommandManager.argument(
                                            "amount",
                                            IntegerArgumentType.integer(0)
                                        ).executes(context -> {
                                            Collection<
                                                ServerPlayerEntity
                                            > targets =
                                                EntityArgumentType.getPlayers(
                                                    context,
                                                    "targets"
                                                );
                                            int amount =
                                                IntegerArgumentType.getInteger(
                                                    context,
                                                    "amount"
                                                );

                                            for (ServerPlayerEntity target : targets) {
                                                double currentMaxHealth =
                                                    target.getAttributeBaseValue(
                                                        EntityAttributes.MAX_HEALTH
                                                    );
                                                double newMaxHealth = Math.max(
                                                    2.0,
                                                    currentMaxHealth -
                                                        amount * 2.0
                                                ); // 1 heart = 2.0 health

                                                target
                                                    .getAttributeInstance(
                                                        EntityAttributes.MAX_HEALTH
                                                    )
                                                    .setBaseValue(newMaxHealth);
                                                target.setHealth(
                                                    (float) newMaxHealth
                                                ); // Set player's health to
                                                // the new max health
                                            }

                                            return targets.size();
                                        })
                                    )
                                )
                        )
                        .then(
                            CommandManager.literal("set")
                                .requires(source ->
                                    source.hasPermissionLevel(2)
                                )
                                .then(
                                    CommandManager.argument(
                                        "targets",
                                        EntityArgumentType.players()
                                    ).then(
                                        CommandManager.argument(
                                            "amount",
                                            IntegerArgumentType.integer(0)
                                        ).executes(context -> {
                                            Collection<
                                                ServerPlayerEntity
                                            > targets =
                                                EntityArgumentType.getPlayers(
                                                    context,
                                                    "targets"
                                                );
                                            int amount =
                                                IntegerArgumentType.getInteger(
                                                    context,
                                                    "amount"
                                                );

                                            for (ServerPlayerEntity target : targets) {
                                                double currentMaxHealth =
                                                    target.getAttributeBaseValue(
                                                        EntityAttributes.MAX_HEALTH
                                                    );
                                                double newMaxHealth = Math.max(
                                                    2.0,
                                                    amount * 2.0
                                                ); // 1 heart = 2.0 health

                                                target
                                                    .getAttributeInstance(
                                                        EntityAttributes.MAX_HEALTH
                                                    )
                                                    .setBaseValue(newMaxHealth);
                                                target.setHealth(
                                                    (float) newMaxHealth
                                                ); // Set player's health to
                                                // the new max health
                                            }

                                            return targets.size();
                                        })
                                    )
                                )
                        )
                        .then(
                            CommandManager.literal("give")
                                .requires(source ->
                                    source.hasPermissionLevel(2)
                                )
                                .then(
                                    CommandManager.argument(
                                        "targets",
                                        EntityArgumentType.players()
                                    ).then(
                                        CommandManager.argument(
                                            "amount",
                                            IntegerArgumentType.integer(0)
                                        ).executes(context -> {
                                            Collection<
                                                ServerPlayerEntity
                                            > targets =
                                                EntityArgumentType.getPlayers(
                                                    context,
                                                    "targets"
                                                );
                                            int amount =
                                                IntegerArgumentType.getInteger(
                                                    context,
                                                    "amount"
                                                );

                                            for (ServerPlayerEntity target : targets) {
                                                double currentMaxHealth =
                                                    target.getAttributeBaseValue(
                                                        EntityAttributes.MAX_HEALTH
                                                    );
                                                double newMaxHealth = Math.max(
                                                    2.0,
                                                    currentMaxHealth +
                                                        amount * 2.0
                                                ); // 1 heart = 2.0 health

                                                target
                                                    .getAttributeInstance(
                                                        EntityAttributes.MAX_HEALTH
                                                    )
                                                    .setBaseValue(newMaxHealth);
                                                target.setHealth(
                                                    (float) newMaxHealth
                                                ); // Set player's health to
                                                // the new max health
                                            }

                                            return targets.size();
                                        })
                                    )
                                )
                        )
                );
            }
        );
    }

    private void registerReviveCommand() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                dispatcher.register(
                    CommandManager.literal("revive").then(
                        CommandManager.argument(
                            "player",
                            GameProfileArgumentType.gameProfile()
                        ).executes(context -> {
                            return executeRevive(
                                context.getSource(),
                                GameProfileArgumentType.getProfileArgument(
                                    context,
                                    "player"
                                )
                            );
                        })
                    )
                );

                // Registering /lifesteal revive [target] to do the same as /revive
                dispatcher.register(
                    CommandManager.literal("lifesteal").then(
                        CommandManager.literal("revive").then(
                            CommandManager.argument(
                                "player",
                                GameProfileArgumentType.gameProfile()
                            ).executes(context -> {
                                return executeRevive(
                                    context.getSource(),
                                    GameProfileArgumentType.getProfileArgument(
                                        context,
                                        "player"
                                    )
                                );
                            })
                        )
                    )
                );
            }
        );
    }

    // Register the new op revive command
    private void registerOpReviveCommand() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                dispatcher.register(
                    CommandManager.literal("lifesteal").then(
                        CommandManager.literal("oprevive")
                            .requires(source -> source.hasPermissionLevel(2)) // Only allow OPs to use this command
                            .then(
                                CommandManager.argument(
                                    "player",
                                    GameProfileArgumentType.gameProfile()
                                ).executes(context -> {
                                    return executeRevive(
                                        context.getSource(),
                                        GameProfileArgumentType.getProfileArgument(
                                            context,
                                            "player"
                                        )
                                    );
                                })
                            )
                    )
                );
            }
        );
    }

    // Extracted method for revive logic
    private int executeRevive(
        ServerCommandSource source,
        Collection<PlayerConfigEntry> targets // ← Change from GameProfile to PlayerConfigEntry
    ) {
        BannedPlayerList banList = source
            .getWorld()
            .getServer()
            .getPlayerManager()
            .getUserBanList();
        int successfullyRevived = 0;
        for (PlayerConfigEntry entry : targets) {
            // ← Change type here too
            BannedPlayerEntry bannedEntry = banList.get(entry); // ← No wrapper needed now!
            if (bannedEntry != null) {
                banList.remove(bannedEntry);
                successfullyRevived++;
                source.sendMessage(
                    Text.literal("Revived " + entry.name()).formatted(
                        // ← entry.name() instead of profile.name()
                        Formatting.GREEN
                    )
                );
            } else {
                source.sendMessage(
                    Text.literal(entry.name() + " is not banned.").formatted(
                        // ← Same here
                        Formatting.RED
                    )
                );
            }
        }
        return successfullyRevived;
    }
}
