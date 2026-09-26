package villagercycler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VillagerCyclerPlugin extends JavaPlugin implements Listener {

    private NamespacedKey cyclerKey;
    private final Map<UUID, ItemStack> swappedItems = new HashMap<>();
    private final int SWAP_SLOT = 0; // Hotbar Slot 1 (Index 0)

    @Override
    public void onEnable() {
        this.cyclerKey = new NamespacedKey(this, "is_cycler_emerald");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (swappedItems.containsKey(player.getUniqueId())) {
                player.closeInventory();
            }
        }
    }

    @EventHandler
    public void onTradeOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) return;
        if (!(merchantInventory.getMerchant() instanceof Villager villager)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        if (villager.getVillagerExperience() > 0) return;

        ItemStack cyclerEmerald = new ItemStack(Material.EMERALD);
        ItemMeta meta = cyclerEmerald.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Click to Cycle Trades", NamedTextColor.GREEN));
            meta.getPersistentDataContainer().set(cyclerKey, PersistentDataType.BYTE, (byte) 1);
            cyclerEmerald.setItemMeta(meta);
        }

        ItemStack existingItem = player.getInventory().getItem(SWAP_SLOT);
        
        if (existingItem != null && existingItem.getType() != Material.AIR) {
            if (!isCycler(existingItem)) {
                swappedItems.put(player.getUniqueId(), existingItem.clone());
            }
        }
        
        player.getInventory().setItem(SWAP_SLOT, cyclerEmerald);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (isCycler(hotbarItem) || isCycler(event.getCurrentItem())) {
                event.setCancelled(true);
                player.updateInventory();
                return;
            }
        }

        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        boolean clickedCycler = isCycler(clickedItem);
        boolean cursorCycler = isCycler(cursorItem);

        if (!clickedCycler && !cursorCycler) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (isCycler(item)) {
                        event.setCancelled(true);
                        player.updateInventory();
                        return;
                    }
                }
            }
            return;
        }

        event.setCancelled(true);

        if (cursorCycler) {
            event.getWhoClicked().setItemOnCursor(null);
        }

        if (clickedCycler && event.getClickedInventory() != null) {
            if (event.getClickedInventory() instanceof MerchantInventory) {
                event.getClickedInventory().setItem(event.getSlot(), null);
                player.updateInventory();
                return;
            }

            if (event.getClickedInventory().equals(player.getInventory())) {
                if (!(player.getOpenInventory().getTopInventory() instanceof MerchantInventory merchantInventory)) return;
                if (!(merchantInventory.getMerchant() instanceof Villager villager)) return;

                cycleVillager(villager);
                
                // Mouse-jump fix: We no longer forcefully re-open the UI here.
                // The server will seamlessly push the new trades to the open window.
                player.updateInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isCycler(event.getOldCursor()) || isCycler(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack item : event.getNewItems().values()) {
            if (isCycler(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        
        if (isCycler(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        
        cleanUpAndRestore(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanUpAndRestore(event.getPlayer());
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (isCycler(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getItemDrop().remove();
            cleanUpAndRestore(event.getPlayer());
        }
    }

    private void cycleVillager(Villager villager) {
        Villager.Profession currentProfession = villager.getProfession();
        villager.setRecipes(new ArrayList<>());
        villager.setProfession(Villager.Profession.NONE);
        villager.setProfession(currentProfession);
    }

    private void cleanUpAndRestore(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isCycler(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }

        UUID playerId = player.getUniqueId();
        if (swappedItems.containsKey(playerId)) {
            ItemStack originalItem = swappedItems.get(playerId);
            
            ItemStack slot1 = player.getInventory().getItem(SWAP_SLOT);
            if (slot1 == null || slot1.getType() == Material.AIR || isCycler(slot1)) {
                player.getInventory().setItem(SWAP_SLOT, originalItem);
            } else {
                player.getInventory().addItem(originalItem);
            }
            
            swappedItems.remove(playerId);
        }
        
        player.updateInventory();
    }

    private boolean isCycler(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(cyclerKey, PersistentDataType.BYTE);
    }
}
