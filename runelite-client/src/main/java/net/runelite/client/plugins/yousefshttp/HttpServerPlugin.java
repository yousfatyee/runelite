package net.runelite.client.plugins.yousefshttp;

import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.events.ChatMessage;
import com.google.inject.Provides;
import net.runelite.api.events.GameTick;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.RuneLiteAPI;


@PluginDescriptor(
		name = "Morg HTTP Client",
		description = "Actively logs the player status to localhost on port 8081.",
		tags = {"status", "stats"},
		enabledByDefault = true
)
@Slf4j
public class HttpServerPlugin extends Plugin
{
	//wintertodt
	private static final int WINTERTODT_REGION_ID = 6462;
	public static final int WINTERTODT_WIDGET_GROUP_ID = 396;
	private static final int WINTERTODT_HEALTH_WIDGET_ID = 26;
	private static final int WINTERTODT_WORTH_METER = 20;

	private Widget healthWidget;
	private Widget warmthWidget;

	@Getter(AccessLevel.PACKAGE)
	private boolean isInWintertodt;

	@Getter(AccessLevel.PACKAGE)
	private int wintertodtHealth = 0;

	@Getter(AccessLevel.PACKAGE)
	private int wintertodtWarmth = 0;

	@Getter(AccessLevel.PACKAGE)
	private boolean wintertodtActive = false;

	private static final Duration WAIT = Duration.ofSeconds(5);
	@Inject
	public Client client;
	public Skill[] skillList;
	public XpTracker xpTracker;
	public Skill mostRecentSkillGained;
	public int tickCount = 0;
	public long startTime = 0;
	public long currentTime = 0;
	public int[] xp_gained_skills;
	@Inject
	public HttpServerConfig config;
	@Inject
	public ClientThread clientThread;
	public HttpServer server;
	public int MAX_DISTANCE = 1200;
	public String msg;
	@Provides
	private HttpServerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HttpServerConfig.class);
	}

	private boolean isInWintertodtRegion()
	{
		if (client.getLocalPlayer() != null)
		{
			return client.getLocalPlayer().getWorldLocation().getRegionID() == WINTERTODT_REGION_ID;
		}

		return false;
	}

	@Override
	protected void startUp() throws Exception
	{
		//MAX_DISTANCE = config.reachedDistance();
		skillList = Skill.values();
		xpTracker = new XpTracker(this);
		server = HttpServer.create(new InetSocketAddress(config.port()), 0);
		server.createContext("/stats", this::handleStats);
		server.createContext("/inv", handlerForInv(InventoryID.INVENTORY));
		server.createContext("/equip", handlerForInv(InventoryID.EQUIPMENT));
		server.createContext("/events", this::handleEvents);
		server.createContext("/wintertodt",this::handleWintertodt);
		server.setExecutor(Executors.newCachedThreadPool());
		startTime = System.currentTimeMillis();
		xp_gained_skills = new int[Skill.values().length];
		int skill_count = 0;
		server.start();
		for (Skill skill : Skill.values())
		{
			xp_gained_skills[skill_count] = 0;
			skill_count++;
		}
	}
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		msg = event.getMessage();
		//System.out.println("onChatmsg:" + msg);
	}

	@Override
	protected void shutDown() throws Exception
	{
		server.stop(1);
	}
	public Client getClient() {
		return client;
	}

	private void reset()
	{
		healthWidget = null;
		warmthWidget = null;
		wintertodtHealth = 0;
		wintertodtWarmth = 0;
	}

	public void handleWintertodt(HttpExchange exchange) throws IOException
	{
		if (!isInWintertodtRegion())
		{
			if (isInWintertodt)
			{
				log.debug("Left Wintertodt!");
				reset();
				isInWintertodt = false;
			}
			return;
		}

		if(isInWintertodt)
		{
			healthWidget = client.getWidget(WINTERTODT_WIDGET_GROUP_ID, WINTERTODT_HEALTH_WIDGET_ID);
			warmthWidget = client.getWidget(WINTERTODT_WIDGET_GROUP_ID, WINTERTODT_WORTH_METER);

			if (healthWidget != null) {
				// widget.getText() returns "Wintertodt's Energy: 100%" so we need to get an int
				String text = healthWidget.getText();
				if (text.contains("return")){
					wintertodtActive = false;
				}else{
					wintertodtActive = true;
				}
				if(text != null && !text.isEmpty() && text.replaceAll("[^0-9]", "") != "")
				{
					wintertodtHealth = Integer.parseInt(text.replaceAll("[^0-9]", ""));
				}
			}

			if(warmthWidget != null){
				String text = warmthWidget.getText();
				if(text != null && !text.isEmpty() && text.replaceAll("[^0-9]", "") != "")
				{
					wintertodtWarmth = Integer.parseInt(text.replaceAll("[^0-9]", ""));
				}
			}

		}else
		{
			reset();
			log.debug("Entered Wintertodt!");
			isInWintertodt = true;
		}

		JsonObject object = new JsonObject();
		object.addProperty("warmth", wintertodtWarmth);
		object.addProperty("hp", wintertodtHealth);
		object.addProperty("active", wintertodtActive);
		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(object, out);
		}
	}
	@Subscribe
	public void onGameTick(GameTick tick)
	{

		currentTime = System.currentTimeMillis();
		xpTracker.update();
		int skill_count = 0;
		for (Skill skill : Skill.values())
		{
			int xp_gained = handleTracker(skill);
			xp_gained_skills[skill_count] = xp_gained;
			skill_count ++;
		}
		tickCount++;

	}

	public int handleTracker(Skill skill){
		int startingSkillXp = xpTracker.getXpData(skill, 0);
		int endingSkillXp = xpTracker.getXpData(skill, tickCount);
		int xpGained = endingSkillXp - startingSkillXp;
		return xpGained;
	}

	public void handleStats(HttpExchange exchange) throws IOException
	{
		Player player = client.getLocalPlayer();
		JsonArray skills = new JsonArray();
		JsonObject headers = new JsonObject();
		headers.addProperty("username", client.getUsername());
		headers.addProperty("player name", player.getName());
		int skill_count = 0;
		skills.add(headers);
		for (Skill skill : Skill.values())
		{
			JsonObject object = new JsonObject();
			object.addProperty("stat", skill.getName());
			object.addProperty("level", client.getRealSkillLevel(skill));
			object.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
			object.addProperty("xp", client.getSkillExperience(skill));
			object.addProperty("xp gained", String.valueOf(xp_gained_skills[skill_count]));
			skills.add(object);
			skill_count++;
		}

		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(skills, out);
		}
	}

	public void handleEvents(HttpExchange exchange) throws IOException
	{
		MAX_DISTANCE = config.reachedDistance();
		Player player = client.getLocalPlayer();
		Actor npc = player.getInteracting();
		String npcName;
		int npcHealth;
		int npcHealth2;
		int health;
		int minHealth = 0;
		int maxHealth = 0;
		if (npc != null)
		{
			npcName = npc.getName();
			npcHealth = npc.getHealthScale();
			npcHealth2 = npc.getHealthRatio();
			health = 0;
			if (npcHealth2 > 0)
			{
				minHealth = 1;
				if (npcHealth > 1)
				{
					if (npcHealth2 > 1)
					{
						// This doesn't apply if healthRatio = 1, because of the special case in the server calculation that
						// health = 0 forces healthRatio = 0 instead of the expected healthRatio = 1
						minHealth = (npcHealth * (npcHealth2 - 1) + npcHealth - 2) / (npcHealth- 1);
					}
					maxHealth = (npcHealth * npcHealth2 - 1) / (npcHealth- 1);
					if (maxHealth > npcHealth)
					{
						maxHealth = npcHealth;
					}
				}
				else
				{
					// If healthScale is 1, healthRatio will always be 1 unless health = 0
					// so we know nothing about the upper limit except that it can't be higher than maxHealth
					maxHealth = npcHealth;
				}
				// Take the average of min and max possible healths
				health = (minHealth + maxHealth + 1) / 2;
			}
		}
		else
		{
			npcName = "null";
			npcHealth = 0;
			npcHealth2 = 0;
			health = 0;
		}
		final List<Integer> idlePoses = Arrays.asList(808, 813, 3418, 10075);

		JsonObject object = new JsonObject();
		JsonObject camera = new JsonObject();
		JsonObject worldPoint = new JsonObject();
		JsonObject mouse = new JsonObject();
		object.addProperty("animation", player.getAnimation());
		object.addProperty("animation pose", player.getPoseAnimation());
		boolean isIdle = player.getAnimation() == -1 && idlePoses.contains(player.getPoseAnimation());
		object.addProperty("Is idle", isIdle);
		object.addProperty("latest msg", msg);
		object.addProperty("run energy", client.getEnergy());
		int specialAttack = client.getVarpValue(300) / 10;
		object.addProperty("special attack", specialAttack);
		object.addProperty("game tick", client.getGameCycle());
		object.addProperty("health", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
		object.addProperty("interacting code", String.valueOf(player.getInteracting()));
		object.addProperty("npc name", npcName);
		object.addProperty("npc health ", minHealth);
		object.addProperty("MAX_DISTANCE", MAX_DISTANCE);
		mouse.addProperty("x", client.getMouseCanvasPosition().getX());
		mouse.addProperty("y", client.getMouseCanvasPosition().getY());
		worldPoint.addProperty("x", player.getWorldLocation().getX());
		worldPoint.addProperty("y", player.getWorldLocation().getY());
		worldPoint.addProperty("plane", player.getWorldLocation().getPlane());
		worldPoint.addProperty("regionID", player.getWorldLocation().getRegionID());
		worldPoint.addProperty("regionX", player.getWorldLocation().getRegionX());
		worldPoint.addProperty("regionY", player.getWorldLocation().getRegionY());
		camera.addProperty("yaw", client.getCameraYaw());
		camera.addProperty("pitch", client.getCameraPitch());
		camera.addProperty("x", client.getCameraX());
		camera.addProperty("y", client.getCameraY());
		camera.addProperty("z", client.getCameraZ());
		object.add("worldPoint", worldPoint);
		object.add("camera", camera);
		object.add("mouse", mouse);
		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(object, out);
		}
	}
	private HttpHandler handlerForInv(InventoryID inventoryID)
	{
		return exchange -> {
			Item[] items = invokeAndWait(() -> {
				ItemContainer itemContainer = client.getItemContainer(inventoryID);
				if (itemContainer != null)
				{
					return itemContainer.getItems();
				}
				return null;
			});

			if (items == null)
			{
				exchange.sendResponseHeaders(204, 0);
				return;
			}

			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(items, out);
			}
		};
	}
	private <T> T invokeAndWait(Callable<T> r)
	{
		try
		{
			AtomicReference<T> ref = new AtomicReference<>();
			Semaphore semaphore = new Semaphore(0);
			clientThread.invokeLater(() -> {
				try
				{

					ref.set(r.call());
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
				finally
				{
					semaphore.release();
				}
			});
			semaphore.acquire();
			return ref.get();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
}
