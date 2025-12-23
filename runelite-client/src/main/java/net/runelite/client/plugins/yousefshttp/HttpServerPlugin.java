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
import net.runelite.api.coords.WorldPoint;
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
		try {
			//MAX_DISTANCE = config.reachedDistance();
			skillList = Skill.values();
			xpTracker = new XpTracker(this);
			int port = config.port();
			server = HttpServer.create(new InetSocketAddress(port), 0);
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
		} catch (Exception e) {
			log.error("Failed to start HTTP server", e);
			throw e;
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
		if (server != null) {
			server.stop(1);
		}
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
		try {
			if (!isInWintertodtRegion())
			{
				if (isInWintertodt)
				{
					log.debug("Left Wintertodt!");
					reset();
					isInWintertodt = false;
				}
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(204, 0);
				exchange.close();
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
				isInWintertodt = true;
			}

			JsonObject object = new JsonObject();
			object.addProperty("warmth", wintertodtWarmth);
			object.addProperty("hp", wintertodtHealth);
			object.addProperty("active", wintertodtActive);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(object, out);
				out.flush();
			}
			exchange.close();
		} catch (Exception e) {
			log.error("Error handling /wintertodt request", e);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(500, 0);
			exchange.close();
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
		try {
			int startingSkillXp = xpTracker.getXpData(skill, 0);
			int endingSkillXp = xpTracker.getXpData(skill, tickCount);
			int xpGained = endingSkillXp - startingSkillXp;
			return xpGained;
		} catch (Exception e) {
			log.warn("Error calculating XP gain for skill {} at tick {}", skill, tickCount, e);
			return 0;
		}
	}

	public void handleStats(HttpExchange exchange) throws IOException
	{
		try {
			Player player = client.getLocalPlayer();
			if (player == null) {
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(503, 0);
				exchange.close();
				return;
			}

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

			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(skills, out);
				out.flush();
			}
			exchange.close();
		} catch (Exception e) {
			log.error("Error handling /stats request", e);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(500, 0);
			exchange.close();
		}
	}

	public void handleEvents(HttpExchange exchange) throws IOException
	{
		try {
			MAX_DISTANCE = config.reachedDistance();
			
			// All client calls must be on client thread
			JsonObject object = invokeAndWait(() -> {
				Player player = client.getLocalPlayer();
				if (player == null) {
					return null;
				}
				
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

				JsonObject obj = new JsonObject();
				JsonObject camera = new JsonObject();
				JsonObject worldPoint = new JsonObject();
				JsonObject mouse = new JsonObject();
				obj.addProperty("animation", player.getAnimation());
				obj.addProperty("animation pose", player.getPoseAnimation());
				boolean isIdle = player.getAnimation() == -1 && idlePoses.contains(player.getPoseAnimation());
				obj.addProperty("Is idle", isIdle);
				obj.addProperty("latest msg", msg != null ? msg : "");
				obj.addProperty("run energy", client.getEnergy());
				int specialAttack = client.getVarpValue(300) / 10;
				obj.addProperty("special attack", specialAttack);
				obj.addProperty("game tick", client.getGameCycle());
				obj.addProperty("health", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
				obj.addProperty("interacting code", String.valueOf(player.getInteracting()));
				obj.addProperty("npc name", npcName);
				obj.addProperty("npc health ", minHealth);
				obj.addProperty("MAX_DISTANCE", MAX_DISTANCE);
				mouse.addProperty("x", client.getMouseCanvasPosition().getX());
				mouse.addProperty("y", client.getMouseCanvasPosition().getY());
				
				// Safely get world location - might be null if player isn't fully loaded
				try {
					WorldPoint worldLoc = player.getWorldLocation();
					if (worldLoc != null) {
						worldPoint.addProperty("x", worldLoc.getX());
						worldPoint.addProperty("y", worldLoc.getY());
						worldPoint.addProperty("plane", worldLoc.getPlane());
						worldPoint.addProperty("regionID", worldLoc.getRegionID());
						worldPoint.addProperty("regionX", worldLoc.getRegionX());
						worldPoint.addProperty("regionY", worldLoc.getRegionY());
					} else {
						worldPoint.addProperty("x", 0);
						worldPoint.addProperty("y", 0);
						worldPoint.addProperty("plane", 0);
						worldPoint.addProperty("regionID", 0);
						worldPoint.addProperty("regionX", 0);
						worldPoint.addProperty("regionY", 0);
					}
				} catch (Exception e) {
					worldPoint.addProperty("x", 0);
					worldPoint.addProperty("y", 0);
					worldPoint.addProperty("plane", 0);
					worldPoint.addProperty("regionID", 0);
					worldPoint.addProperty("regionX", 0);
					worldPoint.addProperty("regionY", 0);
				}
				
				camera.addProperty("yaw", client.getCameraYaw());
				camera.addProperty("pitch", client.getCameraPitch());
				camera.addProperty("x", client.getCameraX());
				camera.addProperty("y", client.getCameraY());
				camera.addProperty("z", client.getCameraZ());
				obj.add("worldPoint", worldPoint);
				obj.add("camera", camera);
				obj.add("mouse", mouse);
				return obj;
			});
			
			if (object == null) {
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(503, 0);
				exchange.close();
				return;
			}
			
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(object, out);
				out.flush();
			}
			exchange.close();
		} catch (Exception e) {
			log.error("Error handling /events request", e);
			try {
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
				JsonObject error = new JsonObject();
				error.addProperty("error", e.getMessage());
				error.addProperty("type", e.getClass().getSimpleName());
				exchange.sendResponseHeaders(500, 0);
				try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
					RuneLiteAPI.GSON.toJson(error, out);
					out.flush();
				}
				exchange.close();
			} catch (Exception ex) {
				log.error("Error sending error response", ex);
			}
		}
	}
	private HttpHandler handlerForInv(InventoryID inventoryID)
	{
		return exchange -> {
			try {
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
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(204, 0);
					exchange.close();
					return;
				}

			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(items, out);
				out.flush();
			}
			exchange.close();
			} catch (Exception e) {
				log.error("Error handling inventory request", e);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(500, 0);
				exchange.close();
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
