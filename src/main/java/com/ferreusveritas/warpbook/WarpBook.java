package com.ferreusveritas.warpbook;

import static com.ferreusveritas.warpbook.compat.AntiqueAtlasCompat.ANTIQUE_ATLAS_ID;

import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ferreusveritas.warpbook.commands.CreateWaypointCommand;
import com.ferreusveritas.warpbook.commands.DeleteWaypointCommand;
import com.ferreusveritas.warpbook.commands.GiveWarpCommand;
import com.ferreusveritas.warpbook.commands.ListWaypointCommand;
import com.ferreusveritas.warpbook.core.WarpDrive;
import com.ferreusveritas.warpbook.event.WarpEventHandler;
import com.ferreusveritas.warpbook.gui.GuiManager;
import com.ferreusveritas.warpbook.item.WarpBookItem;
import com.ferreusveritas.warpbook.net.packet.PacketEffect;
import com.ferreusveritas.warpbook.net.packet.PacketSyncWaypoints;
import com.ferreusveritas.warpbook.net.packet.PacketWarp;
import com.ferreusveritas.warpbook.net.packet.PacketWaypointName;
import com.ferreusveritas.warpbook.util.WarpUtils;
import com.ferreusveritas.warpbook.util.Waypoint;

import net.minecraft.block.Block;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.registries.IForgeRegistry;

@Mod(modid = ModConstants.MODID, name = ModConstants.NAME, version = ModConstants.VERSION)
public class WarpBook {
	@Mod.Instance(value = ModConstants.MODID)
	public static WarpBook instance;

	public static final Logger logger = LogManager.getLogger(ModConstants.MODID);
	public static final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(ModConstants.MODID);

	@SidedProxy(clientSide = "com.ferreusveritas.warpbook.client.ClientProxy", serverSide = "com.ferreusveritas.warpbook.Proxy")
	public static Proxy proxy;

	public static WarpDrive warpDrive = new WarpDrive();
	public static ModItems items;
	public static ModBlocks blocks;
	public static Crafting crafting;

	private static int guiIndex = 42;

	public static float exhaustionCoefficient;
	public static double minExhaustionDistance;
	public static double maxExhaustionDistance;
	public static float distanceCoefficient;
	public static boolean deathPagesEnabled = true;
	public static Integer[] disabledDestinations;
	public static Integer[] disabledLeaving;

	public static Boolean antiqueAtlasCompat;

	public static final int WarpBookWarpGuiIndex = guiIndex++;
	public static final int WarpBookWaypointGuiIndex = guiIndex++;
	public static final int WarpBookInventoryGuiIndex = guiIndex++;
	public static final int BookClonerInventoryGuiIndex = guiIndex++;

	public static HashMap<EntityPlayer, ItemStack> lastHeldBooks = new HashMap<EntityPlayer, ItemStack>();
	public static HashMap<EntityPlayer, ItemStack> formingPages = new HashMap<EntityPlayer, ItemStack>();

	private static Configuration config;

	public static CreativeTabs tabBook = new CreativeTabs("tabWarpBook") {
	@Override
	@SideOnly(Side.CLIENT)
	public ItemStack getTabIconItem() {
			return new ItemStack(items.warpBookItem);
		}
	};

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile());
		config.load();

		exhaustionCoefficient = (float)config.get("tweaks", "exhaustion_coefficient", 0.0f, "factor for determining hunger penalty").getDouble(0.0);
		distanceCoefficient = (float)config.get("tweaks", "distance_coefficient", 1 / 256.0, "hunger cost factor per block travelled via teleportation").getDouble(0.0);
		minExhaustionDistance = config.get("tweaks", "min_exhaustion_distance", 256.0, "minimum distance of hunger penalty factor").getDouble(256.0);
		maxExhaustionDistance = config.get("tweaks", "max_exhaustion_distance", 16384.0, "maximum distance of hunger penalty factor").getDouble(16384.0);

		deathPagesEnabled = config.get("features", "death_pages", true).getBoolean(true);
		int[] disabledDestinationsP = config.get("features", "disabled_destination_dimensions", new int[] {}).getIntList();
		int[] disabledLeavingP = config.get("features", "disabled_departing_dimensions", new int[] {}).getIntList();
		disabledDestinations = new Integer[disabledDestinationsP.length];
		disabledLeaving = new Integer[disabledLeavingP.length];
		for (int i = 0; i < disabledDestinationsP.length; ++i) {
			disabledDestinations[i] = disabledDestinationsP[i];
		}
		for (int i = 0; i < disabledLeavingP.length; ++i) {
			disabledLeaving[i] = disabledLeavingP[i];
		}

		antiqueAtlasCompat = (
				config.get("features", "antique_atlas_compatibility", true, "(Antique Atlas) Whether to create a new marker on every Atlas when creating a waypoint.").getBoolean(true)
				&& Loader.isModLoaded(ANTIQUE_ATLAS_ID)
		);

		items = new ModItems();
		blocks = new ModBlocks();
		crafting = new Crafting();
		config.save();
	}
	
	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(WarpEventHandler.EVENT_HANDLER);
		NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiManager());
		proxy.registerRenderers();
	}
	
	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		int disc = 0;
		network.registerMessage(PacketWarp.class, PacketWarp.class, disc++, Side.SERVER);
		network.registerMessage(PacketWaypointName.class, PacketWaypointName.class, disc++, Side.SERVER);
		network.registerMessage(PacketSyncWaypoints.class, PacketSyncWaypoints.class, disc++, Side.CLIENT);
		network.registerMessage(PacketEffect.class, PacketEffect.class, disc++, Side.CLIENT);
		MinecraftForge.EVENT_BUS.register(proxy);
		MinecraftForge.EVENT_BUS.register(this);
	}
	
	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
		ServerCommandManager manager = ((ServerCommandManager)FMLCommonHandler.instance().getMinecraftServerInstance().getCommandManager());
		manager.registerCommand(new CreateWaypointCommand());
		manager.registerCommand(new ListWaypointCommand());
		manager.registerCommand(new DeleteWaypointCommand());
		manager.registerCommand(new GiveWarpCommand());
	}
	
	@SubscribeEvent
	public void onHurt(LivingHurtEvent event) {
		if (WarpBook.deathPagesEnabled && event.getEntity() instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer)event.getEntity();
			if (event.getSource() != DamageSource.OUT_OF_WORLD && player.getHealth() <= event.getAmount()) {
				for (ItemStack item : player.inventory.mainInventory) {
					if (item != null && item.getItem() instanceof WarpBookItem && WarpBookItem.getRespawnsLeft(item) > 0) {
						WarpBookItem.decrRespawnsLeft(item);
						WarpWorldStorage s = WarpWorldStorage.get(player.world);
						s.setLastDeath(player.getGameProfile().getId(), player.posX, player.posY, player.posZ, player.dimension);
						s.save(player.world);
						break;
					}
				}
			}
		}
	}
	
	@SubscribeEvent
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		if (WarpBook.deathPagesEnabled) {
			WarpWorldStorage s = WarpWorldStorage.get(event.player.world);
			Waypoint death = s.getLastDeath(event.player.getGameProfile().getId());
			if (death != null) {
				s.clearLastDeath(event.player.getGameProfile().getId());
				ItemStack page = new ItemStack(items.locusWarpPageItem, 1);
				WarpUtils.Bind(page, death.x, death.y, death.z, death.dim);
				event.player.world.spawnEntity(new EntityItem(event.player.world, event.player.posX, event.player.posY, event.player.posZ, page));
				s.save(event.player.world);
			}
		}
	}
	
	///////////////////////////////////////////
	// REGISTRATION
	///////////////////////////////////////////
	
	@Mod.EventBusSubscriber(modid = ModConstants.MODID)
	public static class RegistrationHandler {
		
		@SubscribeEvent
		public static void registerBlocks(final RegistryEvent.Register<Block> event) {
			final IForgeRegistry<Block> registry = event.getRegistry();
			
			blocks.register(registry);
		}
		
		@SubscribeEvent
		public static void registerItems(final RegistryEvent.Register<Item> event) {
			final IForgeRegistry<Item> registry = event.getRegistry();
			
			items.register(registry);
		}
		
		@SubscribeEvent(priority = EventPriority.LOWEST)
		public static void registerRecipes(final RegistryEvent.Register<IRecipe> event) {
			final IForgeRegistry<IRecipe> registry = event.getRegistry();
			
			crafting.register(registry);
		}
		
	}
	
}
