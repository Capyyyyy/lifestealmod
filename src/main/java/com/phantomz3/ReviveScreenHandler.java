package com.phantomz3;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

public class ReviveScreenHandler extends GenericContainerScreenHandler {

    public ReviveScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, 3);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true; // Allow players to open the GUI
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public void onSlotClick(int slotId, int button, SlotActionType actionType, PlayerEntity player) {
        ItemStack clickedStack = this.slots.get(slotId).getStack();

        // Check if the clicked item is a player head with a glint
        if (clickedStack.getItem() == Items.PLAYER_HEAD) {
            String playerName = clickedStack.get(DataComponentTypes.ITEM_NAME).getString();
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            BannedPlayerList banList = serverPlayer.getWorld().getServer().getPlayerManager().getUserBanList();

            // Find the banned player entry
            BannedPlayerEntry targetEntry = null;
            for (BannedPlayerEntry entry : banList.values()) {
                GameProfile profile = LifestealMod.getProfileFromEntry(entry);
                if (profile != null && profile.getName().equalsIgnoreCase(playerName)) {
                    targetEntry = entry;
                    break;
                }
            }

            if (targetEntry != null) {
                // Revive the player (Unban)
                executeRevive_(serverPlayer, targetEntry);

                // Remove the player head from the GUI after revival
                this.slots.get(slotId).setStack(ItemStack.EMPTY);
                this.sendContentUpdates(); // Notify the client to refresh the GUI

                // Closing the GUI after revival
                ((ServerPlayerEntity) player).closeHandledScreen();
            } else {
                 player.sendMessage(Text.literal("Could not find banned player: " + playerName).formatted(Formatting.RED), true);
            }
        }

        if (clickedStack.getItem() == Items.GRAY_STAINED_GLASS_PANE) {
            // Closing the GUI when the player clicks on the gray stained glass pane
            ((ServerPlayerEntity) player).closeHandledScreen();
        }

        // Call the parent class method to ensure normal behavior for other slots
        super.onSlotClick(slotId, button, actionType, player);
    }

    private int executeRevive_(ServerPlayerEntity player, BannedPlayerEntry targetEntry) {
        BannedPlayerList banList = player.getWorld().getServer().getPlayerManager().getUserBanList();
        banList.remove(targetEntry); // Unban the player

        player.sendMessage(Text.literal("Succesfully revived the player!").formatted(Formatting.GREEN),
                true);

        // loop through player items and remove revive beacon
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack itemStack = player.getInventory().getStack(i);
            if (itemStack.getItem() == Items.BEACON && itemStack.hasGlint()
                    && itemStack.getName().getString().equals("Revive Beacon")) {
                itemStack.decrement(1);
                break;
            }
        }

        return 1;
    }
}
