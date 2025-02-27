package com.elmakers.mine.bukkit.magic.listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;

import com.elmakers.mine.bukkit.api.event.CraftWandEvent;
import com.elmakers.mine.bukkit.api.item.ItemData;
import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.configuration.MagicConfiguration;
import com.elmakers.mine.bukkit.crafting.MagicRecipe;
import com.elmakers.mine.bukkit.crafting.RecipeMatchType;
import com.elmakers.mine.bukkit.magic.MagicController;
import com.elmakers.mine.bukkit.utility.CompatibilityLib;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.wand.Wand;

public class CraftingController implements Listener {
    private final MagicController controller;
    private boolean craftingEnabled = false;
    private boolean allowWandsAsIngredients = true;
    private Map<Material, List<MagicRecipe>> recipes = new HashMap<>();
    private Set<String> recipeKeys = new HashSet<>();
    private Set<String> autoDiscoverRecipeKeys = new HashSet<>();

    public CraftingController(MagicController controller) {
        this.controller = controller;
    }

    public void load(ConfigurationSection configuration) {
        for (List<MagicRecipe> recipeList : recipes.values()) {
            for (MagicRecipe recipe : recipeList) {
                recipe.unregister(controller.getPlugin());
            }
        }
        recipes.clear();
        recipeKeys.clear();
        autoDiscoverRecipeKeys.clear();
        if (!craftingEnabled) {
            return;
        }
        Set<String> recipeKeys = configuration.getKeys(false);
        for (String key : recipeKeys)
        {
            ConfigurationSection parameters = configuration.getConfigurationSection(key);
            if (!ConfigurationUtils.isEnabled(parameters)) continue;
            parameters = MagicConfiguration.getKeyed(controller, parameters, "recipe", key);

            MagicRecipe recipe = MagicRecipe.loadRecipe(controller, key, parameters);
            if (recipe == null) {
                controller.getLogger().warning("Failed to create crafting recipe: " + key);
                continue;
            }
            Material outputType = recipe.getOutputType();
            List<MagicRecipe> similar = recipes.get(outputType);
            if (similar == null) {
                similar = new ArrayList<>();
                recipes.put(outputType, similar);
            }
            similar.add(recipe);
            this.recipeKeys.add(recipe.getKey());
            if (recipe.isAutoDiscover()) {
                autoDiscoverRecipeKeys.add(recipe.getKey());
            }
        }
    }

    public void loadMainConfiguration(ConfigurationSection configuration) {
        craftingEnabled = configuration.getBoolean("enable_crafting", craftingEnabled);
        allowWandsAsIngredients = craftingEnabled && configuration.getBoolean("allow_wands_as_ingredients", allowWandsAsIngredients);
    }

    public boolean hasCraftPermission(Player player, MagicRecipe recipe)
    {
        if (player == null) return false;

        if (controller.hasBypassPermission(player)) {
            return true;
        }
        if (!controller.hasPermission(player, "magic.wand.craft")) {
            return false;
        }
        if (!controller.hasPermission(player, "magic.craft." + recipe.getKey())) {
            return false;
        }
        if (!recipe.isLocked()) {
            return true;
        }
        Mage mage = controller.getRegisteredMage(player);
        if (mage == null) {
            return false;
        }

        return mage.canCraft(recipe.getKey());
    }

    public void register(MagicController controller, Plugin plugin) {
        if (!craftingEnabled) {
            return;
        }
        for (List<MagicRecipe> list : recipes.values()) {
            for (MagicRecipe recipe : list) {
                recipe.preregister(plugin);
            }
        }
        for (List<MagicRecipe> list : recipes.values()) {
            for (MagicRecipe recipe : list) {
                recipe.register(controller, plugin);
            }
        }
    }

    @EventHandler
    public void onPrepareCraftItem(PrepareItemCraftEvent event)
    {
        CraftingInventory inventory = event.getInventory();
        ItemStack[] contents = inventory.getMatrix();

        // Check for wands placed in the crafting inventory
        for (int i = 0; i < 9 && i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isCraftable(item)) {
                inventory.setResult(new ItemStack(Material.AIR));
                return;
            }
        }

        if (!craftingEnabled) return;

        Recipe recipe = event.getRecipe();
        if (recipe == null) return;
        ItemStack result = recipe.getResult();
        if (result == null) return;
        Material resultType = result.getType();
        List<MagicRecipe> candidates = recipes.get(resultType);
        RecipeMatchType matchType = RecipeMatchType.NONE;
        if (candidates != null && !candidates.isEmpty()) {
            for (MagicRecipe candidate : candidates) {
                matchType = candidate.getMatchType(recipe, contents);
                Material substitute = candidate.getSubstitute();
                if (matchType != RecipeMatchType.NONE) {
                    for (HumanEntity human : event.getViewers()) {
                        if (human instanceof Player && !hasCraftPermission((Player) human, candidate)) {
                            inventory.setResult(new ItemStack(Material.AIR));
                            return;
                        }
                    }

                    if (matchType == RecipeMatchType.PARTIAL) {
                        continue;
                    } else if (matchType == RecipeMatchType.MATCH) {
                        ItemStack crafted = candidate.craft();
                        inventory.setResult(crafted);
                        for (HumanEntity human : event.getViewers()) {
                            candidate.crafted(human, controller);
                        }
                    }
                    break;
                } else if (substitute != null) {
                    inventory.setResult(new ItemStack(substitute, 1));
                }
            }
        }

        // We may need to prevent magic items from being used as ingredients if this did not match
        boolean preventCrafting = matchType == RecipeMatchType.PARTIAL;
        if (!preventCrafting && matchType == RecipeMatchType.NONE) {
            for (ItemStack item : contents) {
                ItemData itemData = controller.getItem(item);
                if (itemData != null && itemData.isExactIngredient()) {
                    preventCrafting = true;
                    break;
                }
            }
        }

        // Force-prevent crafting if we got a partial match
        if (preventCrafting) {
            inventory.setResult(new ItemStack(Material.AIR));
        }
    }

    private boolean isCraftable(ItemStack item) {
        if (Wand.isSpecial(item)) {
            if (!allowWandsAsIngredients) {
                return false;
            }
            return CompatibilityLib.getNBTUtils().getBoolean(item, "craftable", false);
        }
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.isCancelled()) return;

        InventoryType inventoryType = event.getInventory().getType();
        InventoryAction action = event.getAction();
        SlotType slotType = event.getSlotType();
        // Check for wand clicks to prevent grinding them to dust, or whatever.
        if (slotType == SlotType.CRAFTING && (inventoryType == InventoryType.CRAFTING || inventoryType == InventoryType.WORKBENCH)) {
            ItemStack cursor = event.getCursor();
            if (!isCraftable(cursor)) {
                event.setCancelled(true);
            }
        }
        if (slotType != SlotType.CRAFTING && inventoryType == InventoryType.WORKBENCH && action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack clicked = event.getCurrentItem();
            if (!isCraftable(clicked)) {
                event.setCancelled(true);
            }
        }
        if (slotType == SlotType.CRAFTING && (inventoryType == InventoryType.CRAFTING || inventoryType == InventoryType.WORKBENCH) && (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD)) {
            int hotbarButton = event.getHotbarButton();
            if (hotbarButton >= 0) {
                ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbarButton);
                if (!isCraftable(hotbarItem)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    // Borrowed from InventoryView and pruned,
    // TODO: Switch to InventoryView.getSlotType when dropping 1.9 compat
    public final boolean isCraftingSlot(InventoryView view, int slot) {
        if (slot >= 0 && slot < view.getTopInventory().getSize()) {
            if (view.getType() == InventoryType.WORKBENCH || view.getType() == InventoryType.CRAFTING) {
                return slot > 0;
            }
        }
        return false;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.isCancelled()) return;
        ItemStack item = event.getOldCursor();
        // Unfortunately this event gives us a shallow copy of the item so we need to dig a little bit.
        item = item.hasItemMeta() ? CompatibilityLib.getItemUtils().makeReal(item) : item;
        if (isCraftable(item)) return;

        InventoryView view = event.getView();
        for (Integer slot : event.getRawSlots()) {
            if (slot != null && isCraftingSlot(view, slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player)) return;

        Player player = (Player)human;
        Mage mage = controller.getMage(player);

        // Don't allow crafting in the wand inventory.
        if (mage.hasStoredInventory()) {
            event.setCancelled(true);
            return;
        }

        ItemStack currentItem = event.getCurrentItem();
        if (Wand.isWand(currentItem)) {
            currentItem = currentItem.clone();
            CraftWandEvent castEvent = new CraftWandEvent(mage, controller.getWand(currentItem));
            Bukkit.getPluginManager().callEvent(castEvent);
        }
    }

    public boolean isEnabled()
    {
        return craftingEnabled;
    }

    public int getCount() {
        return recipeKeys.size();
    }

    public List<String> getRecipeKeys() {
        return new ArrayList<>(recipeKeys);
    }

    public List<String> getAutoDiscoverRecipeKeys() {
        return new ArrayList<>(autoDiscoverRecipeKeys);
    }
}
